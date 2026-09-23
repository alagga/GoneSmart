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

    @Test fun combinesOnlyNativeGmmpTrackAndAutoDjWordsInAllLanguages() {
        assertEquals(
            "Titel Auto-DJ",
            TrackMixPlan.localizedMenuLabel("de", "Titel", "Auto-DJ")
        )
        assertEquals(
            "Track Auto-DJ",
            TrackMixPlan.localizedMenuLabel("en", "Track", "Auto-DJ")
        )
        assertEquals(
            "Piste DJ automatique",
            TrackMixPlan.localizedMenuLabel("fr", "Piste", "DJ automatique")
        )
        assertEquals(
            "トラック オートDJ",
            TrackMixPlan.localizedMenuLabel("ja", "トラック", "オートDJ")
        )
    }

    @Test fun neverInventsATranslationWhenNativeWordIsMissing() {
        assertEquals(
            "Titel Auto-DJ",
            TrackMixPlan.localizedMenuLabel("de", null, null)
        )
        assertEquals(
            "Track Auto-DJ",
            TrackMixPlan.localizedMenuLabel("en", null, null)
        )
        assertEquals(
            "DJ automatique",
            TrackMixPlan.localizedMenuLabel(
                "fr", null, "DJ automatique"
            )
        )
        assertEquals(
            "Piste Auto-DJ",
            TrackMixPlan.localizedMenuLabel("fr", "Piste", null)
        )
    }

    @Test fun confirmsWithNativeStartedWhenPresentAndSafeFallbackOtherwise() {
        assertEquals(
            "Titel Auto-DJ gestartet",
            TrackMixPlan.localizedStartedMessage(
                "de", "Titel Auto-DJ", "gestartet"
            )
        )
        assertEquals(
            "Track Auto-DJ started",
            TrackMixPlan.localizedStartedMessage(
                "en", "Track Auto-DJ", null
            )
        )
        assertEquals(
            "Piste DJ automatique démarré",
            TrackMixPlan.localizedStartedMessage(
                "fr", "Piste DJ automatique", "démarré"
            )
        )
        assertEquals(
            "トラック オートDJ ✓",
            TrackMixPlan.localizedStartedMessage(
                "ja", "トラック オートDJ", null
            )
        )
    }

    @Test fun nativeIsolationRemovesHistoryAndUpcomingByUniqueEntryId() {
        val entries = listOf(
            TrackMixPlan.NativeQueueEntry(11, 101, 1),
            TrackMixPlan.NativeQueueEntry(12, 102, 2),
            TrackMixPlan.NativeQueueEntry(13, 103, 3),
            TrackMixPlan.NativeQueueEntry(14, 104, 4)
        )
        val plan = TrackMixPlan.planNativeIsolation(
            entries, currentPosition = 3, selectedTrackId = 103
        )
        assertEquals(13L, plan.selectedEntryId)
        assertEquals(3, plan.originalPosition)
        assertEquals(listOf(11L, 12L, 14L), plan.removeEntryIds)
    }

    @Test fun nativeIsolationPreservesExactDuplicateSongOccurrence() {
        val entries = listOf(
            TrackMixPlan.NativeQueueEntry(11, 101, 1),
            TrackMixPlan.NativeQueueEntry(12, 101, 2),
            TrackMixPlan.NativeQueueEntry(13, 101, 3)
        )
        val plan = TrackMixPlan.planNativeIsolation(
            entries, currentPosition = 2, selectedTrackId = 101
        )
        assertEquals(12L, plan.selectedEntryId)
        assertEquals(listOf(11L, 13L), plan.removeEntryIds)
    }

    @Test fun nativeIsolationRejectsWrongTrackOrAmbiguousQueue() {
        val entries = listOf(
            TrackMixPlan.NativeQueueEntry(11, 101, 1),
            TrackMixPlan.NativeQueueEntry(12, 102, 2)
        )
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.planNativeIsolation(entries, 2, 103)
        }
        assertThrows(IllegalStateException::class.java) {
            TrackMixPlan.planNativeIsolation(entries, 5, 102)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.planNativeIsolation(
                listOf(entries[0], entries[0].copy(position = 2)),
                1, 101
            )
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
