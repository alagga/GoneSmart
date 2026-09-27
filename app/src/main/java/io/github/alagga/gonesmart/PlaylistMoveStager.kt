package io.github.alagga.gonesmart

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Durable, private BEFORE-delete backup. A native GMMP delete dialog may be
 * cancelled; no source is touched while we stage. Native GMMP owns removing
 * its indexed old files, and t6.f owns registration of the new M3Us.
 *
 * Relative paths are resolved against the OLD parent before relocation,
 * matching the absolute-path result of editing/saving in GMMP. Preserve all
 * EXTINF/comment metadata and existing line endings. Unknown formats fail.
 */
internal object PlaylistMoveStager {
    data class Entry(
        val source: File,
        val target: File,
        val original: File,
        val normalized: File,
        val normalizedHash: String
    )

    data class Batch(
        val directory: File,
        val root: File,
        val entries: List<Entry>,
        val createdAt: Long
    )

    fun stage(plan: PlaylistMovePolicy.Plan, filesDir: File): Batch {
        val base = File(filesDir, "gonesmart_playlist_moves")
        check(base.isDirectory || base.mkdirs())
        val folder = File(base, UUID.randomUUID().toString())
        check(folder.mkdir())
        try {
            val entries = plan.entries.mapIndexed { index, snapshot ->
                val source = snapshot.source.canonicalFile
                check(snapshot.target.canonicalFile.parentFile == plan.destination)
                val originalBytes = source.readBytes()
                check(originalBytes.size.toLong() == snapshot.length)
                check(hash(originalBytes) == snapshot.contentHash)
                val transformed = absoluteTrackPaths(
                    source, originalBytes, snapshot.expectedRelativeTrackPaths
                )
                val original = File(folder, "original_$index.bin")
                val normalized = File(folder, "normalized_$index.bin")
                durableWrite(original, originalBytes)
                durableWrite(normalized, transformed)
                Entry(source, snapshot.target, original, normalized, hash(transformed))
            }
            val now = System.currentTimeMillis()
            val data = Properties().apply {
                setProperty("root", plan.root.canonicalPath)
                setProperty("count", entries.size.toString())
                setProperty("createdAt", now.toString())
                for ((i, entry) in entries.withIndex()) {
                    setProperty("source.$i", entry.source.canonicalPath)
                    setProperty("target.$i", entry.target.canonicalPath)
                    setProperty("hash.$i", entry.normalizedHash)
                }
            }
            val manifest = File(folder, "manifest.properties")
            FileOutputStream(manifest).use {
                data.store(it, "GoneSmart pending playlist move; do not delete")
                it.fd.sync()
            }
            return Batch(folder, plan.root, entries, now)
        } catch (failure: Throwable) {
            folder.deleteRecursively()
            throw failure
        }
    }

