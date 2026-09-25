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

    @Test fun surfacesAreIndependent() {
        val memory = PlaylistFolderNavigationMemory()
        memory.remember("playlists-tab", "normal-folder")
        memory.remember("add-picker", "picker-folder")
        assertEquals(
            "normal-folder",
            memory.restore("playlists-tab") { true }
        )
        assertEquals(
            "picker-folder",
            memory.restore("add-picker") { true }
        )
    }
}
