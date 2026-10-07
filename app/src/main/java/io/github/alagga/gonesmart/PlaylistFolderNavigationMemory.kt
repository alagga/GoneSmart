package io.github.alagga.gonesmart

import java.util.WeakHashMap

/**
 * Keep the regular Playlists tab's current folder across native navigation.
 * The Add picker remembers its folder only while the SAME native dialog
 * owner remains alive. A newly opened picker has a new owner and starts
 * at root; GMMP rebuilding the list inside the same dialog retains it.
 */
internal class PlaylistFolderNavigationMemory {
    private val tabIds = linkedMapOf<String, String>()
    private val pickerDialogIds = WeakHashMap<Any, String>()

    fun remember(surface: String, folderId: String?, owner: Any? = null) {
        if (surface == "add-picker") {
            if (owner == null) return
            if (folderId == null) pickerDialogIds.remove(owner)
            else pickerDialogIds[owner] = folderId
        } else {
            if (folderId == null) tabIds.remove(surface)
            else tabIds[surface] = folderId
        }
    }

    fun restore(
        surface: String,
        owner: Any? = null,
        isValid: (String) -> Boolean
    ): String? {
        val id = if (surface == "add-picker") {
            owner?.let(pickerDialogIds::get)
        } else tabIds[surface]
        if (id == null) return null
        if (isValid(id)) return id
        clear(surface, owner)
        return null
    }

    fun clear(surface: String, owner: Any? = null) {
        if (surface == "add-picker") {
            if (owner != null) pickerDialogIds.remove(owner)
        } else tabIds.remove(surface)
    }
}
