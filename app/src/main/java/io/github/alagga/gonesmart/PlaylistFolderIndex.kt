package io.github.alagga.gonesmart

import java.io.File

/**
 * Pure, read-only folder model for native GMMP playlist paths.
 *
 * The caller supplies GMMP's actual main playlist directory; do not guess it
 * from Android's storage paths. No files are opened, created, or moved here.
 * Playlist identity is the full native path, not the displayed filename.
 */
internal object PlaylistFolderIndex {
    const val OTHER_LOCATIONS = "Other Locations"
    const val OTHER_LOCATIONS_ID = "virtual:other-locations"

    data class Playlist(
        val path: String,
        val name: String
    )

    data class Folder(
        val id: String,
        val name: String,
        val children: List<Folder>,
        val playlists: List<Playlist>,
        val virtual: Boolean = false
    )

    data class Result(
        /** Physical folders under GMMP's configured main playlist directory. */
        val folders: List<Folder>,
        /** Direct-root and external playlists when grouping is disabled. */
        val ungroupedPlaylists: List<Playlist>,
        /** Virtual folder containing those same paths when grouping is on. */
        val otherLocations: Folder?
    ) {
        /** Folders first; then ungrouped playlists, if any. */
        val topLevelFolders: List<Folder>
            get() = folders + listOfNotNull(otherLocations)
    }

    /**
     * @param nativePlaylistPaths The file paths GMMP actually exposes from its
     * native playlist models, including paths from additional scan locations.
     * @param mainPlaylistDirectory GMMP's real main playlist directory.
     * @param groupOtherLocations Whether root-level/external paths appear
     * beneath the virtual "Other Locations" folder instead of as loose rows.
     */
    fun build(
        nativePlaylistPaths: Collection<String>,
        mainPlaylistDirectory: String,
        groupOtherLocations: Boolean
    ): Result {
        val mainRoot = normalizeFilePath(mainPlaylistDirectory)
            ?: throw IllegalArgumentException("Main playlist directory must be absolute")
        val root = MutableFolder(id = mainRoot, name = "")
        val loose = linkedMapOf<String, Playlist>()

        for (nativePath in nativePlaylistPaths) {
            val rawPath = nativePath.trim()
            if (rawPath.isEmpty()) continue

            // content:// and other non-file URIs are native playlist sources,
            // but not writable children of the main filesystem directory.
            val normalized = normalizeFilePath(rawPath)
            val identity = normalized ?: rawPath
            val playlist = Playlist(
                path = rawPath,
                name = rawPath.substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { rawPath }
            )

            val relative = if (normalized == null) null else relativeToRoot(
                normalized,
                mainRoot
            )

            if (relative == null || !relative.contains(File.separatorChar)) {
                loose.putIfAbsent(identity, playlist)
                continue
            }

            val directories = relative.substringBeforeLast(File.separatorChar)
                .split(File.separatorChar)
                .filter(String::isNotBlank)
            if (directories.isEmpty()) {
                loose.putIfAbsent(identity, playlist)
                continue
            }
            var current = root
            var currentPath = mainRoot
            for (directory in directories) {
                currentPath = currentPath.trimEnd(File.separatorChar) +
                    File.separator + directory
                current = current.children.getOrPut(directory) {
                    MutableFolder(id = currentPath, name = directory)
                }
            }
            current.playlists.putIfAbsent(identity, playlist)
        }

        val looseSorted = sortPlaylists(loose.values)
        return Result(
            folders = root.children.values.map(::freeze).sortedWith(folderOrder),
            ungroupedPlaylists = if (groupOtherLocations) emptyList() else looseSorted,
            otherLocations = if (groupOtherLocations && looseSorted.isNotEmpty()) {
                Folder(
                    id = OTHER_LOCATIONS_ID,
                    name = OTHER_LOCATIONS,
                    children = emptyList(),
                    playlists = looseSorted,
                    virtual = true
                )
            } else null
        )
    }

    private class MutableFolder(val id: String, val name: String) {
        val children = linkedMapOf<String, MutableFolder>()
        val playlists = linkedMapOf<String, Playlist>()
    }

    private val folderOrder = compareBy<Folder>({ it.name.lowercase() }, { it.name }, { it.id })
    private val playlistOrder = compareBy<Playlist>({ it.name.lowercase() }, { it.name }, { it.path })

    private fun freeze(folder: MutableFolder): Folder = Folder(
        id = folder.id,
        name = folder.name,
        children = folder.children.values.map(::freeze).sortedWith(folderOrder),
        playlists = sortPlaylists(folder.playlists.values)
    )

    private fun sortPlaylists(playlists: Collection<Playlist>): List<Playlist> =
        playlists.sortedWith(playlistOrder)

    private fun normalizeFilePath(rawPath: String): String? {
        val source = rawPath.trim()
        if (source.isEmpty() || source.contains("://")) return null
        return runCatching {
            val file = File(source)
            if (!file.isAbsolute) null else file.canonicalPath
        }.getOrNull()
    }

    /** A slash-boundary check prevents e.g. Playlists2 from matching Playlists. */
    private fun relativeToRoot(path: String, root: String): String? {
        if (path == root) return ""
        val prefix = root.trimEnd(File.separatorChar) + File.separator
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }
}
