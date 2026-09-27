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

    /** Crop the Move browser above a sibling mini-player before drawing. */
    fun clippedOverlayHeight(
        listTopPx: Int,
        listHeightPx: Int,
        nativeMiniPlayerTopPx: Int?
    ): Int = nativeMiniPlayerTopPx
        ?.let { (it - listTopPx).coerceIn(1, listHeightPx) }
        ?: listHeightPx

    fun bottomOcclusion(
        overlayBottomPx: Int,
        visibleBottomPx: Int
    ): Int = (overlayBottomPx - visibleBottomPx).coerceAtLeast(0)
}
