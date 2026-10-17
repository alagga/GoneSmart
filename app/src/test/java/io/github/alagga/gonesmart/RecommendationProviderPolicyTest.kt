package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationProviderPolicyTest {
    @Test
    fun smartModeOwnsStableDefaults() {
        val policy = RecommendationProviderPolicy.from(GoneSmartOptions())

        assertEquals(RecommendationMode.SMART, policy.mode)
        assertTrue(policy.isEnabled(RecommendationProvider.LISTENBRAINZ))
        assertTrue(policy.isEnabled(RecommendationProvider.LASTFM))
        assertTrue(policy.isEnabled(RecommendationProvider.LOCAL_PLAYLIST))
        assertEquals(1.0, policy.weight(RecommendationProvider.LISTENBRAINZ), 0.0001)
        assertEquals(0.95, policy.weight(RecommendationProvider.LASTFM), 0.0001)
        assertEquals(1.0, policy.weight(RecommendationProvider.LOCAL_PLAYLIST), 0.0001)
        assertEquals(PlaylistSizeWeighting.BALANCED, policy.playlistSizeWeighting)
    }

    @Test
    fun customModeRespectsProviderChoices() {
        val policy = RecommendationProviderPolicy.from(
            GoneSmartOptions(
                recommendationMode = RecommendationMode.CUSTOM,
                listenBrainzProviderEnabled = false,
                lastFmProviderEnabled = true,
                localPlaylistProviderEnabled = true,
                lastFmProviderWeight = 1.25,
                localPlaylistProviderWeight = 0.70,
                playlistSizeWeighting = PlaylistSizeWeighting.OFF
            )
        )

        assertFalse(policy.isEnabled(RecommendationProvider.LISTENBRAINZ))
        assertTrue(policy.isEnabled(RecommendationProvider.LASTFM))
        assertTrue(policy.isEnabled(RecommendationProvider.LOCAL_PLAYLIST))
        assertEquals(1.25, policy.weight(RecommendationProvider.LASTFM), 0.0001)
        assertEquals(0.70, policy.weight(RecommendationProvider.LOCAL_PLAYLIST), 0.0001)
        assertEquals(PlaylistSizeWeighting.OFF, policy.playlistSizeWeighting)
    }

    @Test
    fun customWeightsAreClamped() {
        val policy = RecommendationProviderPolicy.from(
            GoneSmartOptions(
                recommendationMode = RecommendationMode.CUSTOM,
                listenBrainzProviderWeight = 9.0,
                lastFmProviderWeight = 0.0
            )
        )

        assertEquals(1.50, policy.weight(RecommendationProvider.LISTENBRAINZ), 0.0001)
        assertEquals(0.25, policy.weight(RecommendationProvider.LASTFM), 0.0001)
    }
}
