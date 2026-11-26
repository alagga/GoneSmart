package io.github.alagga.gonesmart

import java.io.File
import java.util.LinkedHashMap

/**
 * Small, worker-owned, short-lived cache of *native* Smart Playlist file
 * models. The native ws4 XML reader remains the sole source of truth.
 *
 * Every lookup checks the current file's length + modification timestamp and
 * the GMMP model type. Sorted order, native adapter and visible rows are NOT
 * cached, so a native sort change or tab reconstruction still executes the
 * normal GMMP pipeline. This cache never persists a listening history or
 * holds entire native RecyclerViews.
 *
 * Access is serialized through SmartPlaylistFolderController's one worker.
 */
internal class NativeSmartModelCache(
    private val maxEntries: Int = 256,
    private val maxAgeMs: Long = 15_000L
) {
    private data class Entry(
        val length: Long,
        val modifiedMs: Long,
        val loadedAtMs: Long,
        val model: Any
    )

    private val entries = object : LinkedHashMap<String, Entry>(
        32, .75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Entry>?
        ): Boolean = size > maxEntries
    }

    fun getOrLoad(
        file: File,
        modelType: Class<*>,
        nowMs: Long,
        loadNative: () -> Any?
    ): Any? {
        if (!file.isFile) {
            entries.remove(file.path)
            return null
        }
        val length = file.length()
        val modifiedMs = file.lastModified()
        val previous = entries[file.path]
        if (previous != null &&
            previous.length == length &&
            previous.modifiedMs == modifiedMs &&
            modelType.isInstance(previous.model) &&
            nowMs >= previous.loadedAtMs &&
            nowMs - previous.loadedAtMs < maxAgeMs
        ) {
            return previous.model
        }
        entries.remove(file.path)
        val model = loadNative() ?: return null
        if (modelType.isInstance(model)) {
            entries[file.path] = Entry(length, modifiedMs, nowMs, model)
        }
        return model
    }
}
