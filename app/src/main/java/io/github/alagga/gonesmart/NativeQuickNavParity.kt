package io.github.alagga.gonesmart

import kotlin.math.abs

/**
 * Compare two measured REAL native XML trees, not inferred screenshot dp.
 * Nullable chevrons are ignored until GMMP itself shows a two-folder path.
 */
internal object NativeQuickNavParity {
    data class Metrics(
        val firstTextStartPx: Int,
        val separatorWidthPx: Int?,
        val textSizePx: Float
    )
    data class Difference(
        val firstStartPx: Int,
        val chevronWidthPx: Int?,
        val textSizePx: Float
    ) {
        fun matches(
            pixelTolerance: Int = 2,
            textPixelTolerance: Float = 1f
        ): Boolean = abs(firstStartPx) <= pixelTolerance &&
            (chevronWidthPx == null || abs(chevronWidthPx) <= pixelTolerance) &&
            abs(textSizePx) <= textPixelTolerance
    }
    fun compare(expected: Metrics, actual: Metrics): Difference {
        val arrowDiff = if (expected.separatorWidthPx == null ||
            actual.separatorWidthPx == null
        ) null else actual.separatorWidthPx - expected.separatorWidthPx
        return Difference(
            firstStartPx = actual.firstTextStartPx - expected.firstTextStartPx,
            chevronWidthPx = arrowDiff,
            textSizePx = actual.textSizePx - expected.textSizePx
        )
    }
}
