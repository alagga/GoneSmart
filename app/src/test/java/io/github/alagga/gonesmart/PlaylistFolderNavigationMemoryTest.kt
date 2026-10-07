package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistFolderNavigationMemoryTest {
    @Test fun restoresSameFolderAfterNativeDetailNavigation() {
        val memory = PlaylistFolderNavigationMemory()
        memory.remember("playlists-tab", "physical-folder")
        assertEquals(
            "physical-folder",
            memory.restore("playlists-tab") { true }
        )
    }

    @Test fun returningToRootClearsRememberedFolder() {
        val memory = PlaylistFolderNavigationMemory()
        memory.remember("playlists-tab", "folder")
        memory.remember("playlists-tab", null)
        assertNull(memory.restore("playlists-tab") { true })
    }

    @Test fun staleFolderIsRejectedAfterGroupingChanges() {
        val memory = PlaylistFolderNavigationMemory()
        memory.remember("playlists-tab", "virtual-other")
        assertNull(memory.restore("playlists-tab") { false })
        assertNull(memory.restore("playlists-tab") { true })
    }

    @Test fun tabAndPickerStayIndependentWithinOneDialog() {
        val memory = PlaylistFolderNavigationMemory()
        val dialog = Any()
        memory.remember("playlists-tab", "normal-folder")
        memory.remember("add-picker", "picker-folder", dialog)
        assertEquals("normal-folder", memory.restore("playlists-tab") { true })
        assertEquals(
            "picker-folder",
            memory.restore("add-picker", dialog) { true }
        )
    }

    @Test fun newPickerStartsAtRootEvenIfEarlierPickerWasInsideFolder() {
        val memory = PlaylistFolderNavigationMemory()
        val previousDialog = Any()
        val nextDialog = Any()
        memory.remember("add-picker", "Level3", previousDialog)
        assertNull(memory.restore("add-picker", nextDialog) { true })
        assertEquals(
            "Level3",
            memory.restore("add-picker", previousDialog) { true }
        )
    }

    @Test fun closedPickerClearsOnlyItsOwnFolderMemory() {
        val memory = PlaylistFolderNavigationMemory()
        val first = Any()
        val second = Any()
        memory.remember("add-picker", "A", first)
        memory.remember("add-picker", "B", second)
        memory.clear("add-picker", first)
        assertNull(memory.restore("add-picker", first) { true })
        assertEquals("B", memory.restore("add-picker", second) { true })
    }

    @Test fun pickerMemoryRequiresRealDialogOwner() {
        val memory = PlaylistFolderNavigationMemory()
        memory.remember("add-picker", "Level3")
        assertNull(memory.restore("add-picker") { true })
    }
}
