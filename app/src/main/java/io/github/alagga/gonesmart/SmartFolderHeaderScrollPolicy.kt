package io.github.alagga.gonesmart

/**
 * Smart physical-folder rows are rendered above GMMP's native ls4 RecyclerView.
 *
 * Keep the native RecyclerView's FULL consumed scroll distance. Only the
 * visual folder translation is capped to the finite folder-band height.
 * Capping the stored distance itself loses how far the native list travelled
 * after the folder was already hidden; then the first upward dy incorrectly
 * makes the folder reappear while the list is still far from its top.
 */
internal object SmartFolderHeaderScrollPolicy {
    fun scrollDistanceAfterDelta(
        currentDistance: Int,
        dy: Int
    ): Int {
        val current = currentDistance.toLong().coerceAtLeast(0L)
        return (current + dy.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun folderTranslation(
        folderHeight: Int,
        scrollDistance: Int
    ): Int {
        if (folderHeight <= 0) return 0
        return scrollDistance.coerceIn(0, folderHeight)
    }
}
