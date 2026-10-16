package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoneSmartOptionsTest {
    @Test
    fun playlistBridgeDefaultsEnabledForUpgradeContinuity() {
        assertTrue(GoneSmartOptions().playlistBridgeEnabled)
    }

    @Test
    fun remixDuplicatePreventionDefaultsOff() {
        assertFalse(GoneSmartOptions().preventRemixDuplicates)
    }
}
