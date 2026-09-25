package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCreationUiPolicyTest {
    private val root = "/storage/emulated/0/gmmp/playlists"

    @Test fun groupRootOnHidesRootAndShowsVirtualOtherLocations() {
        val rootState = PlaylistCreationUiPolicy.state(
            true, null, root, true, false
        )
        assertFalse(rootState.normalMenuVisible)
        assertFalse(rootState.pickerFabVisible)

        val other = PlaylistCreationUiPolicy.state(
            true, PlaylistFolderIndex.OTHER_LOCATIONS_ID,
            root, true, false
        )
        assertTrue(other.normalMenuVisible)
        assertTrue(other.pickerFabVisible)
        assertEquals(root, other.destination)
    }

    @Test fun groupRootOffShowsRootAndHidesVirtualOtherLocations() {
        val rootState = PlaylistCreationUiPolicy.state(
            true, null, root, false, false
        )
        assertTrue(rootState.normalMenuVisible)
        assertTrue(rootState.pickerFabVisible)

        val other = PlaylistCreationUiPolicy.state(
            true, PlaylistFolderIndex.OTHER_LOCATIONS_ID,
            root, false, false
        )
        assertFalse(other.normalMenuVisible)
        assertFalse(other.pickerFabVisible)
    }

    @Test fun physicalFolderKeepsPickerPlusButHidesUnsafeNormalMenu() {
        val physical = "$root/Trance"
        for (groupRoot in listOf(false, true)) {
            val state = PlaylistCreationUiPolicy.state(
                true, physical, root, groupRoot, false
            )
            assertFalse(state.normalMenuVisible)
            assertTrue(state.pickerFabVisible)
            assertTrue(state.physicalDestinationUnsupported)
            assertEquals(physical, state.destination)
        }
    }

    @Test fun multiSelectionAlwaysShowsPickerConfirmFab() {
        val state = PlaylistCreationUiPolicy.state(
            true, null, root, true, true
        )
        assertTrue(state.pickerFabVisible)
    }

    @Test fun disablingFoldersRestoresNativeCreateControls() {
        val state = PlaylistCreationUiPolicy.state(
            false, null, root, true, false
        )
        assertTrue(state.normalMenuVisible)
        assertTrue(state.pickerFabVisible)
    }
}
