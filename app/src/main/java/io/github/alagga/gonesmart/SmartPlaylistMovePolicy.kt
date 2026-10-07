package io.github.alagga.gonesmart

import java.io.File

/**
 * Pure path/collision validation for physical Smart-Playlist moves.
 * Native-link safety is checked separately with GMMP's parsed ws4 rule tree
 * before any file is moved.
 */
internal object SmartPlaylistMovePolicy {
    data class Move(
        val source: File,
        val target: File
    )

    sealed interface Result {
        data class Ready(val moves: List<Move>) : Result
        data class Blocked(val reason: String) : Result
    }

    fun prepare(
        root: File,
        destination: File,
        sources: List<File>
    ): Result {
        val canonicalRoot = canonical(root)
        val canonicalDestination = canonical(destination)
        if (!canonicalRoot.isDirectory ||
            !canonicalDestination.isDirectory ||
            !inside(canonicalRoot, canonicalDestination)
        ) {
            return Result.Blocked("Destination is outside Smart-Playlist root")
        }

        val unique = sources
            .map(::canonical)
            .distinctBy { it.path }
        if (unique.isEmpty()) {
            return Result.Blocked("No Smart-Playlists selected")
        }

        val moves = arrayListOf<Move>()
        val targets = hashSetOf<String>()
        for (source in unique) {
            if (!source.isFile ||
                !source.extension.equals("spl", ignoreCase = true) ||
                !inside(canonicalRoot, source)
            ) {
                return Result.Blocked("Selection is not a Smart-Playlist file")
            }
            if (canonical(source.parentFile) == canonicalDestination) {
                continue
            }
            val target = canonical(File(canonicalDestination, source.name))
            if (!inside(canonicalRoot, target)) {
                return Result.Blocked("Target escapes Smart-Playlist root")
            }
            if (target.exists()) {
                return Result.Blocked("A Smart-Playlist with this name already exists")
            }
            if (!targets.add(target.path)) {
                return Result.Blocked("Selected Smart-Playlists collide at destination")
            }
            moves += Move(source, target)
        }

        return if (moves.isEmpty()) {
            Result.Blocked("Selection is already in this destination")
        } else {
            Result.Ready(moves)
        }
    }

    private fun canonical(file: File): File =
        runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }

    private fun inside(root: File, child: File): Boolean {
        if (root.path == child.path) return true
        val prefix = root.path.trimEnd(File.separatorChar) + File.separator
        return child.path.startsWith(prefix)
    }
}
