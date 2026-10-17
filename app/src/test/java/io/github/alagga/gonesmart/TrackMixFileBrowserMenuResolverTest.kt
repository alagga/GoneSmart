package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TrackMixFileBrowserMenuResolverTest {
    private data class Selected(val path: String)
    private data class Listener(val selected: Any, val adapterRows: List<Any> = emptyList())

    @Test fun regularFileIsEligible() {
        val root = Files.createTempDirectory("gs-track-mix-file").toFile()
        val file = root.resolve("song.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(Listener(Selected(file.absolutePath)))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.REGULAR_FILE, result.kind)
        assertEquals("existing-file", result.evidence)
        root.deleteRecursively()
    }

    @Test fun directoryIsNotEligibleEvenWhenItsAdapterRowsContainFiles() {
        val root = Files.createTempDirectory("gs-track-mix-dir").toFile()
        val child = root.resolve("child.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(
                Listener(
                    selected = Selected(root.absolutePath),
                    adapterRows = listOf(Selected(child.absolutePath))
                )
            )
        )
        assertEquals(TrackMixFileBrowserSelectionKind.DIRECTORY, result.kind)
        assertTrue(result.inspectedObjects <= 64)
        root.deleteRecursively()
    }

    @Test fun unknownPathFailsClosed() {
        val missing = "/definitely/not/a/real/gonesmart/path/song.mp3"
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(Listener(Selected(missing)))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.UNKNOWN, result.kind)
    }
}
