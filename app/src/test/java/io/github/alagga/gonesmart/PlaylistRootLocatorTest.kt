package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRootLocatorTest {
    private val primary = "/storage/emulated/0"
    private val root = "$primary/gmmp/playlists"

    @Test fun identifiesPrimaryRootFromNestedPlaylist() {
        assertEquals(
            root,
            PlaylistRootLocator.infer(
                listOf(
                    "/storage/BCCF-3397/Music/Other.m3u",
                    "$root/Trance/Level2/Level3/Deep.m3u"
                ),
                primary
            )
        )
    }

    @Test fun refusesToMistakeExternalOrSiblingForMainRoot() {
        assertNull(
            PlaylistRootLocator.infer(
                listOf(
                    "/storage/BCCF-3397/gmmp/playlists/Other.m3u",
                    "$root-other/Misleading.m3u"
                ),
                primary
            )
        )
    }

    @Test fun refusesUnverifiedRootAndUriOnlySources() {
        assertNull(
            PlaylistRootLocator.infer(
                listOf("content://music/playlist/42"),
                primary
            )
        )
        assertNull(
            PlaylistRootLocator.infer(
                listOf("$root/../other/Outside.m3u"),
                primary
            )
        )
    }

    @Test fun rejectsRelativePrimaryDirectory() {
        assertNull(
            PlaylistRootLocator.infer(
                listOf("gmmp/playlists/Relative.m3u"),
                "relative/storage"
            )
        )
    }
}
