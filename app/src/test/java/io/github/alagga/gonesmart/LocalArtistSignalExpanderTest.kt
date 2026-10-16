package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtistSignalExpanderTest {
    @Test
    fun reusesArtistIndexForSameLibrarySnapshot() {
        val expander = LocalArtistSignalExpander()
        val library = arrayListOf(
            libraryTrack(1L, "Swedish House Mafia", ratingRaw = 8, playCount = 5),
            libraryTrack(2L, "Coldplay,Swedish House Mafia", ratingRaw = 10, playCount = 2),
            libraryTrack(3L, "Axwell", ratingRaw = 6, playCount = 20)
        )
        val artists = listOf(
            ListenBrainzSimilarArtist(
                artistMbid = "artist-1",
                artistName = "Swedish House Mafia",
                score = 1.0,
                referenceMbid = null
            )
        )

        val first = expander.expand(
            artists = artists,
            library = library,
            excludedTrackIds = emptySet()
        )
        assertEquals(listOf(2L, 1L), first.map { it.libraryTrack.track.id })
        assertFalse(expander.lastMetrics!!.indexCacheHit)

        val second = expander.expand(
            artists = artists,
            library = library,
            excludedTrackIds = setOf(2L)
        )
        assertEquals(listOf(1L), second.map { it.libraryTrack.track.id })
        assertTrue(expander.lastMetrics!!.indexCacheHit)
    }

    @Test
    fun newLibrarySnapshotRebuildsArtistIndex() {
        val expander = LocalArtistSignalExpander()
        val firstLibrary = arrayListOf(
            libraryTrack(1L, "BUNT.", ratingRaw = 8, playCount = 4)
        )
        val artists = listOf(
            ListenBrainzSimilarArtist(
                artistMbid = "artist-bunt",
                artistName = "BUNT.",
                score = 0.9,
                referenceMbid = null
            )
        )

        expander.expand(artists, firstLibrary, emptySet())
        assertFalse(expander.lastMetrics!!.indexCacheHit)

        val refreshedLibrary = ArrayList(firstLibrary)
        expander.expand(artists, refreshedLibrary, emptySet())
        assertFalse(expander.lastMetrics!!.indexCacheHit)
        assertEquals(1, expander.lastMetrics!!.signals)
    }

    private fun libraryTrack(
        id: Long,
        artist: String,
        ratingRaw: Int,
        playCount: Int
    ): GmmpLibraryTrack =
        GmmpLibraryTrack(
            track = TrackInfo(
                id = id,
                title = "Track $id",
                artist = artist,
                albumArtist = null,
                path = "/music/$id.mp3"
            ),
            year = 2025,
            ratingRaw = ratingRaw,
            playCount = playCount,
            skipCount = 0,
            dateAddedEpochMs = null,
            dateUpdatedEpochMs = null,
            lastPlayedEpochMs = null
        )
}
