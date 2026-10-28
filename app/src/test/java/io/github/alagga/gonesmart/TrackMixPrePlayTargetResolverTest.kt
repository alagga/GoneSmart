package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixPrePlayTargetResolverTest {
    private data class NativeTrack(
        val a: Long,
        val b: String,
        val c: String,
        val d: String
    )
    private data class Listener(val selected: Any, val outer: Any? = null)
    private data class Outer(val rows: List<Any>)
    private class Cycle(var next: Any? = null, val selected: Any? = null)

    private val levels = TrackInfo(
        3737L,
        "Levels",
        "Avicii",
        "Avicii",
        "/music/Avicii/Levels.mp3"
    )
    private val fade = TrackInfo(
        3897L,
        "Fade Into Darkness",
        "Avicii",
        "Avicii",
        "/music/Avicii/Fade Into Darkness.mp3"
    )

    @Test fun resolvesCapturedSelectedModelAheadOfDeeperAdapterRows() {
        val selected = NativeTrack(3737L, "Levels", "Avicii", levels.path!!)
        val outer = Outer(listOf(NativeTrack(3897L, "Fade Into Darkness", "Avicii", fade.path!!)))
        val result = TrackMixPrePlayTargetResolver().resolve(
            listOf(Listener(selected, outer)),
            listOf(levels, fade)
        )
        assertEquals(3737L, result?.track?.id)
        assertTrue(result?.evidence?.contains("path") == true)
    }

    @Test fun bareNumericCoincidenceNeverResolves() {
        data class Bare(val value: Long)
        assertNull(
            TrackMixPrePlayTargetResolver().resolve(
                listOf(Bare(3737L)),
                listOf(levels, fade)
            )
        )
    }

    @Test fun duplicateTitleWithoutArtistRemainsAmbiguous() {
        data class TitleOnly(val value: String)
        val other = levels.copy(id = 999L, artist = "Other")
        assertNull(
            TrackMixPrePlayTargetResolver().resolve(
                listOf(TitleOnly("Levels")),
                listOf(levels, other)
            )
        )
    }

    @Test fun duplicateExactPathsRemainAmbiguousEvenWithIndexedLookup() {
        data class PathOnly(val value: String)
        val duplicate = fade.copy(path = levels.path)
        assertNull(
            TrackMixPrePlayTargetResolver().resolve(
                listOf(PathOnly(levels.path!!)),
                listOf(levels, duplicate)
            )
        )
    }

    @Test fun idNeedsCorroborationAndCanUseArtistWithoutTitle() {
        data class IdAndArtist(val number: Long, val text: String)
        val result = TrackMixPrePlayTargetResolver().resolve(
            listOf(IdAndArtist(levels.id, "Avicii")),
            listOf(levels, fade)
        )
        assertEquals(levels.id, result?.track?.id)
        assertTrue(result?.evidence?.contains("id+artist") == true)
    }

    @Test fun repeatedLibraryIdRetainsFirstEquallyScoredMetadata() {
        data class MultipleTitles(val secondTitle: String, val firstTitle: String, val artist: String)
        val first = levels.copy(id = 4242L, title = "First", path = "/music/first.mp3")
        val second = levels.copy(id = 4242L, title = "Second", path = "/music/second.mp3")
        val result = TrackMixPrePlayTargetResolver().resolve(
            listOf(MultipleTitles("Second", "First", "Avicii")),
            listOf(first, second)
        )
        assertEquals("First", result?.track?.title)
    }

    @Test fun selectedTrackRemainsResolvableAmongLargeLibraryAndContextGraph() {
        val library = (1L..18_797L).map { index ->
            TrackInfo(
                index,
                "Song $index",
                "Artist ${index % 101}",
                null,
                "/music/$index.mp3"
            )
        }
        val selected = library[13_938]
        val manyOtherRows = Outer(library.take(48).map { track ->
            NativeTrack(track.id, track.title!!, track.artist!!, track.path!!)
        })
        val result = TrackMixPrePlayTargetResolver().resolve(
            listOf(
                Listener(
                    NativeTrack(selected.id, selected.title!!, selected.artist!!, selected.path!!),
                    manyOtherRows
                )
            ),
            library
        )
        assertEquals(selected.id, result?.track?.id)
        assertTrue((result?.inspectedObjects ?: 0) > 48)
    }

    @Test fun cyclicObjectGraphIsBoundedAndStillFindsStrongTarget() {
        val first = Cycle()
        val second = Cycle(selected = NativeTrack(3737L, "Levels", "Avicii", levels.path!!))
        first.next = second
        second.next = first
        val result = TrackMixPrePlayTargetResolver(maxObjects = 12).resolve(
            listOf(first),
            listOf(levels, fade)
        )
        assertEquals(3737L, result?.track?.id)
        assertTrue((result?.inspectedObjects ?: 99) <= 12)
    }
}
