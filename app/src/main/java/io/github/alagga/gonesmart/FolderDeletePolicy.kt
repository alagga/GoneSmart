package io.github.alagga.gonesmart

import java.io.File

/**
 * Never recursively delete arbitrary user storage. The only deletion
 * request is passed to GMMP's ORIGINAL py0.b; the only remaining local
 * cleanup is File.delete() on verified already-empty directories AFTER
 * GMMP removes every indexed playlist file.
 */
internal object FolderDeletePolicy {
    data class Plan(
        val root: File,
        val folder: File,
        val nativePlaylistFiles: List<File>,
        val nestedDirectories: List<File>
    )

    fun prepare(
        nativeRoot: File,
        selectedFolder: File,
        nativePlaylistPaths: Collection<String>,
        maximumEntries: Int = 4096
    ): Plan? {
        val root = runCatching { nativeRoot.canonicalFile }.getOrNull()
            ?: return null
        val folder = runCatching { selectedFolder.canonicalFile }.getOrNull()
            ?: return null
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!folder.path.startsWith(rootPrefix) ||
            !folder.isDirectory || !folder.canWrite()
        ) return null
        val prefix = folder.path.trimEnd(File.separatorChar) + File.separator
        val nativeFiles = nativePlaylistPaths.mapNotNull { path ->
            if (!File(path).isAbsolute || "://" in path) return@mapNotNull null
            runCatching { File(path).canonicalFile }.getOrNull()
        }.filter { it.path.startsWith(prefix) }.distinctBy { it.path }
        if (nativeFiles.any { !it.isFile }) return null
        val nativePaths = nativeFiles.map(File::getPath).toSet()
        val observed = hashSetOf<String>()
        val directories = arrayListOf<File>()
        var entries = 0
        for (entry in folder.walkTopDown()) {
            if (++entries > maximumEntries) return null
            val resolved = runCatching { entry.canonicalFile }.getOrNull()
                ?: return null
            if (resolved.path != folder.path &&
                !resolved.path.startsWith(prefix)
            ) return null
            // A symlink or a repeated physical node must not escape the
            // native-model whitelist, even when it points inside root.
            if (!observed.add(resolved.path)) return null
            if (entry.isDirectory) {
                directories.add(entry)
            } else if (!entry.isFile ||
                resolved.path !in nativePaths
            ) return null
        }
        if (!nativePaths.all(observed::contains)) return null
        return Plan(root, folder, nativeFiles, directories)
    }

    fun nativeRemovalComplete(
        plan: Plan,
        currentNativePaths: Collection<String>
    ): Boolean {
        if (plan.nativePlaylistFiles.any { it.exists() }) return false
        val target = plan.nativePlaylistFiles
            .map(File::getCanonicalPath).toHashSet()
        return currentNativePaths.none { path ->
            runCatching { File(path).canonicalPath }.getOrNull() in target
        }
    }

    /** No recursive delete API: only dirs already emptied by native GMMP. */
    fun removeEmptyDirectories(plan: Plan): Boolean {
        for (directory in plan.nestedDirectories.sortedByDescending {
            it.path.length
        }) {
            if (directory.exists() && !directory.delete()) return false
        }
        return !plan.folder.exists()
    }
}
