package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistFolderInsertionPlannerTest {
    private val old = listOf("playlist:a", "playlist:c")

    @Test fun insertBetweenExistingRowsMirrorsNativeFadeAndMove() {
        val plan = PlaylistFolderInsertionPlanner.plan(
            old, listOf("playlist:a", "playlist:b", "playlist:c")
        )!!
        assertEquals(setOf("playlist:b"), plan.newKeys)
        assertEquals(listOf(0, 0, 1), plan.shiftBefore)
    }

    @Test fun appendDoesNotMoveExistingRows() {
        val plan = PlaylistFolderInsertionPlanner.plan(
            old, old + "playlist:z"
        )!!
        assertEquals(listOf(0, 0, 0), plan.shiftBefore)
        assertEquals(setOf("playlist:z"), plan.newKeys)
    }

    @Test fun insertionInNewFolderOrFirstAttachIsNotAListAnimation() {
        assertNull(PlaylistFolderInsertionPlanner.plan(
            emptyList(), listOf("playlist:new")
        ))
    }

    @Test fun nativeResortRenameAndBulkRescanDoNotTriggerFalseInserts() {
        assertNull(PlaylistFolderInsertionPlanner.plan(
            old, listOf("playlist:c", "playlist:b", "playlist:a")
        ))
        assertNull(PlaylistFolderInsertionPlanner.plan(
            old, listOf("playlist:a", "playlist:b")
        ))
        assertNull(PlaylistFolderInsertionPlanner.plan(
            old, listOf("playlist:a", "playlist:b", "playlist:c",
                "playlist:d", "playlist:e", "playlist:f", "playlist:g")
        ))
        assertNull(PlaylistFolderInsertionPlanner.plan(
            old, listOf("playlist:a", "playlist:a", "playlist:c")
        ))
    }
}
