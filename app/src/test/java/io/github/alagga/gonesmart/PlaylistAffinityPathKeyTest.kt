package io.github.alagga.gonesmart

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistAffinityPathKeyTest {
    @Test
    fun normalizesAbsolutePathsWithoutFilesystemResolution() {
        assertEquals(
            "/storage/bccf-3397/musik/tomorrow/track.mp3",
            PlaylistAffinityPathKey.fromAbsolutePath(
                "/storage/BCCF-3397/Musik/./Tomorrow/Vol 1/../Track.MP3"
            )
        )
    }

    @Test
    fun normalizesRelativePlaylistReferencesAgainstParentLexically() {
        val parent = File("/storage/emulated/0/gmmp/playlists/Festival")
        val candidate = File(parent, "../../Music/EDM/Track.mp3")

        assertEquals(
            "/storage/emulated/0/gmmp/music/edm/track.mp3",
            PlaylistAffinityPathKey.fromFile(candidate)
        )
    }

    @Test
    fun rejectsNonAbsolutePathKeys() {
        assertNull(
            PlaylistAffinityPathKey.fromAbsolutePath("Music/EDM/Track.mp3")
        )
    }
}
