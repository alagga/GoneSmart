package io.github.alagga.gonesmart

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Strict parser for GMMP Equalizer preset files. It accepts several text
 * layouts used by settings/preset stores and the minimal raw 11-value float /
 * double layout, but never invents a missing PRE or band value.
 */
object GmmpEqualizerPresetParser {
    data class ParsedPreset(
        val name: String,
        val shape: Map<String, String>
    )

    private val frequencies = listOf(32, 64, 128, 256, 512, 1000, 2000, 4000, 8000, 16000)
    private val numberPattern = "[-+]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)(?:[eE][-+]?\\d+)?"
    private val explicitNameRegex = Regex(
        "(?i)(?:\\\"?preset_?name\\\"?\\s*[:=]\\s*\\\"([^\\\"\\r\\n]+)\\\"|" +
            "<preset[^>]*\\bname=\\\"([^\\\"]+)\\\"[^>]*>)"
    )
    private val preRegexes = listOf(
        Regex("(?i)(?:preamp|pre[_ -]?gain|pregain|\\bpre\\b)[^0-9+\\-]{0,32}($numberPattern)"),
        Regex("(?i)(?:name|key)\\s*=\\s*\\\"(?:preamp|pre[_ -]?gain|pregain|pre)\\\"[^>]{0,80}?(?:value\\s*=\\s*\\\")?($numberPattern)")
    )

    fun parse(nameHint: String, bytes: ByteArray): ParsedPreset? {
        if (bytes.isEmpty()) return null
        val text = decodeText(bytes)
        if (text != null) {
            parseText(nameHint, text)?.let { return it }
        }
        return parseRawNumbers(nameHint, bytes)
    }

    internal fun parseText(nameHint: String, text: String): ParsedPreset? {
        val name = extractName(text) ?: cleanName(nameHint)
        if (name.isBlank()) return null

        val pre = preRegexes.asSequence()
            .mapNotNull { regex -> regex.find(text)?.groupValues?.getOrNull(1)?.toNumber() }
            .firstOrNull { plausible(it) }

        val bandsByFrequency = linkedMapOf<Int, Double>()
        frequencies.forEach { frequency ->
            val token = if (frequency >= 1000) {
                val khz = frequency / 1000
                "(?:${frequency}|${khz}\\s*k)"
            } else {
                frequency.toString()
            }
            val regexes = listOf(
                Regex("(?i)(?:band|gain|eq)?[_ .-]*$token(?:\\s*hz)?[^0-9+\\-]{0,32}($numberPattern)"),
                Regex("(?i)(?:name|key)\\s*=\\s*\\\"(?:band|gain|eq)?[_ .-]*$token(?:\\s*hz)?\\\"[^>]{0,80}?(?:value\\s*=\\s*\\\")?($numberPattern)")
            )
            val value = regexes.asSequence()
                .mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.toNumber() }
                .firstOrNull { plausible(it) }
            if (value != null) bandsByFrequency[frequency] = value
        }

        val bands = if (bandsByFrequency.size == frequencies.size) {
            frequencies.map { bandsByFrequency.getValue(it) }
        } else {
            parseIndexedBands(text)
        }

        if (pre != null && bands != null && bands.size == frequencies.size) {
            return buildPreset(name, pre, bands)
        }

        // Some GMMP preset files are compact numeric lists. Accept this only
        // when the entire text has exactly 11 numeric tokens and every value is
        // a valid EQ gain. That keeps unrelated metadata from being guessed as
        // a curve.
        val compact = compactNumbers(text)
        if (compact != null) {
            return buildPreset(name, compact.first(), compact.drop(1))
        }

