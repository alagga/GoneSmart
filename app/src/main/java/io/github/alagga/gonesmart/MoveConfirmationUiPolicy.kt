package io.github.alagga.gonesmart

/**
 * Pure, testable native viewport geometry shared by the playlist move
 * destination browser and its separate original GMMP AestheticFab.
 *
 * The playlist page may extend BEHIND the mini-player; only the original
 * RecyclerView's getGlobalVisibleRect() supplies the true bottom edge.
 */
internal object MoveConfirmationUiPolicy {
    fun bottomOcclusion(
        overlayBottomPx: Int,
        visibleBottomPx: Int
    ): Int = (overlayBottomPx - visibleBottomPx).coerceAtLeast(0)
}
