package io.github.alagga.gonesmart

/**
 * Smart folder rows are rendered above GMMP's native ls4 RecyclerView.
 *
 * Normal user scrolling is driven by RecyclerView's exact consumed dy. The
 * adapter-position + child-top calculation remains a reconciliation fallback
 * after layout; holder identity must never be a prerequisite for moving the
 * folder band during an actual scroll. Do not use computeVerticalScrollOffset:
 * it is a scrollbar estimate and can jump as holders are rebound.
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

    fun rowMatchesSnapshot(
        expectedPath: String?,
        boundPath: String?
    ): Boolean = expectedPath != null && expectedPath == boundPath

    fun folderScrollOffset(
        folderHeight: Int,
        listPaddingTop: Int,
        firstChildTop: Int,
        firstAdapterPosition: Int,
        nativeRowHeight: Int
    ): Int {
        if (folderHeight <= 0 ||
            firstAdapterPosition < 0 ||
            nativeRowHeight <= 0
        ) return 0
        val rowDistance = firstAdapterPosition.toLong() *
            nativeRowHeight.toLong()
        val pixelDistance = listPaddingTop.toLong() -
            firstChildTop.toLong()
        return (rowDistance + pixelDistance)
            .coerceIn(0L, folderHeight.toLong())
            .toInt()
    }
}
