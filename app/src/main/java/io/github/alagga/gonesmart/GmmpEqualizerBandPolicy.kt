package io.github.alagga.gonesmart

import kotlin.math.ln

/**
 * Keeps the persisted profile format stable at ten points while following
 * GMMP's live selectable 2..10-band Equalizer layout.
 *
 * The center-frequency layouts below were verified against GMMP 4.2.1's own
 * Equalizer UI for every supported band count. Curve migration therefore uses
 * frequency space instead of assuming evenly spaced band indices.
 */
object GmmpEqualizerBandPolicy {
    const val MIN_BANDS = 2
    const val MAX_BANDS = 10
    const val BAND_COUNT_FIELD_ID = "eq.band_count"

    private const val MIN_PLAUSIBLE_FREQUENCY_HZ = 15.0
    private const val MAX_PLAUSIBLE_FREQUENCY_HZ = 100_000.0

    private val gmmp421FrequenciesHz: Map<Int, List<Double>> = mapOf(
        2 to listOf(350.0, 15_000.0),
        3 to listOf(150.0, 1_000.0, 15_000.0),
        4 to listOf(70.0, 400.0, 2_000.0, 15_000.0),
        5 to listOf(64.0, 256.0, 1_000.0, 4_000.0, 16_000.0),
        6 to listOf(45.0, 150.0, 450.0, 1_000.0, 4_000.0, 15_000.0),
        7 to listOf(40.0, 100.0, 300.0, 750.0, 2_000.0, 5_000.0, 15_000.0),
        8 to listOf(35.0, 85.0, 200.0, 500.0, 1_000.0, 3_000.0, 6_000.0, 15_000.0),
        9 to listOf(35.0, 70.0, 150.0, 350.0, 700.0, 1_000.0, 3_000.0, 7_000.0, 15_000.0),
        10 to listOf(32.0, 64.0, 128.0, 256.0, 512.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
    )

    fun isSupported(count: Int): Boolean = count in MIN_BANDS..MAX_BANDS

    fun normalizedOrDefault(count: Int?, defaultCount: Int = MAX_BANDS): Int =
        count?.takeIf(::isSupported) ?: defaultCount.coerceIn(MIN_BANDS, MAX_BANDS)

    fun frequenciesFor(count: Int): List<Double>? =
        gmmp421FrequenciesHz[count]?.toList()

    /**
     * Resample an ordered graphic-EQ curve using GMMP's actual center
     * frequencies. Gain is interpolated linearly in log-frequency space,
     * which matches how a graphic EQ's frequency axis is laid out. Targets
     * outside the source layout retain the nearest edge gain.
     *
     * Every returned point is normalized through the real GoneSmart/GMMP band
     * field contract. This keeps projected values on the 0.1 dB grid expected
     * by the Material slider instead of leaking interpolation-only fractions
     * such as 9.045 dB into a stepped control.
     */
    fun project(values: List<Double>, targetCount: Int): List<Double>? {
        if (!isSupported(values.size) || !isSupported(targetCount)) return null
        if (values.any { !it.isFinite() }) return null

        if (values.size == targetCount) {
            return values.map { value -> normalizeProjectedGain(value) ?: return null }
        }

        val sourceFrequencies = frequenciesFor(values.size) ?: return null
        val targetFrequencies = frequenciesFor(targetCount) ?: return null
        return targetFrequencies.map { target ->
            val raw = interpolateAtFrequency(sourceFrequencies, values, target)
            normalizeProjectedGain(raw) ?: return null
        }
    }

    private fun normalizeProjectedGain(value: Double): Double? {
        val fieldId = GoneSmartAudioProfiles.equalizerBandFieldIds.firstOrNull() ?: return null
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return null
        return field.normalize(AudioProfileField.formatNumber(value))?.toDoubleOrNull()
    }

    private fun interpolateAtFrequency(
        sourceFrequencies: List<Double>,
        sourceValues: List<Double>,
        targetFrequency: Double
    ): Double {
        if (targetFrequency <= sourceFrequencies.first()) return sourceValues.first()
        if (targetFrequency >= sourceFrequencies.last()) return sourceValues.last()

        val exact = sourceFrequencies.indexOf(targetFrequency)
        if (exact >= 0) return sourceValues[exact]

        val upper = sourceFrequencies.indexOfFirst { it > targetFrequency }
        if (upper <= 0) return sourceValues.first()
        val lower = upper - 1
        val lowerFrequency = sourceFrequencies[lower]
        val upperFrequency = sourceFrequencies[upper]
        val fraction =
            (ln(targetFrequency) - ln(lowerFrequency)) /
                (ln(upperFrequency) - ln(lowerFrequency))
        return sourceValues[lower] + (sourceValues[upper] - sourceValues[lower]) * fraction
    }

    /**
     * Accept only a complete, ordered audio-frequency vector. Kept as a small
     * validation helper for diagnostics/tests; user-facing labels use the
     * GMMP-4.2.1 layouts verified directly from the player UI.
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
        if (hz >= 1_000.0) {
            "${AudioProfileField.formatNumber(hz / 1_000.0)} kHz"
        } else {
            "${AudioProfileField.formatNumber(hz)} Hz"
        }

    fun uiGroup(
        base: AudioProfileGroup,
        bandCount: Int,
        verifiedFrequencies: List<Double>? = null
    ): AudioProfileGroup {
        val count = normalizedOrDefault(bandCount)
        val frequencies = validatedFrequencies(verifiedFrequencies, count)
            ?: frequenciesFor(count)
        val fixedFields = base.fields.take(3)
        val sourceBandFields = base.fields.drop(3)
        val bandFields = sourceBandFields.take(count).mapIndexed { index, field ->
            field.copy(
                title = frequencies?.getOrNull(index)?.let(::frequencyTitle) ?: "Band ${index + 1}",
                subtitle = "GMMP band ${index + 1} center frequency and gain."
            )
        }
        return base.copy(
            subtitle = "Override the complete GMMP $count-band equalizer for this output. " +
                "Band changes are remapped automatically using GMMP's center frequencies.",
            fields = fixedFields + bandFields
        )
    }
}
