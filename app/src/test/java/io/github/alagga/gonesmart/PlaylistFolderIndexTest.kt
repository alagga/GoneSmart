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
        assertEquals(3, result.folders.sumOf { it.playlists.size + it.children.sumOf { sub -> sub.playlists.size } })
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

    @Test fun otherLocationsHiddenWhenOnlyExternalGroupingIsOnAndNoneExist() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Trance/A.m3u"),
            root,
            groupExternalLocations = true,
            groupRootPlaylists = false
        )
        assertNull(result.otherLocations)
    }

    @Test fun groupedRootKeepsOtherLocationsVisibleEvenWhenEmpty() {
        for (external in listOf(false, true)) {
            val result = PlaylistFolderIndex.build(
                emptyList(),
                root,
                groupExternalLocations = external,
                groupRootPlaylists = true
            )
            assertTrue(result.otherLocations!!.virtual)
            assertTrue(result.otherLocations!!.playlists.isEmpty())
            assertEquals(
                listOf("Other Locations"),
                result.topLevelFolders.map { it.name }
            )
        }
    }

    @Test fun noGroupedRootAndNoGroupedExternalKeepsEmptyOtherHidden() {
        val result = PlaylistFolderIndex.build(
            emptyList(),
            root,
            groupExternalLocations = false,
            groupRootPlaylists = false
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

    @Test fun bothGroupOptionsOffKeepRootAndExternalLoose() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Trance/A.m3u", "$root/Loose.m3u", "/external/Outside.m3u"),
            root, groupExternalLocations = false, groupRootPlaylists = false
        )
        assertEquals(listOf("Trance"), result.topLevelFolders.map { it.name })
        assertNull(result.otherLocations)
        assertEquals(
            setOf(PlaylistFolderIndex.Location.MAIN_ROOT, PlaylistFolderIndex.Location.EXTERNAL),
            result.ungroupedPlaylists.map { it.location }.toSet()
        )
    }

    @Test fun externalGroupedAndRootLoose() {
        val result = PlaylistFolderIndex.build(
            listOf(
                "$root/Trance/A.m3u", "$root/Loose.m3u",
                "/external/Outside.m3u", "content://playlists/42"
            ), root, groupExternalLocations = true, groupRootPlaylists = false
        )
        assertEquals(listOf("Trance", "Other Locations"),
            result.topLevelFolders.map { it.name })
        assertEquals(listOf("$root/Loose.m3u"),
            result.ungroupedPlaylists.map { it.path })
        assertEquals(2, result.otherLocations!!.playlists.size)
        assertTrue(result.otherLocations!!.playlists.all {
            it.location == PlaylistFolderIndex.Location.EXTERNAL
        })
    }

    @Test fun rootGroupedAndExternalLoose() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Trance/A.m3u", "$root/Loose.m3u", "/external/Outside.m3u"),
            root, groupExternalLocations = false, groupRootPlaylists = true
        )
        assertEquals(listOf("Trance", "Other Locations"),
            result.topLevelFolders.map { it.name })
        assertEquals(listOf("/external/Outside.m3u"),
            result.ungroupedPlaylists.map { it.path })
        assertEquals(
            PlaylistFolderIndex.Location.MAIN_ROOT,
            result.otherLocations!!.playlists.single().location
        )
    }

    @Test fun bothOptionsOnShareOneVirtualFolder() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Trance/A.m3u", "$root/Loose.m3u", "/external/Outside.m3u"),
            root, groupExternalLocations = true, groupRootPlaylists = true
        )
        assertTrue(result.ungroupedPlaylists.isEmpty())
        assertEquals(listOf("Trance", "Other Locations"),
            result.topLevelFolders.map { it.name })
        assertEquals(
            setOf(PlaylistFolderIndex.Location.MAIN_ROOT, PlaylistFolderIndex.Location.EXTERNAL),
            result.otherLocations!!.playlists.map { it.location }.toSet()
        )
    }

    @Test fun arbitraryNestedDepthAndEmptyPhysicalSubfolders() {
        val result = PlaylistFolderIndex.build(
            listOf("$root/Genre/Decade/Year/Edition/Deep.m3u"),
            root, groupExternalLocations = true, groupRootPlaylists = true,
            physicalDirectoryPaths = listOf(
                "$root/Genre/Decade/Year/Edition",
                "$root/Empty/Child/Grandchild",
                "$root" + "2/Outside",
                "$root/../../Other/Escaped"
            )
        )
        val deep = result.folders.single { it.name == "Genre" }
            .children.single().children.single().children.single()
        assertEquals("Edition", deep.name)
        assertEquals(listOf("Deep.m3u"), deep.playlists.map { it.name })
        assertEquals(
            "Grandchild",
            result.folders.single { it.name == "Empty" }
                .children.single().children.single().name
        )
        assertEquals(2, result.folders.size)
    }


    @Test fun nativeDisplayNamesReplaceFilenamesAndDetermineSortOrder() {
        val one = "$root/z_file_name.m3u"
        val two = "/external/a_file_name.m3u"
        val index = PlaylistFolderIndex.build(
            nativePlaylistPaths = listOf(one, two),
            mainPlaylistDirectory = root,
            groupExternalLocations = false,
            groupRootPlaylists = false,
            displayNamesByPath = mapOf(
                one to "A Beautiful Playlist",
                two to "Z My External Mix"
            )
        )
        assertEquals(
            listOf("A Beautiful Playlist", "Z My External Mix"),
            index.ungroupedPlaylists.map { it.name }
        )
        assertEquals(listOf(one, two), index.ungroupedPlaylists.map { it.path })
    }

    @Test fun fallbackFilenameOnlyWhenNoNativeTitleIsAvailable() {
        val path = "$root/file-title.m3u"
        val index = PlaylistFolderIndex.build(
            nativePlaylistPaths = listOf(path),
            mainPlaylistDirectory = root,
            groupExternalLocations = false,
            groupRootPlaylists = false,
            displayNamesByPath = mapOf(path to "   ")
        )
        assertEquals("file-title.m3u", index.ungroupedPlaylists.single().name)
    }

    @Test fun canonicalDuplicatesCannotAppearInDifferentGroups() {
        val result = PlaylistFolderIndex.build(
            listOf(
                "$root/./Loose.m3u", "$root/Loose.m3u",
                "$root/Sub/../Loose.m3u",
                "/external/../external/Same.m3u", "/external/Same.m3u"
            ), root, groupExternalLocations = true, groupRootPlaylists = false
        )
        assertEquals(1, result.ungroupedPlaylists.size)
        assertEquals(1, result.otherLocations!!.playlists.size)
    }
}
