package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFolderIndexTest {
    private val root = "/storage/emulated/0/GMMP/Playlists"

    @Test fun nestsPhysicalFoldersAndKeepsPathsAsPlaylistIdentity() {
        val result = PlaylistFolderIndex.build(
            listOf(
                "$root/Trance/Classic/Favorites.m3u",
                "$root/Trance/Favorites.m3u",
                "$root/Progressive/Favorites.m3u"
            ), root, groupOtherLocations = false
        )
        assertEquals(listOf("Progressive", "Trance"), result.folders.map { it.name })
        val trance = result.folders.single { it.name == "Trance" }
        assertEquals(listOf("Favorites.m3u"), trance.playlists.map { it.name })
        assertEquals("Classic", trance.children.single().name)
        assertEquals(
            "$root/Trance/Classic/Favorites.m3u",
            trance.children.single().playlists.single().path
        )
        assertEquals(2, result.folders.sumOf { it.playlists.size + it.children.sumOf { sub -> sub.playlists.size } })
    }

    @Test fun groupingCombinesMainRootAndExternalPlaylistsOnly() {
        val result = PlaylistFolderIndex.build(
            listOf(
                "$root/Trance/A.m3u",
                "$root/Loose.m3u",
                "/storage/emulated/0/Music/Other.m3u",
                "content://playlists/7"
            ), root, groupOtherLocations = true
        )
        assertEquals(listOf("Trance", "Other Locations"), result.topLevelFolders.map { it.name })
        assertTrue(result.ungroupedPlaylists.isEmpty())
        val other = result.otherLocations!!
        assertTrue(other.virtual)
        assertEquals(3, other.playlists.size)
        assertTrue(other.playlists.any { it.path == "$root/Loose.m3u" })
        assertTrue(other.playlists.any { it.path == "content://playlists/7" })
    }

    @Test fun disablingGroupingShowsLooseItemsAfterPhysicalFolders() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Z/Inside.m3u", "$root/Loose.m3u", "/external/Outside.m3u"),
            root,
            groupOtherLocations = false
        )
        assertEquals(listOf("Z"), result.topLevelFolders.map { it.name })
        assertEquals(listOf("Loose.m3u", "Outside.m3u"), result.ungroupedPlaylists.map { it.name })
        assertNull(result.otherLocations)
    }

    @Test fun refusesSiblingPathTraversalAndSimilarPrefix() {
        val result = PlaylistFolderIndex.build(
            listOf(
                "$root/../Outside/Other.m3u",
                "${root}2/Trance/Misleading.m3u",
                "$root/Valid/Actual.m3u"
            ),
            root,
            groupOtherLocations = true
        )
        assertEquals(listOf("Valid"), result.folders.map { it.name })
        assertEquals(2, result.otherLocations!!.playlists.size)
    }

    @Test fun duplicatesAreSuppressedByCanonicalPathNotFilename() {
        val result = PlaylistFolderIndex.build(
            listOf(
                "$root/Trance/A.m3u",
                "$root/Trance/./A.m3u",
                "$root/Pop/A.m3u",
                " ",
                "$root/Loose.m3u",
                "$root/Loose.m3u"
            ),
            root,
            groupOtherLocations = false
        )
        assertEquals(1, result.folders.single { it.name == "Trance" }.playlists.size)
        assertEquals(1, result.folders.single { it.name == "Pop" }.playlists.size)
        assertEquals(1, result.ungroupedPlaylists.size)
        assertFalse(result.topLevelFolders.any { it.virtual })
    }

    @Test fun otherLocationsFolderOnlyAppearsIfNeeded() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Trance/A.m3u"),
            root,
            groupOtherLocations = true
        )
        assertNull(result.otherLocations)
    }

    @Test fun sameNamedRealAndVirtualFoldersRemainDistinct() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Other Locations/A.m3u", "$root/Loose.m3u"),
            root, groupOtherLocations = true
        )
        assertEquals(2, result.topLevelFolders.size)
        assertFalse(result.topLevelFolders[0].virtual)
        assertTrue(result.topLevelFolders[1].virtual)
        assertTrue(result.topLevelFolders[0].id != result.topLevelFolders[1].id)
    }
}
