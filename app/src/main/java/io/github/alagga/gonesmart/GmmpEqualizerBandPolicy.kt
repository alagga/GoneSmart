package io.github.alagga.gonesmart

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Keeps Equalizer profiles stable while GMMP changes its configured band count.
 *
 * GoneSmart persists the existing ten-point curve as its canonical shape. A live
 * GMMP Equalizer with 2..10 bands is a deterministic projection of that curve.
 * Linear interpolation in canonical band-index space is equivalent to a simple
 * interpolation over the logarithmically spaced ten-band frequency axis and is
 * deliberately preferred over inventing GMMP's undocumented intermediate
 * center-frequency formula.
 */
object GmmpEqualizerBandPolicy {
    const val MIN_BANDS = 2
    const val MAX_BANDS = 10
    const val BAND_COUNT_FIELD_ID = "eq.band_count"

    fun isSupported(count: Int): Boolean = count in MIN_BANDS..MAX_BANDS

    fun normalizedOrDefault(count: Int?, defaultCount: Int = MAX_BANDS): Int =
        count?.takeIf(::isSupported) ?: defaultCount.coerceIn(MIN_BANDS, MAX_BANDS)

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

    fun uiGroup(base: AudioProfileGroup, bandCount: Int): AudioProfileGroup {
        val count = normalizedOrDefault(bandCount)
        val fixedFields = base.fields.take(3)
        val sourceBandFields = base.fields.drop(3)
        val bandFields = if (count == MAX_BANDS) {
            sourceBandFields
        } else {
            sourceBandFields.take(count).mapIndexed { index, field ->
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
