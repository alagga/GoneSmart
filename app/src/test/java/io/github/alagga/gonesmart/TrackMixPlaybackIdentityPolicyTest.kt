package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixPlaybackIdentityPolicyTest {
    @Test fun playbackChangeIgnoresQueueIndexOnlyMotion() {
        val before = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 100L,
            trackId = 20L,
            currentIndex = 50
        )
        assertFalse(
            TrackMixPlaybackIdentityPolicy.playbackChanged(
                before,
                before.copy(currentIndex = 49)
            )
        )
        assertTrue(
            TrackMixPlaybackIdentityPolicy.playbackChanged(
                before,
                before.copy(trackId = 21L, queueEntryId = 101L)
            )
        )
        assertTrue(
            TrackMixPlaybackIdentityPolicy.playbackChanged(
                before,
                before.copy(queueEntryId = 101L)
            )
        )
    }

    @Test fun sameCurrentEntryStaysStableWhileQueueAroundItMayChange() {
        val first = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 9001L,
            trackId = 13739L,
            currentIndex = 48
        )
        val later = first.copy(currentIndex = 47)

        assertTrue(
            TrackMixPlaybackIdentityPolicy.sameCurrent(first, later)
        )
        assertTrue(
            TrackMixPlaybackIdentityPolicy.changed(first, later)
        )
    }

    @Test fun differentQueueEntryIsAPlaybackChangeEvenForDuplicateTrack() {
        val before = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 10L,
            trackId = 42L,
            currentIndex = 4
        )
        val current = before.copy(queueEntryId = 11L)

        assertFalse(
            TrackMixPlaybackIdentityPolicy.sameCurrent(before, current)
        )
        assertTrue(
            TrackMixPlaybackIdentityPolicy.changed(before, current)
        )
    }

    @Test fun acceptedQueuePlayMayReuseAlreadyCurrentEntryOnlyWithNativeSignal() {
        val current = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 100L,
            trackId = 20L,
            currentIndex = 50
        )
        assertFalse(
            TrackMixPlaybackIdentityPolicy.acceptSameCurrentQueuePlay(
                source = "menu_gm_context_queue",
                nativePlayAccepted = true,
                nativePlaySignal = false,
                before = current,
                current = current,
                stableMs = 400L,
                actionAgeMs = 1_600L
            )
        )
        assertTrue(
            TrackMixPlaybackIdentityPolicy.acceptSameCurrentQueuePlay(
                source = "menu_gm_context_queue",
                nativePlayAccepted = true,
                nativePlaySignal = true,
                before = current,
                current = current,
                stableMs = 400L,
                actionAgeMs = 1_600L
            )
        )
        assertFalse(
            TrackMixPlaybackIdentityPolicy.acceptSameCurrentQueuePlay(
                source = "menu_gm_context_track",
                nativePlayAccepted = true,
                nativePlaySignal = true,
                before = current,
                current = current,
                stableMs = 400L,
                actionAgeMs = 1_600L
            )
        )
        assertFalse(
            TrackMixPlaybackIdentityPolicy.acceptSameCurrentQueuePlay(
                source = "menu_gm_context_queue",
                nativePlayAccepted = true,
                nativePlaySignal = true,
                before = current,
                current = current,
                stableMs = 400L,
                actionAgeMs = 500L
            )
        )
    }

    @Test fun mismatchingCurrentChangeIsTransitionOnlyWhenPrePlayTargetIsKnown() {
        val current = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 700L,
            trackId = 19133L,
            currentIndex = 5
        )

        assertEquals(
            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TRANSITION_ONLY,
            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(
                expectedTrackId = 19118L,
                hasPlaybackEvidence = true,
                current = current
            )
        )
    }

    @Test fun exactPrePlayTargetMayBecomeInitialTargetAfterTransition() {
        val current = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 701L,
            trackId = 19118L,
            currentIndex = 0
        )

        assertEquals(
            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TARGET,
            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(
                expectedTrackId = 19118L,
                hasPlaybackEvidence = true,
                current = current
            )
        )
    }

    @Test fun initialCandidateWithoutPlaybackEvidenceIsIgnored() {
        val current = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 701L,
            trackId = 19118L,
            currentIndex = 0
        )

        assertEquals(
            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.IGNORE,
            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(
                expectedTrackId = 19118L,
                hasPlaybackEvidence = false,
                current = current
            )
        )
    }

    @Test fun legacyFlowWithoutPrePlayTargetKeepsPlaybackChangeBehavior() {
        val current = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = 701L,
            trackId = 42L,
            currentIndex = 0
        )

        assertEquals(
            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TARGET,
            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(
                expectedTrackId = null,
                hasPlaybackEvidence = true,
                current = current
            )
        )
    }

    @Test fun legacyPathFallsBackToTrackIdentityWithoutQueueEntryId() {
        val first = TrackMixPlaybackIdentityPolicy.Identity(
            queueEntryId = null,
            trackId = 42L,
            currentIndex = 3
        )
        val later = first.copy(currentIndex = 7)

        assertTrue(
            TrackMixPlaybackIdentityPolicy.sameCurrent(first, later)
        )
    }
}
