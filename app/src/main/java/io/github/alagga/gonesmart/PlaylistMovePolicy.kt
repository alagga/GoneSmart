package io.github.alagga.gonesmart

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Strict, side-effect-free preflight for one or multiple physical M3U moves.
 *
 * No playlist is moved by this class. A complete GMMP-native file+DB
 * transaction must be proved on the tested APK BEFORE a caller may commit
 * this plan. Never turn a passing preflight into a raw File.renameTo().
 */
internal object PlaylistMovePolicy {
    private const val MAX_SOURCES = 256
    private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

    enum class BlockReason {
        INVALID_ROOT, INVALID_DESTINATION, OUTSIDE_NATIVE_ROOT,
        INVALID_SOURCE, UNINDEXED_SOURCE, DUPLICATE_NATIVE_RECORD,
        TARGET_ALREADY_EXISTS, TARGET_COLLISION, SOURCE_CONTENT_UNKNOWN,
        UNRESOLVED_RELATIVE_TRACK, NO_MOVES
    }

    sealed class Result {
        data class Ready(val plan: Plan) : Result()
        data class Blocked(val reason: BlockReason) : Result()
    }

    data class SourceSnapshot(
        val source: File,
        val target: File,
        val contentHash: String,
        val length: Long,
        val relativeTrackCount: Int,
        val expectedRelativeTrackPaths: List<String>
    )

    data class Plan(
        val root: File,
        val destination: File,
        val entries: List<SourceSnapshot>,
        val alreadyAtDestination: Int
    ) {
        val count: Int get() = entries.size
    }

    /**
     * Canonical native root and exact, current native indexed paths are
     * mandatory. No external M3Us, directory moves, symlinks, ambiguous
     * duplicates, overwrites or potentially broken relative references.
     */
    fun prepare(
        nativeRoot: File,
        destination: File,
        selectedNativePaths: Collection<String>,
        currentNativePaths: Collection<String>
    ): Result {
        val root = runCatching { nativeRoot.canonicalFile }.getOrNull()
            ?: return Result.Blocked(BlockReason.INVALID_ROOT)
        if (!root.isDirectory) return Result.Blocked(BlockReason.INVALID_ROOT)
        val dest = runCatching { destination.canonicalFile }.getOrNull()
            ?: return Result.Blocked(BlockReason.INVALID_DESTINATION)
        if (!dest.isDirectory || !dest.canWrite() ||
            !withinRoot(root, dest, includeRoot = true)
        ) return Result.Blocked(BlockReason.INVALID_DESTINATION)

        val selected = selectedNativePaths.toList()
        if (selected.isEmpty() || selected.size > MAX_SOURCES) {
            return Result.Blocked(BlockReason.INVALID_SOURCE)
        }

        val nativeMultiplicity = hashMapOf<String, Int>()
        for (raw in currentNativePaths) {
            val file = validAbsolute(raw) ?: continue
            val path = file.canonicalPath
            nativeMultiplicity[path] = (nativeMultiplicity[path] ?: 0) + 1
        }
        val targetNames = hashSetOf<String>()
        val entries = arrayListOf<SourceSnapshot>()
        var skipped = 0
        for (raw in selected.distinct()) {
            val file = validAbsolute(raw)
                ?: return Result.Blocked(BlockReason.INVALID_SOURCE)
            val source = runCatching { file.canonicalFile }.getOrNull()
                ?: return Result.Blocked(BlockReason.INVALID_SOURCE)
            if (!withinRoot(root, source, includeRoot = false)) {
                return Result.Blocked(BlockReason.OUTSIDE_NATIVE_ROOT)
            }
            if (!source.isFile ||
                Files.isSymbolicLink(file.toPath()) ||
                !isPlaylist(source.name) ||
                source.length() > MAX_PLAYLIST_BYTES ||
                !source.canRead()
            ) return Result.Blocked(BlockReason.INVALID_SOURCE)
            when (nativeMultiplicity[source.path]) {
                1 -> Unit
                null, 0 -> return Result.Blocked(BlockReason.UNINDEXED_SOURCE)
                else -> return Result.Blocked(BlockReason.DUPLICATE_NATIVE_RECORD)
            }
            val target = File(dest, source.name)
            if (target.canonicalFile.parentFile != dest) {
                return Result.Blocked(BlockReason.INVALID_DESTINATION)
            }
            if (source.parentFile == dest) {
                skipped++
                continue
            }
            // Android/scoped storage can be case-insensitive even when the
            // current test filesystem is not.
            if (!targetNames.add(target.name.lowercase(java.util.Locale.ROOT))) {
                return Result.Blocked(BlockReason.TARGET_COLLISION)
            }
            if (target.exists() ||
                dest.listFiles()?.any {
                    it.name.equals(target.name, ignoreCase = true)
                } == true
            ) return Result.Blocked(BlockReason.TARGET_ALREADY_EXISTS)

            val content = runCatching { source.readBytes() }.getOrNull()
                ?: return Result.Blocked(BlockReason.SOURCE_CONTENT_UNKNOWN)
            if (content.size > MAX_PLAYLIST_BYTES) {
                return Result.Blocked(BlockReason.SOURCE_CONTENT_UNKNOWN)
            }
            val tracks = relativeTrackPaths(source.parentFile, content)
                ?: return Result.Blocked(
                    BlockReason.UNRESOLVED_RELATIVE_TRACK
                )
            entries += SourceSnapshot(
                source = source,
                target = target,
                contentHash = digest(content),
                length = content.size.toLong(),
                relativeTrackCount = tracks.size,
                expectedRelativeTrackPaths = tracks
            )
        }
        if (entries.isEmpty()) return Result.Blocked(BlockReason.NO_MOVES)
        return Result.Ready(Plan(root, dest, entries, skipped))
    }

