package io.github.alagga.gonesmart

import android.database.Cursor
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Read-only enumeration of GMMP's indexed normal playlist files.
 *
 * playlist_file_table ownership is already established by GMMP's generated
 * Room adapters. We deliberately discover the path/URI column from row values
 * once per live cache instead of depending on an R8 field or guessed column
 * name. No playlist DB row is ever written by GoneSmart.
 */
internal class GmmpPlaylistCatalogReader {
    companion object {
        private const val TAG = "GoneSmart"
        private const val CACHE_MAX_AGE_MS = 2L * 60L * 1000L
        private const val SQL = "SELECT * FROM playlist_file_table"
    }

    @Volatile private var cachedPaths: List<String>? = null
    @Volatile private var cachedAtElapsedMs: Long = 0L
    @Volatile private var reportedColumn: String? = null

    fun read(autoDjInstance: Any, forceRefresh: Boolean = false): List<String> {
        val now = SystemClock.elapsedRealtime()
        cachedPaths?.let { paths ->
            if (!forceRefresh && now - cachedAtElapsedMs <= CACHE_MAX_AGE_MS) {
                return paths
            }
        }

        synchronized(this) {
            val synchronizedNow = SystemClock.elapsedRealtime()
            cachedPaths?.let { paths ->
                if (!forceRefresh &&
                    synchronizedNow - cachedAtElapsedMs <= CACHE_MAX_AGE_MS
                ) return paths
            }

            val paths = runCatching {
                GmmpReadOnlySql.query(autoDjInstance, SQL) { cursor ->
                    readPathColumn(cursor)
                }
            }.onFailure {
                Log.w(TAG, "PLAYLIST AFFINITY | normal playlist catalog unavailable", it)
            }.getOrDefault(emptyList())

            cachedPaths = paths
            cachedAtElapsedMs = synchronizedNow
            return paths
        }
    }

    private fun readPathColumn(cursor: Cursor): List<String> {
        if (cursor.columnCount <= 0) return emptyList()
        val values = Array(cursor.columnCount) { mutableListOf<String>() }

        while (cursor.moveToNext()) {
            for (column in 0 until cursor.columnCount) {
                val value = runCatching { cursor.getString(column) }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                if (value.isNotBlank()) values[column] += value
            }
        }

        data class Candidate(
            val column: Int,
            val playlistRefs: Int,
            val pathLike: Int
        )

        val candidates = values.mapIndexed { index, columnValues ->
            Candidate(
                column = index,
                playlistRefs = columnValues.count(::isPlaylistReference),
                pathLike = columnValues.count { isPlaylistReference(it) && isPathLike(it) }
            )
        }.filter { it.playlistRefs > 0 && it.pathLike > 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.pathLike }
                    .thenByDescending { it.playlistRefs }
            )

        val best = candidates.firstOrNull() ?: return emptyList()
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null &&
            best.pathLike == runnerUp.pathLike &&
            best.playlistRefs == runnerUp.playlistRefs
        ) {
            Log.w(TAG, "PLAYLIST AFFINITY | playlist path column is ambiguous")
            return emptyList()
        }

        val columnName = cursor.getColumnName(best.column)
        if (reportedColumn != columnName) {
            reportedColumn = columnName
            Log.i(
                TAG,
                "PLAYLIST AFFINITY | native catalog path column=$columnName | " +
                    "rows=${values[best.column].size}"
            )
        }

        return values[best.column]
            .asSequence()
            .filter(::isPlaylistReference)
            .mapNotNull(::normalizePlaylistReference)
            .distinct()
            .toList()
    }

    private fun normalizePlaylistReference(value: String): String? {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("file:", ignoreCase = true) ->
                runCatching { File(URI(trimmed)).absolutePath }.getOrNull()
            trimmed.startsWith("content:", ignoreCase = true) -> trimmed
            File(trimmed).isAbsolute -> trimmed
            else -> null
        }
    }

    private fun isPathLike(value: String): Boolean =
        value.startsWith("/") ||
            value.startsWith("file:", ignoreCase = true) ||
            value.startsWith("content:", ignoreCase = true)

    private fun isPlaylistReference(value: String): Boolean {
        val clean = value.substringBefore('?').substringBefore('#')
            .lowercase(Locale.ROOT)
        return clean.endsWith(".m3u") || clean.endsWith(".m3u8") ||
            clean.endsWith(".pls") || clean.endsWith(".wpl")
    }
}
