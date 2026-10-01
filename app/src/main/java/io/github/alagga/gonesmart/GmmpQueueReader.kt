package io.github.alagga.gonesmart

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Read-only GMMP queue adapter.
 *
 * 4.2.0 used the concrete ex3 -> QueueDao path. 4.2.1 moved qr.q to a
 * generated Room DAO, so a second path reads queue_table through the same
 * already-open read-only Cursor bridge as the library reader. The current
 * playback row is resolved only from structurally validated integer state;
 * ambiguous candidates fail closed and leave native Auto-DJ untouched.
 */
class GmmpQueueReader {

    companion object {
        private const val TAG = "GoneSmart"
        private const val CURSOR_QUERY =
            """
            SELECT queue_table.queue_position AS queue_position,
                   queue_table.queue_shuffle_position AS shuffle_position,
                   queue_table.queue_id AS queue_id,
                   tracks.song_id AS song_id,
                   tracks.track_name AS track_name,
                   tracks.track_uri AS track_uri,
                   GROUP_CONCAT(DISTINCT artists.artist) AS artist
              FROM queue_table
              INNER JOIN tracks
                      ON tracks.song_id = queue_table.queue_track_id
              LEFT JOIN artist_tracks
                     ON artist_tracks.song_id = tracks.song_id
              LEFT JOIN artists
                     ON artists.artist_id = artist_tracks.artist_id
             GROUP BY queue_table.queue_id
             ORDER BY queue_table.queue_position
            """
    }

    private enum class OrderKind { QUEUE, SHUFFLE }

    private data class CursorRow(
        val queuePosition: Int,
        val shufflePosition: Int,
        val queueEntryId: Long,
        val track: TrackInfo
    )

    private data class CurrentMarker(
        val rowIndex: Int,
        val orderKind: OrderKind,
        val source: String
    )

    private val reportedCursorBindings =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun read(autoDjInstance: Any): QueueContext? {
        readLegacy(autoDjInstance)?.let { return it }
        return readThroughCursor(autoDjInstance)
    }

    private fun readLegacy(autoDjInstance: Any): QueueContext? =
        runCatching {
            val queue = readObjectField(autoDjInstance, "q")
                ?: return@runCatching null
            val trackDao = readObjectField(autoDjInstance, "r")
                ?: return@runCatching null
            val currentQueuePosition =
                invokeNoArgMethod(queue, "D") as? Int
                    ?: return@runCatching null
            val queueDao = readObjectField(queue, "r")
                ?: return@runCatching null
            val rawQueueItems =
                invokeNoArgMethod(queueDao, "H1") as? List<*>
                    ?: return@runCatching null
            val trackLookupMethod = findMethod(
                trackDao.javaClass,
                "R1",
                java.lang.Long.TYPE
            ) ?: return@runCatching null
            trackLookupMethod.isAccessible = true

            val sortedItems = rawQueueItems.mapNotNull { raw ->
                raw ?: return@mapNotNull null
                val queuePosition = readIntField(raw, "a")
                    ?: return@mapNotNull null
                val trackId = readLongField(raw, "b")
                    ?: return@mapNotNull null
                val shufflePosition = readIntField(raw, "c") ?: -1
                val queueEntryId = readLongField(raw, "d")
                    ?: return@mapNotNull null
                val state = when {
                    queuePosition < currentQueuePosition ->
                        QueueItemState.PAST
                    queuePosition == currentQueuePosition ->
                        QueueItemState.CURRENT
                    else -> QueueItemState.UPCOMING
                }
                QueueItemInfo(
                    orderIndex = -1,
                    queuePosition = queuePosition,
                    shufflePosition = shufflePosition,
                    queueEntryId = queueEntryId,
                    track = readTrack(
                        trackDao,
                        trackLookupMethod,
                        trackId
                    ),
                    state = state
                )
            }.sortedBy { it.queuePosition }
                .mapIndexed { index, item ->
                    item.copy(orderIndex = index)
                }

            if (sortedItems.none { it.state == QueueItemState.CURRENT }) {
                null
            } else {
                QueueContext(
                    currentQueuePosition = currentQueuePosition,
                    items = sortedItems
                )
            }
        }.getOrNull()