    /**
     * Revalidate immediately before GMMP's own native transaction starts.
     * Neither this check nor index observation mutates the filesystem.
     */
    fun sourcesStillMatch(plan: Plan, currentNativePaths: Collection<String>): Boolean {
        val paths = currentNativePaths.mapNotNull { raw ->
            validAbsolute(raw)?.let {
                runCatching { it.canonicalPath }.getOrNull()
            }
        }.toSet()
        return plan.destination.isDirectory && plan.destination.canWrite() &&
            plan.entries.all { entry ->
                entry.source.path in paths &&
                    entry.source.isFile && !entry.target.exists() &&
                    entry.source.length() == entry.length &&
                    runCatching {
                        digest(entry.source.readBytes()) == entry.contentHash
                    }.getOrDefault(false)
            }
    }

    fun nativeDestinationIndexed(
        plan: Plan, currentNativePaths: Collection<String>
    ): Boolean {
        val paths = currentNativePaths.mapNotNull { raw ->
            validAbsolute(raw)?.let {
                runCatching { it.canonicalPath }.getOrNull()
            }
        }.toSet()
        return plan.entries.all { it.target.isFile && it.target.path in paths }
    }

    fun nativeOriginalsGone(
        plan: Plan, currentNativePaths: Collection<String>
    ): Boolean {
        val paths = currentNativePaths.mapNotNull { raw ->
            validAbsolute(raw)?.let {
                runCatching { it.canonicalPath }.getOrNull()
            }
        }.toSet()
        return plan.entries.all { !it.source.exists() && it.source.path !in paths }
    }

    private fun isPlaylist(name: String): Boolean =
        name.endsWith(".m3u", ignoreCase = true) ||
            name.endsWith(".m3u8", ignoreCase = true)

    private fun validAbsolute(raw: String): File? {
        if (raw.isBlank() || "://" in raw) return null
        return File(raw).takeIf { it.isAbsolute }
    }

    private fun withinRoot(root: File, child: File, includeRoot: Boolean): Boolean =
        (includeRoot && child.path == root.path) ||
            child.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)

    /** Return null instead of guessing any non-standard M3U interpretation. */
    private fun relativeTrackPaths(parent: File, bytes: ByteArray): List<String>? {
        val offset = if (bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte()
        ) 3 else 0
        val text = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        }.getOrNull() ?: return null
        val result = arrayListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            if (line.contains("\u0000") ||
                line.startsWith("content:", ignoreCase = true) ||
                line.startsWith("http:", ignoreCase = true) ||
                line.startsWith("https:", ignoreCase = true) ||
                line.startsWith("file:", ignoreCase = true) ||
                line.contains("\\")
            ) return null
            val file = File(line)
            if (file.isAbsolute) continue
            val resolved = runCatching { File(parent, line).canonicalFile }
                .getOrNull() ?: return null
            if (!resolved.isFile || !resolved.canRead()) return null
            result += resolved.path
        }
        return result
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
