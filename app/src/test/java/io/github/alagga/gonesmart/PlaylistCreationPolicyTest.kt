package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCreationPolicyTest {
    private val root = "/storage/emulated/0/gmmp/playlists"

    @Test fun rootCreationOnlyWhenRootGroupingOff() {
        assertNull(PlaylistCreationPolicy.destination(null, root, true))
        assertEquals(root, PlaylistCreationPolicy.destination(null, root, false))
        assertNull(PlaylistCreationPolicy.destination(root, root, true))
        assertEquals(root, PlaylistCreationPolicy.destination(root, root, false))
    }

    @Test fun virtualOtherCreationOnlyWhenRootGroupingOn() {
        val virtual = PlaylistFolderIndex.OTHER_LOCATIONS_ID
        assertEquals(root, PlaylistCreationPolicy.destination(virtual, root, true))
        assertNull(PlaylistCreationPolicy.destination(virtual, root, false))
    }

    @Test fun everyRealSubfolderTargetsItselfWithEitherSetting() {
        val nested = "$root/Trance/2026"
        for (groupRoot in listOf(false, true)) {
            assertEquals(
                nested,
                PlaylistCreationPolicy.destination(nested, root, groupRoot)
            )
        }
    }

    @Test fun realFolderNamedOtherLocationsIsNotTheVirtualNode() {
        val physical = "$root/Other Locations"
        val virtual = PlaylistFolderIndex.OTHER_LOCATIONS_ID
        assertEquals(
            physical,
            PlaylistCreationPolicy.destination(physical, root, false)
        )
        assertEquals(
            physical,
            PlaylistCreationPolicy.destination(physical, root, true)
        )
        assertFalse(PlaylistCreationPolicy.canCreate(virtual, root, false))
        assertTrue(PlaylistCreationPolicy.canCreate(virtual, root, true))
    }

    @Test fun rejectsExternalTraversalRelativeAndSiblingFolders() {
        for (outside in listOf(
            "/storage/emulated/0/gmmp/playlists2",
            "$root/../../outside",
            "content://external/folder",
            "../relative/folder"
        )) {
            assertNull(
                PlaylistCreationPolicy.destination(outside, root, true)
            )
        }
    }

    @Test fun rejectsRelativeMainRoot() {
        assertNull(
            PlaylistCreationPolicy.destination(null, "gmmp/playlists", false)
        )
    }
}
