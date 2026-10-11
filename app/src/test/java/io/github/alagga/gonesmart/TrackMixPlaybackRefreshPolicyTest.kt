package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixPlaybackRefreshPolicyTest {
    @Test
    fun refreshAllowedOnlyForSameSelectedCurrentWithUpcomingRows() {
        assertTrue(
            TrackMixPlaybackRefreshPolicy.canRefresh(
                selectedTrackId = 42L,
                currentTrackId = 42L,
                queueSize = 5,
                currentPosition = 1
            )
        )
    }

    @Test
    fun changedCurrentOrMissingUpcomingFailsClosed() {
        assertFalse(
            TrackMixPlaybackRefreshPolicy.canRefresh(
                selectedTrackId = 42L,
                currentTrackId = 43L,
                queueSize = 5,
                currentPosition = 1
            )
        )
        assertFalse(
            TrackMixPlaybackRefreshPolicy.canRefresh(
                selectedTrackId = 42L,
                currentTrackId = 42L,
                queueSize = 1,
                currentPosition = 1
            )
        )
    }

    @Test
    fun unverifiedPositionFailsClosed() {
        assertFalse(
            TrackMixPlaybackRefreshPolicy.canRefresh(
                selectedTrackId = 42L,
                currentTrackId = 42L,
                queueSize = 5,
                currentPosition = null
            )
        )
    }
}
