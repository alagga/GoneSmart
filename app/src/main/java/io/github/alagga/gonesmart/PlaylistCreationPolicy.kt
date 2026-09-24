package io.github.alagga.gonesmart

import java.io.File

/**
 * Pure navigation policy for future native playlist-create controls.
 *
 * A null destination means the create action must be absent in both GMMP
 * playlist screens. The caller must additionally verify filesystem access,
 * a collision-free filename and the native GMMP creation/DB path before
 * enabling writes; this helper does not create files.
 */
internal object PlaylistCreationPolicy {

    /**
     * null is the main/root view; OTHER_LOCATIONS_ID is the virtual node;
     * a physical subfolder is identified by its canonical directory path.
     */
    fun destination(
        currentFolderId: String?,
        mainPlaylistDirectory: String,
        groupRootPlaylists: Boolean
    ): String? {
        val mainRoot = runCatching {
            val directory = File(mainPlaylistDirectory)
            if (!directory.isAbsolute) return null
            directory.canonicalPath
        }.getOrNull() ?: return null

        if (currentFolderId == null || currentFolderId == mainRoot) {
            return if (groupRootPlaylists) null else mainRoot
        }

        if (currentFolderId == PlaylistFolderIndex.OTHER_LOCATIONS_ID) {
            return if (groupRootPlaylists) mainRoot else null
        }

        // Only an actual main-root descendant can be a physical folder.
        // Do not allow external URIs, sibling directories or traversal.
        val normalized = runCatching {
            val directory = File(currentFolderId)
            if (!directory.isAbsolute) return null
            directory.canonicalPath
        }.getOrNull() ?: return null

        val prefix = mainRoot.trimEnd(File.separatorChar) + File.separator
        return normalized.takeIf { it.startsWith(prefix) }
    }

    fun canCreate(
        currentFolderId: String?,
        mainPlaylistDirectory: String,
        groupRootPlaylists: Boolean
    ): Boolean = destination(
        currentFolderId = currentFolderId,
        mainPlaylistDirectory = mainPlaylistDirectory,
        groupRootPlaylists = groupRootPlaylists
    ) != null
}
