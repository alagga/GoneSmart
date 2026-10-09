package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixInitialFillPolicyTest {
    @Test fun requestsInitialSizeMinusNativeSeedExactlyOnce() {
        assertEquals(
            4,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 1
            )
        )
    }

    @Test fun doesNotRequestAnythingWhenSeedAlreadyMeetsTarget() {
        assertEquals(
            0,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 5
            )
        )
        assertEquals(
            0,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 7
            )
        )
    }

    @Test fun clampsInvalidSettingsToSafeCounts() {
        assertEquals(
            1,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 0,
                seedQueueSize = 0
            )
        )
        assertEquals(
            5,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = -3
            )
        )
    }

    @Test fun keepsLegacyCommandBoundaryButRefills421BeforeNextSourceLookup() {
        assertEquals(
            1_800L,
            TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(true)
        )
        assertEquals(
            90L,
            TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(false)
        )
    }

    @Test fun gmmp421WaitPlayRefillPassesThroughButMutationStagesHold() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "WAIT_PLAY",
                legacyQueue = false
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "CLEARING",
                legacyQueue = false
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "FILLING",
                legacyQueue = false
            )
        )
    }

    @Test fun legacyWaitPlayHoldAndExplicit421RefillStayUnchanged() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "WAIT_PLAY",
                legacyQueue = true
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.NORMAL,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = true,
                stage = "FILLING",
                legacyQueue = false
            )
        )
    }

    @Test fun gmmp421BootstrapsOnlyOneContinuityTrackBeforeSmartRemainder() {
        val totalMissing =
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 1
            )
        assertEquals(
            1,
            TrackMixInitialFillPolicy.continuityBootstrapRefillCount(
                totalMissing = totalMissing,
                legacyQueue = false
            )
        )
        assertEquals(
            4,
            TrackMixInitialFillPolicy.continuityBootstrapRefillCount(
                totalMissing = totalMissing,
                legacyQueue = true
            )
        )
    }

    @Test fun isolatedTrackMixSeedNeverWaitsForSmartPool() {
        assertFalse(
            TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(
                explicitInitialRefill = true
            )
        )
        assertTrue(
            TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(
                explicitInitialRefill = false
            )
        )
    }
}
