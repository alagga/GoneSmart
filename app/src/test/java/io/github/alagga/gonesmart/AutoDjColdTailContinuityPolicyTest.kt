package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjColdTailContinuityPolicyTest {
    @Test fun `native continuity protects the last queue item when pool is cold`() {
        assertTrue(AutoDjColdTailContinuityPolicy.useNativeImmediately(
            poolReady = false,
            upcomingCount = 0,
            nativeFallbackAllowed = true,
            managedTrackMix = false
        ))
    }

    @Test fun `no native interference with matching pool or active buffer`() {
        assertFalse(AutoDjColdTailContinuityPolicy.useNativeImmediately(true, 0, true, false))
        assertFalse(AutoDjColdTailContinuityPolicy.useNativeImmediately(false, 1, true, false))
        assertFalse(AutoDjColdTailContinuityPolicy.useNativeImmediately(false, 2, true, false))
    }

    @Test fun `user-disabled native fallback and managed Track Mix fail closed`() {
        assertFalse(AutoDjColdTailContinuityPolicy.useNativeImmediately(false, 0, false, false))
        assertFalse(AutoDjColdTailContinuityPolicy.useNativeImmediately(false, 0, true, true))
    }
}
