package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQuickNavInsetPolicyTest {
    @Test fun measuredCorrectionDefersOnlyTheObsoleteFrame() {
        val alignment = NativeQuickNavInsetPolicy.DrawAlignment()
        assertEquals(true, alignment.mayCorrect)
        assertEquals(
            NativeQuickNavInsetPolicy.DrawStep(false, true),
            alignment.onMeasurement(true, true)
        )
        assertEquals(false, alignment.mayCorrect)
        assertEquals(
            NativeQuickNavInsetPolicy.DrawStep(true, false),
            alignment.onMeasurement(true, false)
        )
    }

    @Test fun unavailableHolderDoesNotBlockDrawAndRetriesAreBounded() {
        val alignment = NativeQuickNavInsetPolicy.DrawAlignment(maxAttempts = 2)
        assertEquals(
            NativeQuickNavInsetPolicy.DrawStep(true, true),
            alignment.onMeasurement(false, false)
        )
        assertEquals(
            NativeQuickNavInsetPolicy.DrawStep(true, false),
            alignment.onMeasurement(false, false)
        )
    }

    @Test fun alreadyAlignedNativeGeometryNeedsNoAdditionalFrame() {
        val alignment = NativeQuickNavInsetPolicy.DrawAlignment()
        assertEquals(
            NativeQuickNavInsetPolicy.DrawStep(true, false),
            alignment.onMeasurement(true, false)
        )
    }

    @Test fun lastAllowedCorrectionStillPreventsDrawingObsoleteGeometry() {
        val alignment = NativeQuickNavInsetPolicy.DrawAlignment(maxAttempts = 1)
        assertEquals(
            NativeQuickNavInsetPolicy.DrawStep(false, false),
            alignment.onMeasurement(true, true)
        )
    }

    @Test fun matchesMeasuredRealNativeStartWithoutGuessing() {
        assertEquals(
            18,
            NativeQuickNavInsetPolicy.correctedPadding(0, 30, 48, 72)
        )
    }

    @Test fun tinyPixelDifferenceIsIgnored() {
        assertNull(
            NativeQuickNavInsetPolicy.correctedPadding(0, 47, 48, 72)
        )
    }

    @Test fun preventsHugeCorrectionCausedByScrolling() {
        assertNull(
            NativeQuickNavInsetPolicy.correctedPadding(0, -158, 0, 72)
        )
    }

    @Test fun preventsOvershootingNativeViewport() {
        assertNull(
            NativeQuickNavInsetPolicy.correctedPadding(0, 80, 40, 72)
        )
    }

    @Test fun canReducePreviouslyAppliedPaddingAfterThemeChange() {
        assertEquals(
            12,
            NativeQuickNavInsetPolicy.correctedPadding(20, 66, 58, 72)
        )
    }
}
