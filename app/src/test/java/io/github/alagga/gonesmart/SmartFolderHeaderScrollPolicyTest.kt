package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderHeaderScrollPolicyTest {
    @Test fun folderBandStartsVisibleAtFirstRowTop() {
        assertEquals(
            0,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                listPaddingTop = 180,
                firstChildTop = 180,
                firstAdapterPosition = 0
            )
        )
    }

    @Test fun folderBandTracksActualFirstRowPixels() {
        assertEquals(
            48,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                listPaddingTop = 180,
                firstChildTop = 132,
                firstAdapterPosition = 0
            )
        )
    }

    @Test fun folderBandIsFullyGoneAfterFirstRowRecycles() {
        assertEquals(
            120,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                listPaddingTop = 180,
                firstChildTop = 170,
                firstAdapterPosition = 1
            )
        )
    }

    @Test fun missingVisibleChildDoesNotInventEstimatedOffset() {
        assertEquals(
            0,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                listPaddingTop = 180,
                firstChildTop = null,
                firstAdapterPosition = -1
            )
        )
    }
}
