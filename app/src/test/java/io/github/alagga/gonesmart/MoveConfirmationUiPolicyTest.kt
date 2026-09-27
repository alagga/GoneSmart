package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveConfirmationUiPolicyTest {
    @Test
    fun nativeConfirmFabRisesAboveClippedMiniPlayerViewport() {
        assertEquals(
            168,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2200,
                visibleBottomPx = 2032
            )
        )
        assertEquals(
            0,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2032,
                visibleBottomPx = 2032
            )
        )
        assertEquals(
            0,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2000,
                visibleBottomPx = 2032
            )
        )
    }
}
