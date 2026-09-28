package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderHeaderScrollPolicyTest {
    @Test fun folderBandStartsFullyVisibleAtNativeListTop() {
        assertEquals(0, SmartFolderHeaderScrollPolicy.folderScrollOffset(
            folderHeight = 120,
            listPaddingTop = 180,
            firstChildTop = 180,
            firstAdapterPosition = 0
        ))
    }

    @Test fun folderBandTracksPartialNativeScroll() {
        assertEquals(48, SmartFolderHeaderScrollPolicy.folderScrollOffset(
            folderHeight = 120,
            listPaddingTop = 180,
            firstChildTop = 132,
            firstAdapterPosition = 0
        ))
    }

    @Test fun folderBandIsGoneAfterFirstNativeRowLeaves() {
        assertEquals(120, SmartFolderHeaderScrollPolicy.folderScrollOffset(
            folderHeight = 120,
            listPaddingTop = 180,
            firstChildTop = 170,
            firstAdapterPosition = 1
        ))
    }

    @Test fun offsetIsClampedToFolderBandHeight() {
        assertEquals(120, SmartFolderHeaderScrollPolicy.folderScrollOffset(
            folderHeight = 120,
            listPaddingTop = 180,
            firstChildTop = -500,
            firstAdapterPosition = 0
        ))
    }
}
