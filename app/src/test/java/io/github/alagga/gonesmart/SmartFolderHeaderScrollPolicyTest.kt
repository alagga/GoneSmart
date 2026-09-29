package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderHeaderScrollPolicyTest {
    @Test fun consumedScrollDeltaMovesImmediately() {
        assertEquals(
            48,
            SmartFolderHeaderScrollPolicy.folderScrollOffsetAfterDelta(
                folderHeight = 288,
                currentOffset = 0,
                dy = 48
            )
        )
    }

    @Test fun clampsAtBottomOfFiniteFolderBand() {
        assertEquals(
            288,
            SmartFolderHeaderScrollPolicy.folderScrollOffsetAfterDelta(
                folderHeight = 288,
                currentOffset = 240,
                dy = 200
            )
        )
    }

    @Test fun reverseScrollBringsFolderBandBackContinuously() {
        assertEquals(
            198,
            SmartFolderHeaderScrollPolicy.folderScrollOffsetAfterDelta(
                folderHeight = 288,
                currentOffset = 288,
                dy = -90
            )
        )
    }

    @Test fun uninitializedOffsetStartsAtTop() {
        assertEquals(
            0,
            SmartFolderHeaderScrollPolicy.folderScrollOffsetAfterDelta(
                folderHeight = 288,
                currentOffset = Int.MIN_VALUE,
                dy = -50
            )
        )
    }
}
