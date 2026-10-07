package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SmartPlaylistMovePolicyTest {
    @Test
    fun preparesNestedPhysicalMove() {
        val root = Files.createTempDirectory("smart-root").toFile()
        try {
            val sourceDir = File(root, "A").apply { mkdirs() }
            val destination = File(root, "B").apply { mkdirs() }
            val source = File(sourceDir, "Mix.spl").apply { writeText("x") }

            val result = SmartPlaylistMovePolicy.prepare(
                root, destination, listOf(source)
            )

            assertTrue(result is SmartPlaylistMovePolicy.Result.Ready)
            val ready = result as SmartPlaylistMovePolicy.Result.Ready
            assertEquals(1, ready.moves.size)
            assertEquals(
                File(destination, "Mix.spl").canonicalPath,
                ready.moves.single().target.canonicalPath
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun blocksDestinationOutsideRoot() {
        val root = Files.createTempDirectory("smart-root").toFile()
        val outside = Files.createTempDirectory("smart-outside").toFile()
        try {
            val source = File(root, "Mix.spl").apply { writeText("x") }
            val result = SmartPlaylistMovePolicy.prepare(
                root, outside, listOf(source)
            )
            assertTrue(result is SmartPlaylistMovePolicy.Result.Blocked)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun blocksCollisionWithoutOverwrite() {
        val root = Files.createTempDirectory("smart-root").toFile()
        try {
            val sourceDir = File(root, "A").apply { mkdirs() }
            val destination = File(root, "B").apply { mkdirs() }
            val source = File(sourceDir, "Mix.spl").apply { writeText("source") }
            File(destination, "Mix.spl").writeText("target")

            val result = SmartPlaylistMovePolicy.prepare(
                root, destination, listOf(source)
            )
            assertTrue(result is SmartPlaylistMovePolicy.Result.Blocked)
            val blocked = result as SmartPlaylistMovePolicy.Result.Blocked
            assertTrue(blocked.reason.contains("already exists"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameDirectoryIsNoOpAndBlocked() {
        val root = Files.createTempDirectory("smart-root").toFile()
        try {
            val source = File(root, "Mix.spl").apply { writeText("x") }
            val result = SmartPlaylistMovePolicy.prepare(
                root, root, listOf(source)
            )
            assertTrue(result is SmartPlaylistMovePolicy.Result.Blocked)
            val blocked = result as SmartPlaylistMovePolicy.Result.Blocked
            assertTrue(blocked.reason.contains("already"))
        } finally {
            root.deleteRecursively()
        }
    }
}
