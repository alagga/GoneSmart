package io.github.alagga.gonesmart

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

            val ready = assertIs<SmartPlaylistMovePolicy.Result.Ready>(result)
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
            assertIs<SmartPlaylistMovePolicy.Result.Blocked>(
                SmartPlaylistMovePolicy.prepare(
                    root, outside, listOf(source)
                )
            )
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

            val blocked = assertIs<SmartPlaylistMovePolicy.Result.Blocked>(
                SmartPlaylistMovePolicy.prepare(
                    root, destination, listOf(source)
                )
            )
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
            val blocked = assertIs<SmartPlaylistMovePolicy.Result.Blocked>(
                SmartPlaylistMovePolicy.prepare(
                    root, root, listOf(source)
                )
            )
            assertTrue(blocked.reason.contains("already"))
        } finally {
            root.deleteRecursively()
        }
    }
}
