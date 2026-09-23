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

    @Test fun usesActualGmmpGermanAndEnglishTrackNouns() {
        assertEquals(
            "Titel-Mix",
            TrackMixPlan.localizedMenuLabel("de", "Titel", "Auto-DJ")
        )
        assertEquals(
            "Track Mix",
            TrackMixPlan.localizedMenuLabel("en", "Track", "Auto-DJ")
        )
    }

    @Test fun usesFullyNativeNounsForOtherGmmpLanguages() {
        assertEquals(
            "Piste · Auto-DJ",
            TrackMixPlan.localizedMenuLabel("fr", "Piste", "Auto-DJ")
        )
        assertEquals(
            "トラック · オートDJ",
            TrackMixPlan.localizedMenuLabel("ja", "トラック", "オートDJ")
        )
    }

    @Test fun fallsBackSafelyIfGmmpStringIsUnavailable() {
        assertEquals(
            "Titel-Mix",
            TrackMixPlan.localizedMenuLabel("de", null, null)
        )
        assertEquals(
            "Track Mix",
            TrackMixPlan.localizedMenuLabel("en", null, null)
        )
        assertEquals(
            "Auto-DJ",
            TrackMixPlan.localizedMenuLabel("fr", "", "Auto-DJ")
        )
    }

    @Test fun confirmsGermanAndEnglishButUsesNativeLabelsForOtherLanguages() {
        assertEquals(
            "Titel-Mix gestartet",
            TrackMixPlan.localizedStartedMessage("de", "Titel-Mix", null)
        )
        assertEquals(
            "Track Mix started",
            TrackMixPlan.localizedStartedMessage("en", "Track Mix", null)
        )
        assertEquals(
            "Piste · Auto-DJ ✓",
            TrackMixPlan.localizedStartedMessage(
                "fr", "Piste · Auto-DJ", null
            )
        )
        assertEquals(
            "Piste · Auto-DJ · Démarré",
            TrackMixPlan.localizedStartedMessage(
                "fr", "Piste · Auto-DJ", "Démarré"
            )
        )
    }

    @Test fun clearedSeedMayAlreadyHaveNewAutoDjTracks() {
        assertTrue(
            TrackMixPlan.isSeedIsolated(
                55L, listOf(1L, 55L, 2L, 3L), 1,
                listOf(55L, 101L, 102L), 0
            )
        )
        assertTrue(
            TrackMixPlan.isSeedIsolated(
                55L, listOf(1L, 55L, 2L, 3L), 1,
                listOf(55L), 0
            )
        )
    }

    @Test fun neverMistakesOldQueueOrWrongCurrentForNewMix() {
        val old = listOf(1L, 55L, 2L, 3L)
        assertFalse(TrackMixPlan.isSeedIsolated(
            55L, old, 1, old, 1
        ))
        assertFalse(TrackMixPlan.isSeedIsolated(
            55L, old, 1, listOf(55L, 2L, 101L), 0
        ))
        assertFalse(TrackMixPlan.isSeedIsolated(
            55L, old, 1, listOf(55L, 101L), 1
        ))
    }

    @Test fun alwaysInsertsAsThirdMenuItemAfterPlayNext() {
        assertEquals(2, TrackMixPlan.insertionIndex(1, 10))
        assertEquals(2, TrackMixPlan.insertionIndex(1, 7))
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.insertionIndex(9, 7)
        }
    }
}
