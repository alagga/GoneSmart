package io.github.alagga.gonesmart

/**
 * Pure geometry for the move FAB in GMMP's actual playlist viewport.
 * The original native mini-player sometimes overlays its sibling rather
 * than clipping the RecyclerView's getGlobalVisibleRect().
 */
internal object MoveConfirmationUiPolicy {
    fun safeBottom(
        nativeListBottomPx: Int,
        visibleNativeMiniPlayerTopPx: Int?
    ): Int = minOf(
        nativeListBottomPx,
        visibleNativeMiniPlayerTopPx ?: nativeListBottomPx
    )

    fun bottomOcclusion(
        overlayBottomPx: Int,
        visibleBottomPx: Int
    ): Int = (overlayBottomPx - visibleBottomPx).coerceAtLeast(0)
}
