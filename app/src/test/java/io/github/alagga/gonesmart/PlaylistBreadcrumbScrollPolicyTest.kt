package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBreadcrumbScrollPolicyTest {
    @Test fun userSwipeIsNotLostOnSameFolderRender() {
        assertEquals(136, PlaylistBreadcrumbScrollPolicy.targetX(false, 136, 920, 500))
    }

    @Test fun everyNavigationRevealsTheNewestPathComponent() {
        assertEquals(420, PlaylistBreadcrumbScrollPolicy.targetX(true, 136, 920, 500))
        assertEquals(610, PlaylistBreadcrumbScrollPolicy.targetX(true, 0, 1110, 500))
    }

    @Test fun lateNativeTypographyCanRevealTheEndAgain() {
        // A delayed native style sample makes the content wider.
        assertEquals(420, PlaylistBreadcrumbScrollPolicy.targetX(true, 0, 920, 500))
        // A user who intentionally swiped left stays where they were.
        assertEquals(90, PlaylistBreadcrumbScrollPolicy.targetX(false, 90, 920, 500))
    }

    @Test fun shorterPathsClampOldOffsetAndElasticRangeFits() {
        assertEquals(25, PlaylistBreadcrumbScrollPolicy.targetX(false, 136, 525, 500))
        assertEquals(0, PlaylistBreadcrumbScrollPolicy.targetX(false, 136, 260, 500))
        assertEquals(1, PlaylistBreadcrumbScrollPolicy.targetX(true, 0, 501, 500))
    }
}
