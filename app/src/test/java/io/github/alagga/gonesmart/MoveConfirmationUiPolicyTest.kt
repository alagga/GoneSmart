package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveConfirmationUiPolicyTest {
    @Test fun nativeConfirmFabRisesAboveClippedViewport() {
        assertEquals(
            168,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2331,
                visibleBottomPx = 2163
            )
        )
        assertEquals(
            0,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2163,
                visibleBottomPx = 2163
            )
        )
    }

    @Test fun alsoAccountsForOverlappingSiblingMiniPlayerBeforeFirstFrame() {
        // A sibling overlay does NOT affect RecyclerView.getGlobalVisibleRect.
        assertEquals(
            2163,
            MoveConfirmationUiPolicy.safeBottom(
                nativeListBottomPx = 2331,
                visibleNativeMiniPlayerTopPx = 2163
            )
        )
        assertEquals(
            168,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2331,
                visibleBottomPx = MoveConfirmationUiPolicy.safeBottom(
                    nativeListBottomPx = 2331,
                    visibleNativeMiniPlayerTopPx = 2163
                )
            )
        )
        assertEquals(
            2163,
            MoveConfirmationUiPolicy.safeBottom(
                nativeListBottomPx = 2163,
                visibleNativeMiniPlayerTopPx = null
            )
        )
    }
}
