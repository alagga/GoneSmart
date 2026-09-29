package io.github.alagga.gonesmart

/**
 * Smart folder rows are rendered above GMMP's native ls4 RecyclerView.
 *
 * Use the real adapter position plus the real child top. Native Smart rows are
 * fixed-height on GMMP 4.2.0, so this remains continuous when RecyclerView
 * recycles position 0 into position 1. Do not use computeVerticalScrollOffset:
 * it is a scrollbar estimate and can jump as holders are rebound.
 */
internal object SmartFolderHeaderScrollPolicy {
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
