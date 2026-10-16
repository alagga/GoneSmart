package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFamilyKeyBuilderTest {
    private val builder = TrackFamilyKeyBuilder(
        metadataNormalizer = TrackMetadataNormalizer(),
        trackInputResolver = TrackInputResolver()
    )

    @Test
    fun remixAndRadioEditShareDuplicateFamily() {
        val radio = track(
            id = 1L,
            artist = "Avicii",
            title = "Levels (Radio Edit)"
        )
        val remix = track(
            id = 2L,
            artist = "Avicii",
            title = "Levels (Skrillex Remix)"
        )

        assertTrue(
            builder.familyKeys(radio)
                .intersect(builder.familyKeys(remix))
                .isNotEmpty()
        )
    }

    @Test
    fun sameTitleByDifferentArtistsRemainsDistinct() {
        val avicii = track(1L, "Avicii", "Levels")
        val other = track(2L, "Other Artist", "Levels")

        assertFalse(
            builder.familyKeys(avicii)
                .intersect(builder.familyKeys(other))
                .isNotEmpty()
        )
    }

    private fun track(
        id: Long,
        artist: String,
        title: String
    ) = TrackInfo(
        id = id,
        title = title,
        artist = artist,
        albumArtist = null,
        path = "/music/$id.mp3"
    )
}
