package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemixSourcePriorityPolicyTest {
    private val normalizer = TrackMetadataNormalizer()

    @Test fun `Re-Style bootleg prioritizes its producer`() {
        val seed = RecommendationSeed(track = TrackInfo(
            id = 19189L,
            artist = "Armin van Buuren",
            title = "In And Out Of Love (Re-Style Bootleg)",
            albumArtist = null,
            path = null
        ), type = SeedType.CURRENT, recency = 0)
        val metadata = normalizer.normalize(seed.track)
        assertEquals(listOf("Re-Style"), RemixSourcePriorityPolicy.remixArtists(
            metadata, normalizer::comparisonKey
        ))
        assertEquals("Re-Style", RemixSourcePriorityPolicy.localFallbackSeed(
            seed, metadata, normalizer::comparisonKey
        ).track.artist)
        assertEquals("Armin van Buuren", seed.track.artist)
        assertFalse(RemixSourcePriorityPolicy.allowUnverifiedOriginalArtistFallback(true))
        assertEquals(0.38,
            RemixSourcePriorityPolicy.originalIdentityConfidence(1.0, true), 0.001)
    }

    @Test fun `ordinary tracks retain original artist identity strength`() {
        val seed = RecommendationSeed(
            track = TrackInfo(2L, "Levels", "Avicii", null, null),
            type = SeedType.CURRENT,
            recency = 0
        )
        assertEquals(seed, RemixSourcePriorityPolicy.localFallbackSeed(
            seed, normalizer.normalize(seed.track), normalizer::comparisonKey
        ))
        assertTrue(RemixSourcePriorityPolicy.allowUnverifiedOriginalArtistFallback(false))
        assertEquals(0.75,
            RemixSourcePriorityPolicy.originalIdentityConfidence(0.75, false), 0.001)
    }
}
