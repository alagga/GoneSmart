package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixQueueSettlingPolicyTest {
    @Test
    fun smartPlaylistTrackMenuGetsCompletionGuard() {
        assertEquals(
            TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
            TrackMixQueueSettlingPolicy.requiredCompletionGuardMs(
                "menu_gm_context_track"
            )
        )
        assertEquals(
            0L,
            TrackMixQueueSettlingPolicy.requiredCompletionGuardMs(
                "menu_gm_context_playlist_details"
            )
        )
    }

    @Test
    fun growingNativeQueueIsNotSettledEvenWhenCurrentTrackIsUnchanged() {
        assertFalse(
            TrackMixQueueSettlingPolicy.sameQueue(
                previousTrackIds = listOf(10L, 11L),
                previousEntryIds = listOf(100L, 101L),
                previousCurrentIndex = 0,
                currentTrackIds = listOf(10L, 11L, 12L),
                currentEntryIds = listOf(100L, 101L, 102L),
                currentCurrentIndex = 0
            )
        )
    }

    @Test
    fun quietIntermediateSmartPlaylistQueueStillNeedsCompletionGuard() {
        val same = TrackMixQueueSettlingPolicy.sameQueue(
            previousTrackIds = listOf(10L, 11L),
            previousEntryIds = listOf(100L, 101L),
            previousCurrentIndex = 0,
            currentTrackIds = listOf(10L, 11L),
            currentEntryIds = listOf(100L, 101L),
            currentCurrentIndex = 0
        )

        assertFalse(
            TrackMixQueueSettlingPolicy.isSettled(
                sameQueue = same,
                stableForMs = 812L,
                sinceDetectionMs = 812L,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS
            )
        )
        assertTrue(
            TrackMixQueueSettlingPolicy.isSettled(
                sameQueue = same,
                stableForMs = TrackMixQueueSettlingPolicy.QUIET_WINDOW_MS,
                sinceDetectionMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS
            )
        )
    }

    @Test
    fun ordinaryPlaylistNeedsOnlyQuietWindow() {
        assertTrue(
            TrackMixQueueSettlingPolicy.isSettled(
                sameQueue = true,
                stableForMs = TrackMixQueueSettlingPolicy.QUIET_WINDOW_MS,
                sinceDetectionMs = TrackMixQueueSettlingPolicy.QUIET_WINDOW_MS,
                requiredGuardMs = 0L
            )
        )
    }

    @Test
    fun selectedTargetMaySurviveAListRebuildAfterCurrentDrifts() {
        assertTrue(
            TrackMixQueueSettlingPolicy.selectedTargetStillUsable(
                selectedTrackOccurrences = 1,
                currentStillSelected = false,
                detectedQueueSize = 2,
                currentQueueSize = 7213
            )
        )
        assertFalse(
            TrackMixQueueSettlingPolicy.selectedTargetStillUsable(
                selectedTrackOccurrences = 1,
                currentStillSelected = false,
                detectedQueueSize = 2,
                currentQueueSize = 2
            )
        )
        assertFalse(
            TrackMixQueueSettlingPolicy.selectedTargetStillUsable(
                selectedTrackOccurrences = 2,
                currentStillSelected = false,
                detectedQueueSize = 2,
                currentQueueSize = 7213
            )
        )
    }

    @Test
    fun vanishedProvisionalSmartPlaylistCurrentMayBeRetargetedInsideGuard() {
        assertTrue(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_track",
                sinceDetectionMs = 400L,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 0,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
    }

    @Test
    fun retargetingFailsClosedOutsideSmartPlaylistSettlingWindow() {
        assertFalse(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_track",
                sinceDetectionMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 0,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
        assertFalse(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_queue",
                sinceDetectionMs = 400L,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 0,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
        assertFalse(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_track",
                sinceDetectionMs = 400L,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 1,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
    }
}