    fun recover(filesDir: File, nativeRoot: File): List<Batch> {
        val canonicalRoot = nativeRoot.canonicalFile
        val base = File(filesDir, "gonesmart_playlist_moves")
        return base.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            runCatching {
                val props = Properties().apply {
                    File(dir, "manifest.properties").inputStream().use { stream ->
                        load(stream)
                    }
                }
                val root = File(props.getProperty("root")).canonicalFile
                require(root == canonicalRoot && root.isDirectory)
                val count = props.getProperty("count").toInt()
                require(count in 1..256)
                val entries = (0 until count).map { index ->
                    val source = File(props.getProperty("source.$index")).canonicalFile
                    val target = File(props.getProperty("target.$index")).canonicalFile
                    require(within(root, source) && within(root, target))
                    val original = File(dir, "original_$index.bin")
                    val normalized = File(dir, "normalized_$index.bin")
                    require(original.isFile && normalized.isFile)
                    val digest = props.getProperty("hash.$index")
                    require(hash(normalized.readBytes()) == digest)
                    Entry(source, target, original, normalized, digest)
                }
                Batch(dir, root, entries, props.getProperty("createdAt").toLong())
            }.getOrNull()
        }
    }

    /**
     * The destination file is never overwritten. The private staged backup
     * survives interrupted native scans or process death until indexed.
     */
    fun commit(batch: Batch): Boolean {
        if (batch.entries.any {
            !it.target.parentFile.isDirectory || it.target.exists() ||
                it.target.parentFile.listFiles().orEmpty().any { file ->
                    file.name.equals(it.target.name, ignoreCase = true)
                }
        }) return false
        val written = ArrayList<File>()
        try {
            for (entry in batch.entries) {
                val temp = File.createTempFile(
                    ".gonesmart_move_", ".pending", entry.target.parentFile
                )
                written += temp
                durableWrite(temp, entry.normalized.readBytes())
                check(hash(temp.readBytes()) == entry.normalizedHash)
                check(temp.renameTo(entry.target)) { "Atomic move of staged M3U failed" }
                written.remove(temp)
                written += entry.target
            }
            return true
        } catch (_: Throwable) {
            // Private originals and normalized copies remain intact. Only our
            // newly created output is removed; never touch a preexisting file.
            written.asReversed().forEach { runCatching { it.delete() } }
            return false
        }
    }

    /**
     * Recover from a partially successful original GMMP bulk deletion or a
     * destination collision discovered AFTER its confirmation. Do not alter
     * originals that still exist, especially if they changed independently.
     * Retain every private backup until GMMP reindexes all restored paths.
     */
    fun restoreMissingOriginals(batch: Batch): Boolean {
        val originals = runCatching {
            batch.entries.associateWith { it.original.readBytes() }
        }.getOrNull() ?: return false
        if (batch.entries.any { entry ->
            val source = entry.source
            (source.exists() && (
                !source.isFile ||
                    runCatching {
                        hash(source.readBytes()) !=
                            hash(requireNotNull(originals[entry]))
                    }.getOrDefault(true)
            )) || !source.parentFile.isDirectory
        }) return false
        try {
            for (entry in batch.entries) {
                if (entry.source.exists()) continue
                val backup = requireNotNull(originals[entry])
                val temp = File.createTempFile(
                    ".gonesmart_restore_", ".pending",
                    entry.source.parentFile
                )
                try {
                    durableWrite(temp, backup)
                    check(hash(temp.readBytes()) == hash(backup))
                    check(temp.renameTo(entry.source))
                } finally {
                    if (temp.exists()) temp.delete()
                }
            }
            return true
        } catch (_: Throwable) {
            // A partially completed restore is recoverable from the still
            // intact stage. Never delete any already restored source here.
            return false
        }
    }

    fun finish(batch: Batch) {
        batch.directory.deleteRecursively()
    }

    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun durableWrite(file: File, bytes: ByteArray) {
        FileOutputStream(file).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    private fun within(root: File, child: File): Boolean =
        child.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)

    fun absoluteTrackPaths(
        source: File, bytes: ByteArray, expectedRelativePaths: List<String>
    ): ByteArray {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val out = StringBuilder(text.length + 128)
        var offset = 0
        var relative = 0
        while (offset < text.length) {
            val end = text.indexOf('\n', offset)
            val endIndex = if (end == -1) text.length else end
            val hasCr = endIndex > offset && text[endIndex - 1] == '\r'
            val line = text.substring(offset, endIndex - if (hasCr) 1 else 0)
            val trimmed = line.trim().removePrefix("\uFEFF")
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val path = File(trimmed)
                if (!path.isAbsolute) {
                    require(relative < expectedRelativePaths.size)
                    val resolved = File(source.parentFile, trimmed).canonicalFile
                    require(resolved.isFile &&
                        resolved.path == expectedRelativePaths[relative++])
                    out.append(resolved.path)
                } else out.append(line)
            } else out.append(line)
            if (hasCr) out.append('\r')
            if (end != -1) out.append('\n')
            offset = if (end == -1) text.length else end + 1
        }
        require(relative == expectedRelativePaths.size)
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }
}
