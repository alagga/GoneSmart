package io.github.alagga.gonesmart

internal class PlaylistFolderNavigationMemory {
    private val ids = linkedMapOf<String, String>()

    fun remember(surface: String, folderId: String?) {
        if (folderId == null) ids.remove(surface) else ids[surface] = folderId
    }

    fun restore(surface: String, isValid: (String) -> Boolean): String? {
        val id = ids[surface] ?: return null
        return if (isValid(id)) id else {
            ids.remove(surface)
            null
        }
    }

    fun clear(surface: String) {
        ids.remove(surface)
    }
}
