package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMainPlaylistSelectionMirrorTest {
    @Test fun longPressStartsSelectionAndClickAddsAnother() {
        val mirror = NativeMainPlaylistSelectionMirror()
        assertFalse(mirror.onNativeAction("a", false))
        assertTrue(mirror.onNativeAction("a", true))
        assertTrue(mirror.onNativeAction("b", false))
        assertTrue(mirror.isSelected("a"))
        assertTrue(mirror.isSelected("b"))
        assertEquals(2, mirror.selectedCount)
    }

    @Test fun clickDeselectsLastPlaylistAndOrdinaryNavigationResumes() {
        val mirror = NativeMainPlaylistSelectionMirror()
        mirror.onNativeAction("a", true)
        mirror.onNativeAction("b", false)
        mirror.onNativeAction("a", false)
        assertFalse(mirror.isSelected("a"))
        assertEquals(1, mirror.selectedCount)
        mirror.onNativeAction("b", false)
        assertFalse(mirror.isSelecting)
        assertFalse(mirror.onNativeAction("c", false))
    }

    @Test fun theSameNameInDifferentFoldersIsNotTheSamePlaylist() {
        val mirror = NativeMainPlaylistSelectionMirror()
        assertTrue(mirror.onNativeAction("/root/House/Set.m3u", true))
        assertFalse(mirror.onNativeAction("/root/House/Set.m3u", true))
        mirror.onNativeAction("/root/Trance/Set.m3u", false)
        assertEquals(2, mirror.selectedCount)
        mirror.clear()
        assertEquals(0, mirror.selectedCount)
    }
}
