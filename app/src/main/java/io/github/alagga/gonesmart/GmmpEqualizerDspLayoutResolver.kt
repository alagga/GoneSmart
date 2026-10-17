package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the native GMMP equalizer-layout creation call from DspManager
 * arguments without depending on obfuscated method names.
 *
 * The observed GMMP 4.2.1 diagnostic is semantically shaped as
 * `Bands: N, Rate: R, Channels: C`. We only accept calls where those three
 * values can be identified unambiguously. Once a binding has been learned,
 * later layout changes reuse the exact same method and argument positions.
 */
object GmmpEqualizerDspLayoutResolver {
    private const val MIN_SAMPLE_RATE = 8_000L
    private const val MAX_SAMPLE_RATE = 768_000L
    private const val MIN_CHANNELS = 1L
    private const val MAX_CHANNELS = 8L

    data class Binding(
        val methodId: String,
        val bandArgIndex: Int,
        val rateArgIndex: Int,
        val channelArgIndex: Int
    )

    data class Observation(
        val bandCount: Int,
        val sampleRate: Int,
        val channels: Int,
        val binding: Binding
    )

    fun candidateMethods(owner: Class<*>): List<Method> = owner.declaredMethods
        .filterNot { Modifier.isAbstract(it.modifiers) }
        .filter { method -> method.parameterTypes.count(::isIntegralType) >= 3 }

    fun infer(method: Method, args: List<Any?>): Observation? {
        val numeric = numericArguments(method, args)
        if (numeric.size < 3) return null

        // GMMP 4.2.1's direct creation boundary presents the diagnostic tuple
        // in Bands/Rate/Channels order. Prefer that exact narrow shape when it
        // is the complete numeric signature; this also disambiguates layouts
        // such as 2-8 bands where band count and channel count overlap.
        if (numeric.size == 3) {
            positionalObservation(method, numeric)?.let { return it }
        }

        // For wider signatures, only accept a structurally unique tuple. A
        // 9/10-band call is naturally strong because channels never exceed 8.
        val matches = mutableListOf<Observation>()
        for (band in numeric) {
            val bandCount = band.value.toInt()
            if (bandCount.toLong() != band.value || !GmmpEqualizerBandPolicy.isSupported(bandCount)) continue
            for (rate in numeric) {
                if (rate.index == band.index || rate.value !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) continue
                for (channels in numeric) {
                    if (channels.index == band.index || channels.index == rate.index) continue
                    if (channels.value !in MIN_CHANNELS..MAX_CHANNELS) continue
                    matches += observation(method, band, rate, channels)
                }
            }
        }

        return matches
            .distinctBy { observation ->
                Triple(
                    observation.binding.bandArgIndex,
                    observation.binding.rateArgIndex,
                    observation.binding.channelArgIndex
                )
            }
            .singleOrNull()
    }

    fun readBound(binding: Binding, method: Method, args: List<Any?>): Observation? {
        if (binding.methodId != methodId(method)) return null
        val band = integralValue(method, args, binding.bandArgIndex) ?: return null
        val rate = integralValue(method, args, binding.rateArgIndex) ?: return null
        val channels = integralValue(method, args, binding.channelArgIndex) ?: return null
        if (band !in GmmpEqualizerBandPolicy.MIN_BANDS.toLong()..GmmpEqualizerBandPolicy.MAX_BANDS.toLong()) return null
        if (rate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        if (channels !in MIN_CHANNELS..MAX_CHANNELS) return null
        return Observation(band.toInt(), rate.toInt(), channels.toInt(), binding)
    }

    fun methodId(method: Method): String = buildString {
        append(method.declaringClass.name).append('#').append(method.name).append('(')
        method.parameterTypes.forEachIndexed { index, type ->
            if (index > 0) append(',')
            append(type.name)
        }
        append(')')
    }

    private data class NumericArg(val index: Int, val value: Long)

    private fun positionalObservation(method: Method, numeric: List<NumericArg>): Observation? {
        val band = numeric[0]
        val rate = numeric[1]
        val channels = numeric[2]
        if (band.value !in GmmpEqualizerBandPolicy.MIN_BANDS.toLong()..GmmpEqualizerBandPolicy.MAX_BANDS.toLong()) return null
        if (rate.value !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        if (channels.value !in MIN_CHANNELS..MAX_CHANNELS) return null
        return observation(method, band, rate, channels)
    }

    private fun observation(
        method: Method,
        band: NumericArg,
        rate: NumericArg,
        channels: NumericArg
    ): Observation = Observation(
        bandCount = band.value.toInt(),
        sampleRate = rate.value.toInt(),
        channels = channels.value.toInt(),
        binding = Binding(
            methodId = methodId(method),
            bandArgIndex = band.index,
            rateArgIndex = rate.index,
            channelArgIndex = channels.index
        )
    )

    private fun numericArguments(method: Method, args: List<Any?>): List<NumericArg> =
        method.parameterTypes.mapIndexedNotNull { index, type ->
            if (!isIntegralType(type)) return@mapIndexedNotNull null
            integralValue(method, args, index)?.let { NumericArg(index, it) }
        }

    private fun integralValue(method: Method, args: List<Any?>, index: Int): Long? {
        if (index !in method.parameterTypes.indices || index !in args.indices) return null
        if (!isIntegralType(method.parameterTypes[index])) return null
        return when (val value = args[index]) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }

    private fun isIntegralType(type: Class<*>): Boolean =
        type == java.lang.Byte.TYPE || type == java.lang.Byte::class.java ||
            type == java.lang.Short.TYPE || type == java.lang.Short::class.java ||
            type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java ||
            type == java.lang.Long.TYPE || type == java.lang.Long::class.java
}
