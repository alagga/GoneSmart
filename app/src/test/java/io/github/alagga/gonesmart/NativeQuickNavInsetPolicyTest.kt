package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQuickNavInsetPolicyTest {
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
