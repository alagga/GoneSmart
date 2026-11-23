package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFolderRevealTimeoutPolicyTest {
    @Test fun currentUnreadyAttachedBrowserMayUseBoundedNativeFallback() {
        assertTrue(
            SmartFolderRevealTimeoutPolicy.mayFailOpen(
                scheduledGeneration = 17,
                currentGeneration = 17,
                browserActive = true,
                listAttached = true,
                nativeContentReady = false
            )
        )
    }

    @Test fun oldRootTimeoutCannotRevealNewChildOrReturnedRoot() {
        // Log 2026-11-23 13:21:05-07: initial root attach, child navigation,
        // back to root and old 2.5s timer firing 47 ms after the root render.
        for (current in listOf(18L, 19L, 20L)) {
            assertFalse(
                SmartFolderRevealTimeoutPolicy.mayFailOpen(
                    scheduledGeneration = 17,
                    currentGeneration = current,
                    browserActive = true,
                    listAttached = true,
                    nativeContentReady = false
                )
            )
        }
    }

    @Test fun completedDetachedOrReplacedViewCannotFailOpen() {
        assertFalse(
            SmartFolderRevealTimeoutPolicy.mayFailOpen(
                17, 17, true, true, true
            )
        )
        assertFalse(
            SmartFolderRevealTimeoutPolicy.mayFailOpen(
                17, 17, true, false, false
            )
        )
        assertFalse(
            SmartFolderRevealTimeoutPolicy.mayFailOpen(
                17, 17, false, true, false
            )
        )
    }
}
