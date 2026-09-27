package io.github.alagga.gonesmart

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistMovePolicyTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("gonesmart-move").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun plansTwoIndexedPlaylistsAndResolvesRelativeTracks() = fixture { root ->
        val a = File(root, "A").apply { mkdirs() }
        val b = File(root, "B").apply { mkdirs() }
        val destination = File(root, "Target").apply { mkdirs() }
        val track = File(root, "song.mp3").apply { writeText("audio") }
        val first = File(a, "House.m3u")
            .apply { writeText("#EXTM3U\n../song.mp3\n") }
        val second = File(b, "Techno.m3u")
            .apply { writeText("#EXTM3U\n" + track.path + "\n") }
        val result = PlaylistMovePolicy.prepare(
            root, destination, listOf(first.path, second.path),
            listOf(first.path, second.path)
        )
        assertTrue(result is PlaylistMovePolicy.Result.Ready)
        val plan = (result as PlaylistMovePolicy.Result.Ready).plan
        assertEquals(2, plan.count)
        assertEquals(1, plan.entries[0].relativeTrackCount)
        assertEquals(track.canonicalPath, plan.entries[0].expectedRelativeTrackPaths.single())
        assertTrue(PlaylistMovePolicy.sourcesStillMatch(
            plan, listOf(first.path, second.path)
        ))
        first.appendText("# changed")
        assertFalse(PlaylistMovePolicy.sourcesStillMatch(
            plan, listOf(first.path, second.path)
        ))
    }

    @Test fun neverOverwritesOrAllowsDuplicateNames() = fixture { root ->
        val firstDir = File(root, "A").apply { mkdirs() }
        val secondDir = File(root, "B").apply { mkdirs() }
        val target = File(root, "Target").apply { mkdirs() }
        val first = File(firstDir, "Sample.m3u").apply { writeText("#EXTM3U") }
        val second = File(secondDir, "SAMPLE.m3u").apply { writeText("#EXTM3U") }
        val r = PlaylistMovePolicy.prepare(
            root, target, listOf(first.path, second.path),
            listOf(first.path, second.path)
        )
        assertEquals(
            PlaylistMovePolicy.BlockReason.TARGET_COLLISION,
            (r as PlaylistMovePolicy.Result.Blocked).reason
        )
        File(target, first.name).writeText("existing")
        val single = PlaylistMovePolicy.prepare(
            root, target, listOf(first.path), listOf(first.path)
        )
        assertEquals(
            PlaylistMovePolicy.BlockReason.TARGET_ALREADY_EXISTS,
            (single as PlaylistMovePolicy.Result.Blocked).reason
        )
    }

    @Test fun requiresOriginalNativeModelsAndRootContainment() = fixture { root ->
        val target = File(root, "Target").apply { mkdirs() }
        val original = File(root, "playlist.m3u").apply { writeText("#EXTM3U") }
        val missing = PlaylistMovePolicy.prepare(
            root, target, listOf(original.path), emptyList()
        )
        assertEquals(
            PlaylistMovePolicy.BlockReason.UNINDEXED_SOURCE,
            (missing as PlaylistMovePolicy.Result.Blocked).reason
        )
        val other = Files.createTempDirectory("outside").toFile()
        try {
            val outside = PlaylistMovePolicy.prepare(
                root, other, listOf(original.path), listOf(original.path)
            )
            assertEquals(
                PlaylistMovePolicy.BlockReason.INVALID_DESTINATION,
                (outside as PlaylistMovePolicy.Result.Blocked).reason
            )
        } finally { other.deleteRecursively() }
    }

    @Test fun refusesUnresolvableRelativeReferenceAndBadEncoding() = fixture { root ->
        val target = File(root, "Target").apply { mkdirs() }
        val original = File(root, "playlist.m3u").apply {
            writeText("#EXTM3U\n../missing/song.mp3\n")
        }
        val missing = PlaylistMovePolicy.prepare(
            root, target, listOf(original.path), listOf(original.path)
        )
        assertEquals(
            PlaylistMovePolicy.BlockReason.UNRESOLVED_RELATIVE_TRACK,
            (missing as PlaylistMovePolicy.Result.Blocked).reason
        )
        original.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val bad = PlaylistMovePolicy.prepare(
            root, target, listOf(original.path), listOf(original.path)
        )
        assertEquals(
            PlaylistMovePolicy.BlockReason.UNRESOLVED_RELATIVE_TRACK,
            (bad as PlaylistMovePolicy.Result.Blocked).reason
        )
    }

    @Test fun reportsNativeIndexTransitionWithoutTouchingOriginals() = fixture { root ->
        val target = File(root, "Target").apply { mkdirs() }
        val original = File(root, "original.m3u").apply { writeText("#EXTM3U") }
        val result = PlaylistMovePolicy.prepare(
            root, target, listOf(original.path), listOf(original.path)
        ) as PlaylistMovePolicy.Result.Ready
        val plan = result.plan
        assertFalse(PlaylistMovePolicy.nativeDestinationIndexed(plan, listOf(original.path)))
        assertFalse(PlaylistMovePolicy.nativeOriginalsGone(plan, listOf(original.path)))
        val copy = File(target, original.name).apply { writeText("#EXTM3U") }
        assertTrue(PlaylistMovePolicy.nativeDestinationIndexed(plan, listOf(copy.path)))
        original.delete()
        assertTrue(PlaylistMovePolicy.nativeOriginalsGone(plan, listOf(copy.path)))
    }
}
