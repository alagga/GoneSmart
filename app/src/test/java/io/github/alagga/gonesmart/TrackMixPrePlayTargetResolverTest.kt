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
