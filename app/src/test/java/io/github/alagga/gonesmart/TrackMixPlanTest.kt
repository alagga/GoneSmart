package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixPlanTest {
    @Test fun selectedSongCountsTowardNativeInitialQueueSize() {
        assertEquals(4, TrackMixPlan.additionalTracksNeeded(5, 1))
        assertFalse(TrackMixPlan.hasEnoughTracks(5, 1))
        assertTrue(TrackMixPlan.hasEnoughTracks(5, 5))
    }

    @Test fun preservesExistingNativeRefillWithoutOverfilling() {
        assertEquals(0, TrackMixPlan.additionalTracksNeeded(5, 7))
        assertEquals(2, TrackMixPlan.additionalTracksNeeded(5, 3))
    }

    @Test fun oneTrackInitialQueueNeedsNoExtraSong() {
        assertEquals(0, TrackMixPlan.additionalTracksNeeded(1, 1))
        assertTrue(TrackMixPlan.hasEnoughTracks(1, 1))
    }

    @Test fun rejectsBrokenNativeSettingsAndSnapshots() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.additionalTracksNeeded(0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.additionalTracksNeeded(5, -1)
        }
    }

    @Test fun alwaysInsertsAsThirdMenuItemAfterPlayNext() {
        assertEquals(2, TrackMixPlan.insertionIndex(1, 10))
        assertEquals(2, TrackMixPlan.insertionIndex(1, 7))
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.insertionIndex(9, 7)
        }
    }
}
