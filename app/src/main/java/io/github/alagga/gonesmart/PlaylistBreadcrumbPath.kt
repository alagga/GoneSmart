package io.github.alagga.gonesmart

/**
 * A breadcrumb consists exclusively of GMMP's already-indexed playlist
 * folders. The root label is intentionally blank here: the injected view
 * resolves GMMP's localized "storage" string at runtime.
 *
 * When currentFolderId is null (the root), return no segments so the
 * breadcrumb takes no screen space.
 */
internal object PlaylistBreadcrumbPath {
    data class Segment(val folderId: String?, val name: String)

    fun forFolder(
        index: PlaylistFolderIndex.Result,
        currentFolderId: String?
    ): List<Segment> {
        if (currentFolderId == null) return emptyList()

        fun find(
            folders: List<PlaylistFolderIndex.Folder>,
            ancestors: List<PlaylistFolderIndex.Folder>
        ): List<PlaylistFolderIndex.Folder>? {
            for (folder in folders) {
                val trail = ancestors + folder
                if (folder.id == currentFolderId) return trail
                find(folder.children, trail)?.let { return it }
            }
            return null
        }

        val trail = find(index.topLevelFolders, emptyList()) ?: return emptyList()
        return listOf(Segment(folderId = null, name = "")) +
            trail.map { Segment(folderId = it.id, name = it.name) }
    }
}
