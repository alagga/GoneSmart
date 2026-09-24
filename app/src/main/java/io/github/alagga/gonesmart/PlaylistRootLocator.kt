package io.github.alagga.gonesmart

import java.io.File

/**
 * Conservative, read-only root discovery for the experimental folder
 * browser. A future writable version must use GMMP's actual directory
 * preference rather than this inference.
 */
internal object PlaylistRootLocator {
    fun infer(
        playlistPaths: Collection<String>,
        primaryStorageDirectory: String
    ): String? {
        val primary = runCatching {
            val parent = File(primaryStorageDirectory)
            if (!parent.isAbsolute) return null
            File(parent, "gmmp/playlists").canonicalPath
        }.getOrNull() ?: return null
        val prefix = primary.trimEnd(File.separatorChar) + File.separator
        return primary.takeIf { root ->
            playlistPaths.any { source ->
                if (source.contains("://")) return@any false
                val path = runCatching {
                    val file = File(source)
                    if (file.isAbsolute) file.canonicalPath else null
                }.getOrNull()
                path != null && path.startsWith(prefix) &&
                    path != root
            }
        }
    }
}
