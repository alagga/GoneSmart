package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderHeaderScrollPolicyTest {
    @Test fun ignoresHolderFromPreviousFolderSnapshot() {
        assertEquals(
            false,
            SmartFolderHeaderScrollPolicy.rowMatchesSnapshot(
                expectedPath = "/smart/current.spl",
                boundPath = "/smart/previous.spl"
            )
        )
        assertEquals(
            true,
            SmartFolderHeaderScrollPolicy.rowMatchesSnapshot(
                expectedPath = "/smart/current.spl",
                boundPath = "/smart/current.spl"
            )
        )
    }

    @Test fun startsAtZero() {
        assertEquals(
            0,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 288,
                listPaddingTop = 432,
                firstChildTop = 432,
                firstAdapterPosition = 0,
                nativeRowHeight = 144
            )
        )
    }

    @Test fun tracksRealPixelsWhileFirstRowIsVisible() {
        assertEquals(
            48,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 288,
                listPaddingTop = 432,
                firstChildTop = 384,
                firstAdapterPosition = 0,
                nativeRowHeight = 144
            )
        )
    }

    @Test fun remainsContinuousAcrossRecyclerBoundary() {
        val beforeRecycle =
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 288,
                listPaddingTop = 432,
                firstChildTop = 289,
                firstAdapterPosition = 0,
                nativeRowHeight = 144
            )
        val afterRecycle =
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 288,
                listPaddingTop = 432,
                firstChildTop = 433,
                firstAdapterPosition = 1,
                nativeRowHeight = 144
            )
        assertEquals(143, beforeRecycle)
        assertEquals(143, afterRecycle)
    }

    @Test fun clampsOnlyAfterWholeFolderBandScrolledAway() {
        assertEquals(
            288,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 288,
                listPaddingTop = 432,
                firstChildTop = 420,
                firstAdapterPosition = 2,
                nativeRowHeight = 144
            )
        )
    }
}
