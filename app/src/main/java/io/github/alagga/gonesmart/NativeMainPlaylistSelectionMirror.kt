package io.github.alagga.gonesmart

/**
 * Visual-only mirror of successfully handled original GMMP Playlists-tab
 * row interactions. GMMP retains native ActionMode and all actions.
 */
internal class NativeMainPlaylistSelectionMirror {
    private val selected = linkedSetOf<String>()
    val isSelecting: Boolean get() = selected.isNotEmpty()
    val selectedCount: Int get() = selected.size
    fun isSelected(path: String): Boolean = path in selected
    fun selectedPaths(): List<String> = selected.toList()

    /**
     * Visual prediction only. Used to paint the synthetic folder row
     * immediately while GoneSmart scrolls the hidden native RecyclerView to
     * dispatch GMMP's real click/long-click. The mirror is committed only
     * after the original native action reports handled.
     */
    fun previewSelected(path: String, longClick: Boolean): Boolean? {
        if (path.isBlank()) return null
        if (longClick) return true
        if (!isSelecting) return null
        return !isSelected(path)
    }

    fun onNativeAction(path: String, longClick: Boolean): Boolean {
        if (path.isBlank()) return false
        if (longClick) return selected.add(path)
        if (!isSelecting) return false
        if (!selected.add(path)) selected.remove(path)
        return true
    }

    fun clear() = selected.clear()
}
