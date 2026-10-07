package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBreadcrumbPathTest {
    private val root = "/storage/emulated/0/gmmp/playlists"

    private fun index(vararg paths: String) = PlaylistFolderIndex.build(
        nativePlaylistPaths = paths.toList(),
        mainPlaylistDirectory = root,
        groupExternalLocations = true,
        groupRootPlaylists = true
    )

    @Test fun rootUsesNoHeaderAndNoExtraHeight() {
        val folders = index("$root/Music/House/A.m3u")
        assertTrue(PlaylistBreadcrumbPath.forFolder(folders, null).isEmpty())
    }

    @Test fun nestedFolderShowsStoragePlaceholderAndEveryClickableAncestor() {
        val folders = index("$root/Music/House/Progressive/Set.m3u")
        val music = folders.folders.single()
        val house = music.children.single()
        val progressive = house.children.single()
        val trail = PlaylistBreadcrumbPath.forFolder(folders, progressive.id)
        assertEquals(listOf("", "Music", "House", "Progressive"),
            trail.map { it.name })
        assertEquals(listOf(null, music.id, house.id, progressive.id),
            trail.map { it.folderId })
    }

    @Test fun virtualOtherLocationsIsAnIndependentClickableDestination() {
        val folders = index("$root/Music/A.m3u", "/storage/external/Other.m3u")
        val other = folders.otherLocations!!
        val trail = PlaylistBreadcrumbPath.forFolder(folders, other.id)
        assertEquals(listOf("", "Other Locations"), trail.map { it.name })
        assertEquals(listOf(null, other.id), trail.map { it.folderId })
    }

    @Test fun missingFolderNeverShowsStaleNavigation() {
        assertTrue(
            PlaylistBreadcrumbPath.forFolder(
                index("$root/Music/House/Set.m3u"), "$root/Deleted"
            ).isEmpty()
        )
    }

    @Test fun identicallyNamedFoldersKeepDistinctNativeIds() {
        val folders = index(
            "$root/A/Mixes/One.m3u",
            "$root/B/Mixes/Two.m3u"
        )
        val a = folders.folders.single { it.name == "A" }
        val b = folders.folders.single { it.name == "B" }
        val aTrail = PlaylistBreadcrumbPath.forFolder(folders, a.children.single().id)
        val bTrail = PlaylistBreadcrumbPath.forFolder(folders, b.children.single().id)
        assertEquals("Mixes", aTrail.last().name)
        assertEquals("Mixes", bTrail.last().name)
        assertEquals(a.children.single().id, aTrail.last().folderId)
        assertEquals(b.children.single().id, bTrail.last().folderId)
        assertTrue(aTrail.last().folderId != bTrail.last().folderId)
    }
}