        val numbers = Regex(numberPattern).findAll(text)
            .mapNotNull { it.value.toNumber() }
            .toList()
        if (numbers.size == 11 && numbers.all(::plausible)) {
            return buildPreset(name, numbers.first(), numbers.drop(1))
        }
        return null
    }

    private fun compactNumbers(text: String): List<Double>? {
        val trimmed = text.trim()
        val whitespace = trimmed.split(Regex("[\\s;|]+"))
            .filter { it.isNotBlank() }
        if (whitespace.size == 11) {
            val parsed = whitespace.map { it.toNumber() ?: return@map Double.NaN }
            if (parsed.all(::plausible)) return parsed
        }
        if (trimmed.count { it == ',' } == 10 && '.' !in trimmed) {
            val parsed = trimmed.split(',').map { it.trim().toDoubleOrNull() ?: Double.NaN }
            if (parsed.size == 11 && parsed.all(::plausible)) return parsed
        }
        return null
    }

    private fun parseIndexedBands(text: String): List<Double>? {
        val result = mutableMapOf<Int, Double>()
        for (index in frequencies.indices) {
            val regexes = listOf(
                Regex("(?i)(?:band|gain|eq)[_ .-]*$index\\b[^0-9+\\-]{0,32}($numberPattern)"),
                Regex("(?i)(?:name|key)\\s*=\\s*\\\"(?:band|gain|eq)[_ .-]*$index\\\"[^>]{0,80}?(?:value\\s*=\\s*\\\")?($numberPattern)")
            )
            val value = regexes.asSequence()
                .mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.toNumber() }
                .firstOrNull { plausible(it) }
                ?: return null
            result[index] = value
        }
        return frequencies.indices.map { result.getValue(it) }
    }

    private fun parseRawNumbers(nameHint: String, bytes: ByteArray): ParsedPreset? {
        val name = cleanName(nameHint)
        if (name.isBlank()) return null
        val layouts = listOf(
            4 to ByteOrder.LITTLE_ENDIAN,
            4 to ByteOrder.BIG_ENDIAN,
            8 to ByteOrder.LITTLE_ENDIAN,
            8 to ByteOrder.BIG_ENDIAN
        )
        layouts.forEach { (width, order) ->
            if (bytes.size != width * 11) return@forEach
            val buffer = ByteBuffer.wrap(bytes).order(order)
            val values = MutableList(11) {
                if (width == 4) buffer.float.toDouble() else buffer.double
            }
            if (values.all(::plausible)) {
                return buildPreset(name, values.first(), values.drop(1))
            }
        }
        return null
    }

    private fun buildPreset(name: String, pre: Double, bands: List<Double>): ParsedPreset? {
        if (!plausible(pre) || bands.size != frequencies.size || !bands.all(::plausible)) return null
        val shape = linkedMapOf<String, String>()
        val preValue = NativeAudioObservation.equalizerPre(pre) ?: return null
        shape[preValue.fieldId] = preValue.value
        bands.forEachIndexed { index, value ->
            val mapped = NativeAudioObservation.equalizerBand(index, value) ?: return null
            shape[mapped.fieldId] = mapped.value
        }
        if (shape.keys.toSet() != GoneSmartAudioProfiles.equalizerShapeFieldIds.toSet()) return null
        return ParsedPreset(name, shape)
    }

    private fun extractName(text: String): String? {
        val match = explicitNameRegex.find(text) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun cleanName(raw: String): String = raw
        .substringAfterLast('/')
        .substringBeforeLast('.', raw.substringAfterLast('/'))
        .replace('_', ' ')
        .trim()

    private fun decodeText(bytes: ByteArray): String? {
        val sample = bytes.take(256)
        val zeroRatio = sample.count { it.toInt() == 0 }.toDouble() / sample.size.coerceAtLeast(1)
        if (zeroRatio > 0.15) return null
        return runCatching { bytes.toString(Charset.forName("UTF-8")) }
            .getOrNull()
            ?.takeIf { text -> text.count { it == '\uFFFD' } < 4 }
    }

    private fun String.toNumber(): Double? =
        trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun plausible(value: Double): Boolean = value.isFinite() && value in -12.001..12.001
}
