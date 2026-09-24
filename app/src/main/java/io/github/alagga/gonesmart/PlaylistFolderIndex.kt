package io.github.alagga.gonesmart

import java.io.File

/**
 * Read-only folder model built from GMMP's native playlist paths.
 * The caller supplies the actual main directory; no path is hardcoded.
 * Neither GMMP's database nor any playlist file is changed here.
 */
internal object PlaylistFolderIndex {
    const val OTHER_LOCATIONS = "Other Locations"
    const val OTHER_LOCATIONS_ID = "virtual:other-locations"

    enum class Location { MAIN_SUBFOLDER, MAIN_ROOT, EXTERNAL }

    data class Playlist(
        val path: String,
        val name: String,
        val location: Location
    )

    data class Folder(
        val id: String,
        val name: String,
        val children: List<Folder>,
        val playlists: List<Playlist>,
        val virtual: Boolean = false
    )

    data class Result(
        val folders: List<Folder>,
        val ungroupedPlaylists: List<Playlist>,
        val otherLocations: Folder?
    ) {
        /** Physical folders always precede the optional virtual folder. */
        val topLevelFolders: List<Folder>
            get() = folders + listOfNotNull(otherLocations)
    }

    /** Compatibility for the old, combined grouping option. */
    fun build(
        nativePlaylistPaths: Collection<String>,
        mainPlaylistDirectory: String,
        groupOtherLocations: Boolean
    ): Result = build(
        nativePlaylistPaths = nativePlaylistPaths,
        mainPlaylistDirectory = mainPlaylistDirectory,
        groupExternalLocations = groupOtherLocations,
        groupRootPlaylists = groupOtherLocations
    )

    /**
     * Two independent grouping options. Empty directories can also be
     * supplied by the future physical-directory scanner.
     */
    fun build(
        nativePlaylistPaths: Collection<String>,
        mainPlaylistDirectory: String,
        groupExternalLocations: Boolean,
        groupRootPlaylists: Boolean,
        physicalDirectoryPaths: Collection<String> = emptyList()
    ): Result {
        val mainRoot = normalizeFilePath(mainPlaylistDirectory)
            ?: throw IllegalArgumentException("Main playlist directory must be absolute")
        val root = MutableFolder(id = mainRoot, name = "")
        val ungrouped = linkedMapOf<String, Playlist>()
        val grouped = linkedMapOf<String, Playlist>()

        fun ensureFolder(relativePath: String): MutableFolder {
            var current = root
            var currentPath = mainRoot
            for (directory in relativePath.split(File.separatorChar).filter(String::isNotBlank)) {
                currentPath = currentPath.trimEnd(File.separatorChar) +
                    File.separator + directory
                current = current.children.getOrPut(directory) {
                    MutableFolder(id = currentPath, name = directory)
                }
            }
            return current
        }

        // Native playlist models cannot describe empty folders.
        for (physicalPath in physicalDirectoryPaths) {
            val normalized = normalizeFilePath(physicalPath) ?: continue
            val relative = relativeToRoot(normalized, mainRoot) ?: continue
            if (relative.isNotBlank()) ensureFolder(relative)
        }

        val observedPlaylistIds = hashSetOf<String>()
        for (nativePath in nativePlaylistPaths) {
            val rawPath = nativePath.trim()
            if (rawPath.isEmpty()) continue

            // content:// and other URIs are external to the physical tree.
            val normalized = normalizeFilePath(rawPath)
            val identity = normalized ?: rawPath
            if (!observedPlaylistIds.add(identity)) continue
            val relative = normalized?.let { relativeToRoot(it, mainRoot) }
            val location = when {
                relative == null -> Location.EXTERNAL
                relative.contains(File.separatorChar) -> Location.MAIN_SUBFOLDER
                else -> Location.MAIN_ROOT
            }
            val playlist = Playlist(
                path = rawPath,
                name = rawPath.substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { rawPath },
                location = location
            )
            when (location) {
                Location.MAIN_SUBFOLDER -> {
                    val parent = relative!!.substringBeforeLast(File.separatorChar)
                    ensureFolder(parent).playlists[identity] = playlist
                }
                Location.MAIN_ROOT -> {
                    (if (groupRootPlaylists) grouped else ungrouped)[identity] = playlist
                }
                Location.EXTERNAL -> {
                    (if (groupExternalLocations) grouped else ungrouped)[identity] = playlist
                }
            }
        }

        val groupedSorted = sortPlaylists(grouped.values)
        return Result(
            folders = root.children.values.map(::freeze).sortedWith(folderOrder),
            ungroupedPlaylists = sortPlaylists(ungrouped.values),
            // Root creation moves into Other Locations when root grouping
            // is enabled. Keep this virtual destination navigable even
            // before the very first root playlist exists.
            otherLocations = if (groupRootPlaylists || groupedSorted.isNotEmpty()) {
                Folder(
                    id = OTHER_LOCATIONS_ID,
                    name = OTHER_LOCATIONS,
                    children = emptyList(),
                    playlists = groupedSorted,
                    virtual = true
                )
            } else null
        )
    }

    private class MutableFolder(val id: String, val name: String) {
        val children = linkedMapOf<String, MutableFolder>()
        val playlists = linkedMapOf<String, Playlist>()
    }

    private val folderOrder =
        compareBy<Folder>({ it.name.lowercase() }, { it.name }, { it.id })
    private val playlistOrder =
        compareBy<Playlist>({ it.name.lowercase() }, { it.name }, { it.path })

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

    /** Do not confuse Playlists with Playlists2 or escaped ../ paths. */
    private fun relativeToRoot(path: String, root: String): String? {
        if (path == root) return ""
        val prefix = root.trimEnd(File.separatorChar) + File.separator
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }
}
