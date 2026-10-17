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
    fun remixAndRadioEditAreDifferentVersionFamiliesButSameSongIdentity() {
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

        val radioIdentity = builder.duplicateIdentity(radio)
        val remixIdentity = builder.duplicateIdentity(remix)

        assertFalse(
            radioIdentity.versionFamilyKeys
                .intersect(remixIdentity.versionFamilyKeys)
                .isNotEmpty()
        )
        assertTrue(
            radioIdentity.songIdentityKeys
                .intersect(remixIdentity.songIdentityKeys)
                .isNotEmpty()
        )
        assertFalse(radioIdentity.isRemix)
        assertTrue(remixIdentity.isRemix)
    }

    @Test
    fun radioAndExtendedShareBaseVersionFamily() {
        val radio = track(1L, "Avicii", "Levels (Radio Edit)")
        val extended = track(2L, "Avicii", "Levels (Extended Mix)")

        assertTrue(
            builder.familyKeys(radio)
                .intersect(builder.familyKeys(extended))
                .isNotEmpty()
        )
    }

    @Test
    fun sameTitleByDifferentArtistsRemainsDistinct() {
        val avicii = track(1L, "Avicii", "Levels")
        val other = track(2L, "Other Artist", "Levels")

        assertFalse(
            builder.duplicateIdentity(avicii).songIdentityKeys
                .intersect(builder.duplicateIdentity(other).songIdentityKeys)
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
