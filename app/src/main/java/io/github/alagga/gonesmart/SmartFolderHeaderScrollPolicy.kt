package io.github.alagga.gonesmart

/**
 * Smart folder rows are rendered above GMMP's native ls4 RecyclerView.
 *
 * RecyclerView.computeVerticalScrollOffset() is intentionally NOT used here:
 * for variable/recycled rows it is an estimate and can jump when holders are
 * rebound. While adapter position 0 is visible, use its real pixel top.
 * Once position 0 is gone, the finite folder band is already fully offscreen.
 */
internal object SmartFolderHeaderScrollPolicy {
    fun folderScrollOffset(
        folderHeight: Int,
        listPaddingTop: Int,
        firstChildTop: Int?,
        firstAdapterPosition: Int
    ): Int {
        if (folderHeight <= 0) return 0
        if (firstAdapterPosition > 0) return folderHeight
        if (firstAdapterPosition < 0 || firstChildTop == null) return 0
        return (listPaddingTop - firstChildTop)
            .coerceIn(0, folderHeight)
    }
}
