package io.github.alagga.gonesmart

import java.io.File

/**
 * Pure path rules for Smart-Playlist folders.
 *
 * The feature only owns physical directories BELOW GMMP's configured
 * Smart-Playlist root. Existing files are never moved implicitly.
 */
internal object SmartPlaylistFolderPolicy {
    fun isInsideRoot(rootPath: String, candidatePath: String): Boolean {
        val root = canonical(rootPath) ?: return false
        val candidate = canonical(candidatePath) ?: return false
        if (candidate == root) return true
        return candidate.startsWith(
            root.trimEnd(File.separatorChar) + File.separator
        )
    }

    fun redirectNewSave(
        rootPath: String,
        currentFolderPath: String,
        originalDestinationPath: String,
        originalExists: Boolean
    ): String? {
        if (originalExists) return null
        val root = canonical(rootPath) ?: return null
        val current = canonical(currentFolderPath) ?: return null
        val original = canonical(originalDestinationPath) ?: return null
        if (current == root || !isInsideRoot(root, current)) return null
        val originalFile = File(original)
        val originalParent = canonical(originalFile.parent ?: return null)
            ?: return null
        if (originalParent != root) return null
        val target = File(current, originalFile.name)
        return canonical(target.path)
    }

    private fun canonical(path: String): String? = runCatching {
        File(path).canonicalPath
    }.getOrNull()
}
