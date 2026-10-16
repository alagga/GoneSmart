package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixInitialFillPolicyTest {
    @Test fun initialSizeAlwaysDrivesDynamicFollowerCount() {
        assertEquals(0, TrackMixInitialFillPolicy.nativeInitialRefillCount(1, 1))
        assertEquals(4, TrackMixInitialFillPolicy.nativeInitialRefillCount(5, 1))
        assertEquals(7, TrackMixInitialFillPolicy.nativeInitialRefillCount(8, 1))
    }

    @Test fun fallbackRequestsOnlyTheActualDynamicShortfall() {
        assertEquals(0, TrackMixInitialFillPolicy.nativeInitialRefillCount(5, 5))
        assertEquals(1, TrackMixInitialFillPolicy.nativeInitialRefillCount(5, 4))
        assertEquals(3, TrackMixInitialFillPolicy.nativeInitialRefillCount(8, 5))
    }

    @Test fun commandBoundaryRemainsBounded() {
        assertEquals(1_800L, TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(true))
        assertEquals(800L, TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(false))
    }

    @Test fun allIncidentalRefillsAreHeldUntilSeedIsolationFinishes() {
        listOf("WAIT_PLAY", "PREWARM", "PRIME_NATIVE", "RETARGETING", "ZERO_POOL_BOUNDARY", "ZERO_POOL_RESTORE", "FILLING").forEach { stage ->
            assertEquals(
                TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
                TrackMixInitialFillPolicy.nativeRefillAction(true, false, stage, false)
            )
        }
    }

    @Test fun nativeRefillRunsNormallyAsSoonAsTrackMixReleasesTheHold() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.NORMAL,
            TrackMixInitialFillPolicy.nativeRefillAction(false, false, "FILLING", false)
        )
    }

    @Test fun explicitZeroPoolInitialFillCrossesTheHoldNatively() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.NORMAL,
            TrackMixInitialFillPolicy.nativeRefillAction(true, true, "FILLING", false)
        )
    }

    @Test fun explicitFallbackAllowanceBypassesTheHold() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.NORMAL,
            TrackMixInitialFillPolicy.nativeRefillAction(true, true, "PRIME_NATIVE", false)
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(true, false, "PRIME_NATIVE", false)
        )
    }

    @Test fun all421SongMenuStartsUseThePredecoderCompatibilityPort() {
        assertTrue(
            TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = "menu_gm_context_playlist_details",
                legacyQueue = false
            )
        )
        assertTrue(
            TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = "menu_gm_context_queue",
                legacyQueue = false
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = "menu_gm_context_playlist_details",
                legacyQueue = true
            )
        )
    }

    @Test fun preparedQueueContextRestoresOneSeedBeforeBootstrap() {
        assertTrue(
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = "menu_gm_context_queue",
                legacyQueue = false
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = "menu_gm_context_playlist_details",
                legacyQueue = false
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = "menu_gm_context_queue",
                legacyQueue = true
            )
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

    @Test fun exactInitialQueueRequiresPositionOneAndContiguousConfiguredSize() {
        assertTrue(
            TrackMixInitialFillPolicy.isExactInitialQueue(
                initialSize = 5,
                currentQueuePosition = 1,
                queuePositions = listOf(1, 2, 3, 4, 5)
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.isExactInitialQueue(
                initialSize = 5,
                currentQueuePosition = 6,
                queuePositions = listOf(6, 7215, 7216, 7217, 7218)
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.isExactInitialQueue(
                initialSize = 5,
                currentQueuePosition = 1,
                queuePositions = listOf(1, 7215, 7216, 7217, 7218)
            )
        )
    }
}