    private fun readThroughCursor(autoDjInstance: Any): QueueContext? =
        runCatching {
            val rows = GmmpReadOnlySql.query(
                autoDjInstance = autoDjInstance,
                sql = CURSOR_QUERY.trimIndent()
            ) { cursor ->
                fun column(name: String): Int =
                    cursor.getColumnIndex(name).also {
                        require(it >= 0) {
                            "GMMP queue Cursor missing column " + name
                        }
                    }
                val queuePosition = column("queue_position")
                val shufflePosition = column("shuffle_position")
                val queueId = column("queue_id")
                val songId = column("song_id")
                val title = column("track_name")
                val path = column("track_uri")
                val artist = column("artist")
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            CursorRow(
                                queuePosition = cursor.getInt(queuePosition),
                                shufflePosition =
                                    if (cursor.isNull(shufflePosition)) -1
                                    else cursor.getInt(shufflePosition),
                                queueEntryId = cursor.getLong(queueId),
                                track = TrackInfo(
                                    id = cursor.getLong(songId),
                                    title =
                                        if (cursor.isNull(title)) null
                                        else cursor.getString(title),
                                    artist =
                                        if (cursor.isNull(artist)) null
                                        else cursor.getString(artist),
                                    albumArtist = null,
                                    path =
                                        if (cursor.isNull(path)) null
                                        else cursor.getString(path)
                                )
                            )
                        )
                    }
                }
            }
            if (rows.isEmpty()) return@runCatching null

            val marker = resolveCurrentMarker(autoDjInstance, rows)
                ?: return@runCatching null
            val useShuffle =
                marker.orderKind == OrderKind.SHUFFLE &&
                    rows.all { it.shufflePosition >= 0 } &&
                    rows.map { it.shufflePosition }.distinct().size == rows.size
            val ordered = if (useShuffle) {
                rows.sortedBy { it.shufflePosition }
            } else {
                rows.sortedBy { it.queuePosition }
            }
            val currentEntry = rows[marker.rowIndex].queueEntryId
            val currentIndex = ordered.indexOfFirst {
                it.queueEntryId == currentEntry
            }
            if (currentIndex < 0) return@runCatching null

            val items = ordered.mapIndexed { index, row ->
                QueueItemInfo(
                    orderIndex = index,
                    queuePosition = row.queuePosition,
                    shufflePosition = row.shufflePosition,
                    queueEntryId = row.queueEntryId,
                    track = row.track,
                    state = when {
                        index < currentIndex -> QueueItemState.PAST
                        index == currentIndex -> QueueItemState.CURRENT
                        else -> QueueItemState.UPCOMING
                    }
                )
            }
            val currentQueuePosition = ordered[currentIndex].queuePosition
            val key =
                autoDjInstance.javaClass.name + "|" +
                    marker.source + "|" +
                    if (useShuffle) "shuffle" else "queue"
            if (reportedCursorBindings.add(key)) {
                Log.i(
                    TAG,
                    "GMMP QUEUE MAPPING | source=read-only Cursor" +
                        " | rows=" + rows.size +
                        " | currentResolver=" + marker.source +
                        " | order=" +
                        if (useShuffle) "shuffle" else "queue"
                )
            }
            QueueContext(
                currentQueuePosition = currentQueuePosition,
                items = items
            )
        }.onFailure {
            Log.w(
                TAG,
                "GMMP queue read-only Cursor fallback unavailable",
                it
            )
        }.getOrNull()

    private fun resolveCurrentMarker(
        autoDjInstance: Any,
        rows: List<CursorRow>
    ): CurrentMarker? {
        fun fromHost(host: Any, source: String): CurrentMarker? {
            val signals = integerSignals(host)
            return markerFromSignals(signals, rows, source)
        }

        readObjectField(autoDjInstance, "p")?.let { fast ->
            fromHost(fast, "direct-state:" + fast.javaClass.name)
                ?.let { return it }
        }

        val candidates = hierarchyFields(autoDjInstance.javaClass)
            .mapNotNull { field ->
                field.isAccessible = true
                val value = runCatching {
                    field.get(autoDjInstance)
                }.getOrNull() ?: return@mapNotNull null
                if (!isSignalHost(value)) return@mapNotNull null
                fromHost(
                    value,
                    "field:" + field.name + "->" + value.javaClass.name
                )
            }
        val uniqueRows = candidates.map { it.rowIndex }.distinct()
        if (uniqueRows.size != 1) {
            Log.w(
                TAG,
                "GMMP QUEUE CURRENT | structural marker unresolved" +
                    " | candidates=" +
                    candidates.joinToString(",") {
                        it.source + "#" + it.rowIndex
                    }.ifBlank { "none" }
            )
            return null
        }
        val matching = candidates.filter {
            it.rowIndex == uniqueRows.single()
        }
        return matching.firstOrNull {
            it.orderKind == OrderKind.SHUFFLE
        } ?: matching.firstOrNull()
    }

    private fun markerFromSignals(
        signals: List<Pair<String, Int>>,
        rows: List<CursorRow>,
        source: String
    ): CurrentMarker? {
        val markers = arrayListOf<CurrentMarker>()
        signals.forEach { (name, value) ->
            if (value < 0) return@forEach
            val queue = rows.indices.filter {
                rows[it].queuePosition == value
            }
            val shuffle = rows.indices.filter {
                rows[it].shufflePosition == value
            }
            if (shuffle.size == 1) {
                markers += CurrentMarker(
                    shuffle.single(),
                    OrderKind.SHUFFLE,
                    source + "." + name
                )
            }
            if (queue.size == 1) {
                markers += CurrentMarker(
                    queue.single(),
                    OrderKind.QUEUE,
                    source + "." + name
                )
            }
        }
        val uniqueRows = markers.map { it.rowIndex }.distinct()
        if (uniqueRows.size != 1) return null
        return markers.firstOrNull {
            it.rowIndex == uniqueRows.single() &&
                it.orderKind == OrderKind.SHUFFLE
        } ?: markers.firstOrNull {
            it.rowIndex == uniqueRows.single()
        }
    }

    private fun integerSignals(target: Any): List<Pair<String, Int>> {
        val result = arrayListOf<Pair<String, Int>>()
        hierarchyFields(target.javaClass).forEach { field ->
            if (field.type != Integer.TYPE &&
                field.type != Integer::class.java
            ) return@forEach
            val value = runCatching {
                field.isAccessible = true
                (field.get(target) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            result += "field:" + field.name to value
        }
        hierarchyMethods(target.javaClass).forEach { method ->
            if (method.parameterCount != 0 ||
                (method.returnType != Integer.TYPE &&
                    method.returnType != Integer::class.java) ||
                method.name == "hashCode"
            ) return@forEach
            val value = runCatching {
                method.isAccessible = true
                (method.invoke(target) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            result += "method:" + method.name to value
        }
        return result.distinct()
    }

    private fun isSignalHost(value: Any): Boolean {
        val name = value.javaClass.name
        if (name.startsWith("java.") ||
            name.startsWith("android.") ||
            name.startsWith("kotlin.") ||
            value is java.util.Collection<*> ||
            value is java.util.concurrent.Executor
        ) return false
        val fields = hierarchyFields(value.javaClass)
        if (fields.any {
                it.type.name ==
                    "gonemad.gmmp.data.database.GMDatabase" ||
                    generateSequence<Class<*>>(it.type) { c -> c.superclass }
                        .any { c -> c.name == "androidx.room.RoomDatabase" }
            }
        ) return false
        return true
    }

    private fun readTrack(
        trackDao: Any,
        trackLookupMethod: Method,
        trackId: Long
    ): TrackInfo {
        return try {
            val trackObject = trackLookupMethod.invoke(trackDao, trackId)
            if (trackObject == null) {
                return TrackInfo(trackId, null, null, null, null)
            }
            TrackInfo(
                id = readLongField(trackObject, "o") ?: trackId,
                title = readStringField(trackObject, "p"),
                artist = readStringField(trackObject, "u"),
                albumArtist = readStringField(trackObject, "H"),
                path = readStringField(trackObject, "r")
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Track lookup failed for trackId=" + trackId, t)
            TrackInfo(trackId, null, null, null, null)
        }
    }

    private fun readObjectField(target: Any, fieldName: String): Any? =
        runCatching {
            val field = findField(target.javaClass, fieldName)
                ?: return@runCatching null
            field.isAccessible = true
            field.get(target)
        }.getOrNull()

    private fun readIntField(target: Any, fieldName: String): Int? =
        runCatching {
            val field = findField(target.javaClass, fieldName)
                ?: return@runCatching null
            field.isAccessible = true
            (field.get(target) as? Number)?.toInt()
        }.getOrNull()

    private fun readLongField(target: Any, fieldName: String): Long? =
        runCatching {
            val field = findField(target.javaClass, fieldName)
                ?: return@runCatching null
            field.isAccessible = true
            (field.get(target) as? Number)?.toLong()
        }.getOrNull()

    private fun readStringField(target: Any, fieldName: String): String? =
        runCatching {
            val field = findField(target.javaClass, fieldName)
                ?: return@runCatching null
            field.isAccessible = true
            field.get(target) as? String
        }.getOrNull()

    private fun invokeNoArgMethod(target: Any, methodName: String): Any? =
        runCatching {
            val method = findMethod(target.javaClass, methodName)
                ?: return@runCatching null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()

    private fun findField(
        startClass: Class<*>,
        fieldName: String
    ): Field? {
        var current: Class<*>? = startClass
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMethod(
        startClass: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        var current: Class<*>? = startClass
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredMethod(
                    methodName,
                    *parameterTypes
                )
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun hierarchyFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" + it.type.name
            }
            .toList()

    private fun hierarchyMethods(type: Class<*>): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    !Modifier.isAbstract(it.modifiers) &&
                    !it.isSynthetic
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" +
                    it.parameterTypes.joinToString(",") { p -> p.name } +
                    "|" + it.returnType.name
            }
            .toList()
}
