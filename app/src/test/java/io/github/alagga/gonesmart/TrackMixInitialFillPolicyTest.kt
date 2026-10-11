package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixInitialFillPolicyTest {
    @Test fun requestsInitialSizeMinusNativeSeedExactlyOnce() {
        assertEquals(4, TrackMixInitialFillPolicy.nativeInitialRefillCount(5, 1))
    }

    @Test fun commandBoundaryRemainsBounded() {
        assertEquals(1_800L, TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(true))
        assertEquals(800L, TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(false))
    }

    @Test fun gmmp421WaitPlayPassesButRetargetWindowHolds() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE,
            TrackMixInitialFillPolicy.nativeRefillAction(true, false, "WAIT_PLAY", false)
        )
        listOf("PREWARM", "PRIME_NATIVE", "RETARGETING", "FILLING").forEach { stage ->
            assertEquals(
                TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
                TrackMixInitialFillPolicy.nativeRefillAction(true, false, stage, false)
            )
        }
    }

    @Test fun onlyOwnedSlotShortfallCrossesPrimeStage() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE,
            TrackMixInitialFillPolicy.nativeRefillAction(true, true, "PRIME_NATIVE", false)
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(true, false, "PRIME_NATIVE", false)
        )
    }

    @Test fun slotRefillRequestsOnlyRealShortfall() {
        assertEquals(0, TrackMixInitialFillPolicy.nativeSlotRefillCount(4, 4, false))
        assertEquals(2, TrackMixInitialFillPolicy.nativeSlotRefillCount(4, 2, false))
        assertEquals(0, TrackMixInitialFillPolicy.nativeSlotRefillCount(4, 2, true))
    }

    @Test fun legacySeedOnlyRefillPolicyRemainsAvailable() {
        assertFalse(TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(true))
        assertTrue(TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(false))
    }
}
