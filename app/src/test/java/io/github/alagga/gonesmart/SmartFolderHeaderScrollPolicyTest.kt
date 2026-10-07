package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderHeaderScrollPolicyTest {
    @Test fun consumedScrollDeltaAccumulatesAbsoluteDistance() {
        assertEquals(
            48,
            SmartFolderHeaderScrollPolicy.scrollDistanceAfterDelta(
                currentDistance = 0,
                dy = 48
            )
        )
    }

    @Test fun distanceKeepsGrowingAfterFolderIsFullyHidden() {
        val distance = SmartFolderHeaderScrollPolicy.scrollDistanceAfterDelta(
            currentDistance = 240,
            dy = 200
        )
        assertEquals(440, distance)
        assertEquals(
            288,
            SmartFolderHeaderScrollPolicy.folderTranslation(
                folderHeight = 288,
                scrollDistance = distance
            )
        )
    }

    @Test fun upwardScrollDoesNotRevealFolderUntilNearRealTop() {
        val stillDeep = SmartFolderHeaderScrollPolicy.scrollDistanceAfterDelta(
            currentDistance = 900,
            dy = -90
        )
        assertEquals(810, stillDeep)
        assertEquals(
            288,
            SmartFolderHeaderScrollPolicy.folderTranslation(
                folderHeight = 288,
                scrollDistance = stillDeep
            )
        )

        val nearTop = SmartFolderHeaderScrollPolicy.scrollDistanceAfterDelta(
            currentDistance = 310,
            dy = -90
        )
        assertEquals(220, nearTop)
        assertEquals(
            220,
            SmartFolderHeaderScrollPolicy.folderTranslation(
                folderHeight = 288,
                scrollDistance = nearTop
            )
        )
    }

    @Test fun distanceNeverGoesBelowTop() {
        assertEquals(
            0,
            SmartFolderHeaderScrollPolicy.scrollDistanceAfterDelta(
                currentDistance = 10,
                dy = -50
            )
        )
    }

    @Test fun edgeStretchIsNeutralWithoutOverscroll() {
        assertEquals(
            1f,
            SmartFolderHeaderScrollPolicy.edgeStretchScale(0f),
            0.0001f
        )
    }

    @Test fun edgeStretchTracksAndroidEdgeEffectDamping() {
        assertEquals(
            1.1039f,
            SmartFolderHeaderScrollPolicy.edgeStretchScale(1f),
            0.001f
        )
    }
}
