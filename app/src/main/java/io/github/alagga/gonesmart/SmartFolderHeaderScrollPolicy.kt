package io.github.alagga.gonesmart

/**
 * Smart physical-folder rows are rendered above GMMP's native ls4 RecyclerView.
 *
 * Their finite displacement is driven only by RecyclerView's exact consumed
 * scroll dy. PreDraw/holder geometry must never overwrite that value: on the
 * tested GMMP surface the holder/top calculation can report the reserved top
 * padding again while the native list is already scrolling, which pins the
 * synthetic folder until a later recycler boundary.
 */
internal object SmartFolderHeaderScrollPolicy {
    fun folderScrollOffsetAfterDelta(
        folderHeight: Int,
        currentOffset: Int,
        dy: Int
    ): Int {
        if (folderHeight <= 0) return 0
        val current = currentOffset.toLong().coerceAtLeast(0L)
        return (current + dy.toLong())
            .coerceIn(0L, folderHeight.toLong())
            .toInt()
    }
}
