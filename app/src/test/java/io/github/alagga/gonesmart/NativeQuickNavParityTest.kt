package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQuickNavParityTest {
    @Test fun matchingOriginalLayoutsNeedNoManualSpacingTune() {
        val a = NativeQuickNavParity.Metrics(36, 192, 58.8f)
        assertTrue(NativeQuickNavParity.compare(a, a).matches())
    }

    @Test fun catchesSmallButVisibleLeftInsetAndChevronDifferences() {
        val expected = NativeQuickNavParity.Metrics(36, 192, 58.8f)
        val actual = NativeQuickNavParity.Metrics(24, 120, 58.8f)
        val result = NativeQuickNavParity.compare(expected, actual)
        assertEquals(-12, result.firstStartPx)
        assertEquals(-72, result.chevronWidthPx)
        assertFalse(result.matches())
    }

    @Test fun firstNativeFolderBeforeArrowExistsCanStillCompareTypography() {
        val expected = NativeQuickNavParity.Metrics(48, null, 58.8f)
        val actual = NativeQuickNavParity.Metrics(49, 192, 58.9f)
        assertTrue(NativeQuickNavParity.compare(expected, actual).matches())
    }
}
