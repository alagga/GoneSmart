package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SmartPlaylistFolderPolicyTest {
    @Test fun acceptsRootAndNestedFoldersButNotSiblingPrefixes() {
        val root = File("/storage/emulated/0/Smart")
        assertTrue(
            SmartPlaylistFolderPolicy.isInsideRoot(
                root.path,
                File(root, "House/Progressive").path
            )
        )
        assertTrue(
            SmartPlaylistFolderPolicy.isInsideRoot(root.path, root.path)
        )
        assertFalse(
            SmartPlaylistFolderPolicy.isInsideRoot(
                root.path,
                "/storage/emulated/0/Smart2/House"
            )
        )
    }

    @Test fun redirectsOnlyBrandNewRootSaveIntoCurrentNestedFolder() {
        val root = "/storage/emulated/0/Smart"
        val current = "$root/House"
        assertEquals(
            "$current/Test.spl",
            SmartPlaylistFolderPolicy.redirectNewSave(
                root,
                current,
                "$root/Test.spl",
                originalExists = false
            )
        )
        assertNull(
            SmartPlaylistFolderPolicy.redirectNewSave(
                root,
                current,
                "$root/Test.spl",
                originalExists = true
            )
        )
        assertNull(
            SmartPlaylistFolderPolicy.redirectNewSave(
                root,
                root,
                "$root/Test.spl",
                originalExists = false
            )
        )
    }

    @Test fun never_retargetsAnExistingNestedDestination() {
        val root = "/storage/emulated/0/Smart"
        assertNull(
            SmartPlaylistFolderPolicy.redirectNewSave(
                root,
                "$root/House",
                "$root/Other/Test.spl",
                originalExists = false
            )
        )
    }
}
