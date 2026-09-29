package io.github.alagga.gonesmart

/**
 * Smart folder rows are rendered outside GMMP's native ls4 RecyclerView.
 * Couple their vertical translation to RecyclerView's cumulative scroll
 * offset instead of the currently attached first child, whose recycling can
 * otherwise make a header jump between offsets.
 */
internal object SmartFolderHeaderScrollPolicy {
    fun folderScrollOffset(
        folderHeight: Int,
        nativeScrollOffset: Int
    ): Int {
        if (folderHeight <= 0) return 0
        return nativeScrollOffset.coerceIn(0, folderHeight)
    }
}
