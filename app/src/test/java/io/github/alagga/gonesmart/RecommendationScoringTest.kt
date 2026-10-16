package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationScoringTest {
    @Test
    fun corroborationRewardsIndependentProviders() {
        assertEquals(1.0, RecommendationScoring.corroborationMultiplier(1), 0.0001)
        assertTrue(
            RecommendationScoring.corroborationMultiplier(2) >
                RecommendationScoring.corroborationMultiplier(1)
        )
        assertTrue(
            RecommendationScoring.corroborationMultiplier(3) >
                RecommendationScoring.corroborationMultiplier(2)
        )
    }

    @Test
    fun focusedPlaylistWeighsMoreThanHugePlaylist() {
        assertTrue(
            PlaylistAffinityScoring.playlistWeight(25) >
                PlaylistAffinityScoring.playlistWeight(2500)
        )
    }

    @Test
    fun repeatedCoOccurrenceStrengthensAffinity() {
        val one = PlaylistAffinityScoring.score(listOf(25))
        val two = PlaylistAffinityScoring.score(listOf(25, 30))
        val huge = PlaylistAffinityScoring.score(listOf(2500))
        assertTrue(two > one)
        assertTrue(one > huge)
        assertTrue(two <= 0.95)
    }
}
