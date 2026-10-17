package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TrackMixFileBrowserMenuResolverTest {
    private data class Selected(val path: String)
    private data class Listener(val selected: Any, val adapterRows: List<Any> = emptyList())
    private data class CollectionBridge(val rows: List<Any>)

    @Test fun regularFileBehindBoundedCollectionIsEligible() {
        val root = Files.createTempDirectory("gs-track-mix-file").toFile()
        val file = root.resolve("song.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(CollectionBridge(listOf(Listener(Selected(file.absolutePath)))))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.REGULAR_FILE, result.kind)
        assertEquals("existing-file", result.evidence)
        root.deleteRecursively()
    }

    @Test fun shallowDirectoryWinsOverDeeperChildFile() {
        val root = Files.createTempDirectory("gs-track-mix-dir").toFile()
        val child = root.resolve("child.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(
                CollectionBridge(
                    listOf(
                        Listener(
                            selected = Selected(root.absolutePath),
                            adapterRows = listOf(Selected(child.absolutePath))
                        )
                    )
                )
            )
        )
        assertEquals(TrackMixFileBrowserSelectionKind.DIRECTORY, result.kind)
        assertTrue(result.inspectedObjects <= 240)
        root.deleteRecursively()
    }

    @Test fun adapterLikeCollectionWithMultipleFileTargetsFailsClosed() {
        val root = Files.createTempDirectory("gs-track-mix-many").toFile()
        val first = root.resolve("one.mp3").apply { writeText("1") }
        val second = root.resolve("two.mp3").apply { writeText("2") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(
                CollectionBridge(
                    listOf(
                        Selected(first.absolutePath),
                        Selected(second.absolutePath)
                    )
                )
            )
        )
        assertEquals(TrackMixFileBrowserSelectionKind.UNKNOWN, result.kind)
        assertEquals("ambiguous-shallow-paths", result.evidence)
        root.deleteRecursively()
    }

    @Test fun unknownPathFailsClosed() {
        val missing = "/definitely/not/a/real/gonesmart/path/song.mp3"
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(CollectionBridge(listOf(Selected(missing))))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.UNKNOWN, result.kind)
    }
}
