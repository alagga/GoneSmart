package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FolderDeletePolicyTest {
    @Test fun rejectsRootAndAnythingOutsideNativeRoot() {
        val root = Files.createTempDirectory("gs-root").toFile()
        val other = Files.createTempDirectory("gs-other").toFile()
        try {
            assertNull(FolderDeletePolicy.prepare(root, root, emptyList()))
            assertNull(FolderDeletePolicy.prepare(root, other, emptyList()))
        } finally {
            root.deleteRecursively()
            other.deleteRecursively()
        }
    }

    @Test fun rejectsUnindexedFilesBeforeShowingNativeConfirm() {
        val root = Files.createTempDirectory("gs-root").toFile()
        val folder = File(root, "House").apply { mkdirs() }
        File(folder, "untracked.mp3").writeText("never delete music")
        try {
            assertNull(
                FolderDeletePolicy.prepare(root, folder, emptyList())
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun acceptsNativeM3uFilesAndOnlyCleansVerifiedEmptyDirs() {
        val root = Files.createTempDirectory("gs-root").toFile()
        val folder = File(root, "House").apply { mkdirs() }
        val nested = File(folder, "Level2").apply { mkdirs() }
        val playlist = File(nested, "playlist.m3u")
            .apply { writeText("#EXTM3U\n") }
        try {
            val plan = FolderDeletePolicy.prepare(
                root, folder, listOf(playlist.path)
            )
            assertNotNull(plan)
            assertEquals(1, plan!!.nativePlaylistFiles.size)
            assertFalse(
                FolderDeletePolicy.nativeRemovalComplete(
                    plan, listOf(playlist.path)
                )
            )
            // The only local cleanup is empty dirs, AFTER the native
            // player removes the M3U and the native model forgets it.
            assertTrue(playlist.delete())
            assertTrue(
                FolderDeletePolicy.nativeRemovalComplete(plan, emptyList())
            )
            assertTrue(FolderDeletePolicy.removeEmptyDirectories(plan))
            assertFalse(folder.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun emptyDirectoryIsAValidNativeFileDeleteTarget() {
        val root = Files.createTempDirectory("gs-root").toFile()
        val folder = File(root, "Empty").apply { mkdirs() }
        try {
            val plan = FolderDeletePolicy.prepare(root, folder, emptyList())
            assertNotNull(plan)
            assertTrue(plan!!.nativePlaylistFiles.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
