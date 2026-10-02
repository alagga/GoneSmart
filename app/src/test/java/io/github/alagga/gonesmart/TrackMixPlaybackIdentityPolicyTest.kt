package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixPlaybackIdentityPolicyTest {
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
