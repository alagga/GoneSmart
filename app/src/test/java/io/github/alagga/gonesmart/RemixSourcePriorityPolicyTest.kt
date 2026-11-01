package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemixSourcePriorityPolicyTest {
    private val normalizer = TrackMetadataNormalizer()

    @Test fun `Re-Style bootleg prioritizes its producer`() {
        val metadata = normalizer.normalize(TrackInfo(
            id = 19189L,
            artist = "Armin van Buuren",
            title = "In And Out Of Love (Re-Style Bootleg)",
            albumArtist = null,
            path = null
        ))
        assertEquals(listOf("Re-Style"), RemixSourcePriorityPolicy.remixArtists(
            metadata, normalizer::comparisonKey
        ))
        assertFalse(RemixSourcePriorityPolicy.allowUnverifiedOriginalArtistFallback(true))
        assertEquals(0.38,
            RemixSourcePriorityPolicy.originalIdentityConfidence(1.0, true), 0.001)
    }

    @Test fun `ordinary tracks retain original artist identity strength`() {
        assertTrue(RemixSourcePriorityPolicy.allowUnverifiedOriginalArtistFallback(false))
        assertEquals(0.75,
            RemixSourcePriorityPolicy.originalIdentityConfidence(0.75, false), 0.001)
    }
}
