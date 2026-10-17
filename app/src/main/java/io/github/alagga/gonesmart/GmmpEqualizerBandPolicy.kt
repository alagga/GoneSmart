package io.github.alagga.gonesmart

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Keeps the persisted profile format stable at ten points while following
 * GMMP's live selectable 2..10-band Equalizer layout.
 */
object GmmpEqualizerBandPolicy {
    const val MIN_BANDS = 2
    const val MAX_BANDS = 10
    const val BAND_COUNT_FIELD_ID = "eq.band_count"

    private const val MIN_PLAUSIBLE_FREQUENCY_HZ = 15.0
    private const val MAX_PLAUSIBLE_FREQUENCY_HZ = 100_000.0

    fun isSupported(count: Int): Boolean = count in MIN_BANDS..MAX_BANDS

    fun normalizedOrDefault(count: Int?, defaultCount: Int = MAX_BANDS): Int =
        count?.takeIf(::isSupported) ?: defaultCount.coerceIn(MIN_BANDS, MAX_BANDS)

    /**
     * Linearly resample one ordered graphic-EQ curve onto another point count.
     * The persisted ten points are logarithmically ordered frequency anchors,
     * so index-space interpolation is the safe fallback when GMMP's exact live
     * center frequencies have not yet been passively verified.
     */
    fun project(values: List<Double>, targetCount: Int): List<Double>? {
        if (!isSupported(values.size) || !isSupported(targetCount)) return null
        if (values.any { !it.isFinite() }) return null
        if (values.size == targetCount) return values.toList()

        val sourceLast = values.lastIndex.toDouble()
        val targetLast = (targetCount - 1).toDouble()
        return List(targetCount) { targetIndex ->
            val position = targetIndex * sourceLast / targetLast
            val lower = floor(position).toInt().coerceIn(values.indices)
            val upper = ceil(position).toInt().coerceIn(values.indices)
            if (lower == upper) {
                values[lower]
            } else {
                val fraction = position - lower
                values[lower] + (values[upper] - values[lower]) * fraction
            }
        }
    }

    /**
     * Accept only a complete, ordered audio-frequency vector for the active
     * GMMP layout. This is deliberately stricter than merely accepting N
     * doubles so unrelated indexed getters cannot become user-facing labels.
     */
    fun validatedFrequencies(values: List<Double>?, bandCount: Int): List<Double>? {
        if (!isSupported(bandCount) || values == null || values.size != bandCount) return null
        if (values.any { !it.isFinite() || it < MIN_PLAUSIBLE_FREQUENCY_HZ || it > MAX_PLAUSIBLE_FREQUENCY_HZ }) {
            return null
        }
        if (values.zipWithNext().any { (left, right) -> right <= left }) return null
        return values.toList()
    }

    fun frequencyTitle(hz: Double): String =
        "${AudioProfileField.formatNumber(hz)} Hz"

    fun uiGroup(
        base: AudioProfileGroup,
        bandCount: Int,
        verifiedFrequencies: List<Double>? = null
    ): AudioProfileGroup {
        val count = normalizedOrDefault(bandCount)
        val frequencies = validatedFrequencies(
            verifiedFrequencies ?: GmmpEqualizerBandRuntimeMetadata.frequenciesFor(count),
            count
        )
        val fixedFields = base.fields.take(3)
        val sourceBandFields = base.fields.drop(3)
        val bandFields = when {
            frequencies != null -> sourceBandFields.take(count).mapIndexed { index, field ->
                field.copy(
                    title = frequencyTitle(frequencies[index]),
                    subtitle = "GMMP band ${index + 1} center frequency and gain."
                )
            }

            count == MAX_BANDS -> sourceBandFields

            else -> sourceBandFields.take(count).mapIndexed { index, field ->
                field.copy(
                    title = "Band ${index + 1}",
                    subtitle = "GMMP band ${index + 1} gain in $count-band mode."
                )
            }
        }
        return base.copy(
            subtitle = "Override the complete GMMP $count-band equalizer for this output. " +
                "Band changes are remapped automatically when GMMP's band count changes.",
            fields = fixedFields + bandFields
        )
    }
}
