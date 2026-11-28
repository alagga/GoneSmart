package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOverlayPositionPolicyTest {
    @Test fun followsNativeAppBarOffsetInBothDirections() {
        assertTrue(PlaylistNavigationSurfaceHost.needsPositionUpdate(144f, 130f))
        assertTrue(PlaylistNavigationSurfaceHost.needsPositionUpdate(0f, 144f))
        assertTrue(PlaylistNavigationSurfaceHost.needsPositionUpdate(144f, 0f))
    }

    @Test fun stableFrameDoesNotRepositionOverlay() {
        assertFalse(PlaylistNavigationSurfaceHost.needsPositionUpdate(144f, 144f))
        assertFalse(PlaylistNavigationSurfaceHost.needsPositionUpdate(144f, 144.25f))
    }
}
