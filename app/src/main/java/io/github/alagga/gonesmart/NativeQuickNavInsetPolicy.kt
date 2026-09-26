package io.github.alagga.gonesmart

import kotlin.math.abs

/**
 * Match the real native metadata holder's measured content X. Reuse XML;
 * never introduce guessed layout margins or calibrate a scrolled viewport.
 */
internal object NativeQuickNavInsetPolicy {
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
