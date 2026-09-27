package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistMoveStagerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun prepareFixture(): Pair<PlaylistMovePolicy.Plan, File> {
        val root = temp.newFolder("playlists")
        val from = File(root, "One").apply { mkdirs() }
        val dest = File(root, "Two").apply { mkdirs() }
        val music = File(root, "Music").apply { mkdirs() }
        File(music, "song.mp3").writeBytes(byteArrayOf(1, 2, 3))
        val playlist = File(from, "Demo.m3u").apply {
            writeText(
                "#EXTM3U\r\n#EXTINF:123,Song\r\n" +
                    "../Music/song.mp3\r\n",
                Charsets.UTF_8
            )
        }
        val plan = PlaylistMovePolicy.prepare(
            root, dest, listOf(playlist.path), listOf(playlist.path)
        ) as PlaylistMovePolicy.Result.Ready
        return plan.plan to root
    }

    @Test fun preservesMetadataAndResolvesOldRelativePath() {
        val (plan, root) = prepareFixture()
        val batch = PlaylistMoveStager.stage(plan, temp.newFolder("private"))
        val staged = batch.entries.single().normalized.readText()
        assertTrue(staged.startsWith("#EXTM3U\r\n#EXTINF:123,Song\r\n"))
        assertTrue(staged.contains(
            File(root, "Music/song.mp3").canonicalPath + "\r\n"
        ))
        assertFalse(staged.contains("../Music/"))
        assertTrue(plan.entries.single().source.exists())
        assertFalse(batch.entries.single().target.exists())
    }

    @Test fun durableRecoveryAndPublishAfterOriginalNativeDeletion() {
        val (plan, _) = prepareFixture()
        val filesDir = temp.newFolder("private")
        val stage = PlaylistMoveStager.stage(plan, filesDir)
        val recovered = PlaylistMoveStager.recover(filesDir, plan.root)
        assertEquals(1, recovered.size)
        assertEquals(stage.entries.single().target, recovered.single().entries.single().target)
        // Emulate the PRECONDITION, not the native deletion implementation:
        // GMMP must have removed both its old file AND indexed old row.
        assertTrue(plan.entries.single().source.delete())
        assertTrue(PlaylistMoveStager.commit(recovered.single()))
        assertTrue(recovered.single().entries.single().target.exists())
        assertEquals(
            recovered.single().entries.single().normalizedHash,
            PlaylistMoveStager.hash(
                recovered.single().entries.single().target.readBytes()
            )
        )
        PlaylistMoveStager.finish(recovered.single())
        assertFalse(stage.directory.exists())
    }

    @Test fun destinationCollisionNeverOverwritesOrRemovesOriginal() {
        val (plan, _) = prepareFixture()
        val stage = PlaylistMoveStager.stage(plan, temp.newFolder("private"))
        val existing = stage.entries.single().target
        existing.writeText("KEEP")
        assertFalse(PlaylistMoveStager.commit(stage))
        assertEquals("KEEP", existing.readText())
        assertTrue(stage.entries.single().source.exists())
        assertTrue(stage.entries.single().original.isFile)
    }

    @Test fun nonExistingRelativeTracksAreRejectedByPreflight() {
        val (plan, _) = prepareFixture()
        val source = plan.entries.single().source
        source.writeText("#EXTM3U\nmissing.mp3\n")
        val result = PlaylistMovePolicy.prepare(
            plan.root, plan.destination, listOf(source.path), listOf(source.path)
        )
        assertTrue(result is PlaylistMovePolicy.Result.Blocked)
    }
}
