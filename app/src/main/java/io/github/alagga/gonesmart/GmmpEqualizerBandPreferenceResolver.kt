package io.github.alagga.gonesmart

import java.util.Locale

/**
 * Passively identifies GMMP's persisted Equalizer band-count preference.
 *
 * The preference name is obfuscated/configuration evidence rather than an API,
 * so accept it only when the native audio store contains exactly one semantic
 * band-count candidate whose value is a supported 2..10 layout. Ambiguity
 * fails closed.
 */
object GmmpEqualizerBandPreferenceResolver {
    data class Candidate(val key: String, val count: Int)

    fun resolve(values: Map<String, *>): Candidate? {
        val candidates = values.mapNotNull { (key, raw) ->
            val count = integralCount(raw) ?: return@mapNotNull null
            if (!GmmpEqualizerBandPolicy.isSupported(count)) return@mapNotNull null
            if (!looksLikeEqualizerBandCountKey(key)) return@mapNotNull null
            Candidate(key, count)
        }
        return candidates.distinctBy { it.key }.singleOrNull()
    }

    internal fun looksLikeEqualizerBandCountKey(key: String): Boolean {
        val normalized = key.trim().lowercase(Locale.US)
        if (!normalized.contains("band")) return false

        val equalizerContext =
            normalized.contains("equalizer") ||
                normalized.startsWith("eq") ||
                normalized.contains("_eq") ||
                normalized.contains(".eq")
        val dspContext = normalized.contains("dsp")
        val countContext =
            normalized.contains("count") ||
                normalized.contains("num") ||
                normalized.contains("bands") ||
                normalized.endsWith("band")

        return equalizerContext || (dspContext && countContext)
    }

    private fun integralCount(raw: Any?): Int? {
        val value = when (raw) {
            is Byte -> raw.toLong()
            is Short -> raw.toLong()
            is Int -> raw.toLong()
            is Long -> raw
            is Float -> raw.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
            is Double -> raw.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: return null
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return value.toInt()
    }
}
