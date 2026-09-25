package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBreadcrumbScrollPolicyTest {
    @Test fun userSwipeIsNotLostOnSameFolderRender() {
        assertEquals(136, PlaylistBreadcrumbScrollPolicy.targetX(false, 136, 920, 500))
    }

    @Test fun navigatingToDeeperFolderRevealsTheCurrentSegment() {
        assertEquals(420, PlaylistBreadcrumbScrollPolicy.targetX(true, 136, 920, 500))
    }

    @Test fun narrowerAndNonScrollablePathsClampTheirOldOffset() {
        assertEquals(25, PlaylistBreadcrumbScrollPolicy.targetX(false, 136, 525, 500))
        assertEquals(0, PlaylistBreadcrumbScrollPolicy.targetX(false, 136, 260, 500))
        assertEquals(0, PlaylistBreadcrumbScrollPolicy.targetX(true, 0, 260, 500))
    }
}
