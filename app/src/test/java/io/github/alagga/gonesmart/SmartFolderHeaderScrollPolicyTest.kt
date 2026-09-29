package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderHeaderScrollPolicyTest {
    @Test fun folderBandStartsFullyVisibleAtNativeListTop() {
        assertEquals(
            0,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                nativeScrollOffset = 0
            )
        )
    }

    @Test fun folderBandTracksPartialNativeScroll() {
        assertEquals(
            48,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                nativeScrollOffset = 48
            )
        )
    }

    @Test fun folderBandIsGoneAfterScrollingPastItsHeight() {
        assertEquals(
            120,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                nativeScrollOffset = 240
            )
        )
    }

    @Test fun negativeOffsetsClampToTop() {
        assertEquals(
            0,
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = 120,
                nativeScrollOffset = -30
            )
        )
    }
}
