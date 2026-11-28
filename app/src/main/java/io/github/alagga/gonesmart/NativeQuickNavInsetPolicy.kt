package io.github.alagga.gonesmart

import kotlin.math.abs

/**
 * Match the real native metadata holder's measured content X. Reuse XML;
 * never introduce guessed layout margins or calibrate a scrolled viewport.
 */
internal object NativeQuickNavInsetPolicy {
    data class DrawStep(val draw: Boolean, val keepPending: Boolean)

    /**
     * Bound work to actual pre-draw events. Only one measured padding
     * correction may defer a frame; missing/scrolled geometry never blocks
     * the native window. A subsequent measurement is diagnostic only.
     */
    class DrawAlignment(private val maxAttempts: Int = 12) {
        private var attempts = 0
        private var corrected = false

        val mayCorrect: Boolean get() = !corrected

        fun onMeasurement(
            measurementAvailable: Boolean,
            paddingApplied: Boolean
        ): DrawStep {
            attempts++
            if (measurementAvailable && paddingApplied && !corrected) {
                corrected = true
                return DrawStep(draw = false, keepPending = attempts < maxAttempts)
            }
            return DrawStep(
                draw = true,
                keepPending = !measurementAvailable && attempts < maxAttempts
            )
        }
    }

    fun correctedPadding(
        currentPadding: Int,
        measuredTextStart: Int,
        expectedTextStart: Int,
        maxCorrection: Int
    ): Int? {
        val delta = expectedTextStart - measuredTextStart
        if (abs(delta) <= 1 || abs(delta) > maxCorrection) return null
        val result = currentPadding + delta
        return result.takeIf { it in 0..maxCorrection }
    }
}
