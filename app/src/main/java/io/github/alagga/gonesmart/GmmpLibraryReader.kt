package io.github.alagga.gonesmart

import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Date

data class GmmpLibraryTrack(
    val track: TrackInfo,
    val year: Int,
    val ratingRaw: Int,
    val playCount: Int,
    val skipCount: Int,
    val dateAddedEpochMs: Long?,
    val dateUpdatedEpochMs: Long?,
    val lastPlayedEpochMs: Long?
) {
    val ratingStars: Double?
        get() =
            if (
                ratingRaw <= 0
            ) {

                null

            } else {

                (ratingRaw.toDouble() / 2.0)
                    .coerceIn(
                        0.0,
                        5.0
                    )
            }
}

class GmmpLibraryReader {

    companion object {

        private const val TAG =
            "GoneSmart"

        /*
         * The in-memory library cache is refreshed
         * periodically so newly scanned, deleted or
         * retagged files become visible without a GMMP
         * restart.
         *
         * LocalLibraryMatcher uses a content fingerprint
         * afterwards and only rebuilds its index if the
         * relevant library data actually changed.
         */
        private const val CACHE_MAX_AGE_MS =
            2L * 60L * 1000L

        private const val LIBRARY_QUERY =
            """
            SELECT
                t.song_id AS song_id,
                t.track_name AS track_name,
                t.track_no AS track_no,
                t.track_uri AS track_uri,
                t.track_duration AS track_duration,
                t.track_year AS track_year,

                COALESCE(
                    (
                        SELECT GROUP_CONCAT(
                            artist_value.artist,
                            ','
                        )
                        FROM artist_tracks artist_link
                        INNER JOIN artists artist_value
                            ON artist_value.artist_id =
                               artist_link.artist_id
                        WHERE artist_link.song_id =
                              t.song_id
                    ),
                    ''
                ) AS artist,

                a.album_art AS album_art,
                a.album AS album,
                a.album_year AS album_year,

                t.song_rating AS song_rating,

                COALESCE(
                    (
                        SELECT GROUP_CONCAT(
                            genre_value.genre,
                            ','
                        )
                        FROM genre_tracks genre_link
                        INNER JOIN genres genre_value
                            ON genre_value.genre_id =
                               genre_link.genre_id
                        WHERE genre_link.song_id =
                              t.song_id
                    ),
                    ''
                ) AS genre,

                t.disc_no AS disc_no,
                t.playcount AS playcount,
                t.skipcount AS skipcount,

                t.track_date_added AS track_date_added,
                t.track_date_updated AS track_date_updated,
                t.track_last_played AS track_last_played,

                COALESCE(
                    (
                        SELECT GROUP_CONCAT(
                            album_artist_value.artist,
                            ','
                        )
                        FROM albumartist_albums
                             album_artist_link
                        INNER JOIN artists
                             album_artist_value
                            ON album_artist_value.artist_id =
                               album_artist_link.artist_id
                        WHERE album_artist_link.album_id =
                              t.album_id
                    ),
                    ''
                ) AS albumartist,

                COALESCE(
                    (
                        SELECT GROUP_CONCAT(
                            composer_value.composer,
                            ','
                        )
                        FROM composer_tracks
                             composer_link
                        INNER JOIN composers
                             composer_value
                            ON composer_value.composer_id =
                               composer_link.composer_id
                        WHERE composer_link.song_id =
                              t.song_id
                    ),
                    ''
                ) AS composer,

                t.album_id AS album_id

            FROM tracks t

            LEFT JOIN albums a
                ON a.album_id =
                   t.album_id

            WHERE t.track_placeholder = 0
            """
    }

    @Volatile
    private var cachedTracks:
            List<GmmpLibraryTrack>? =
        null

    @Volatile
    private var cacheCreatedAtElapsedMs:
            Long =
        0L

    @Volatile
    private var deterministicMappingFailure =
        false

    fun hasDeterministicMappingFailure(): Boolean =
        deterministicMappingFailure

    fun read(
        autoDjInstance: Any,
        forceRefresh: Boolean = false
    ): List<GmmpLibraryTrack> {

        val now =
            SystemClock.elapsedRealtime()

        val currentCache =
            cachedTracks

        if (
            !forceRefresh &&
            currentCache != null &&
            now -
            cacheCreatedAtElapsedMs <
            CACHE_MAX_AGE_MS
        ) {

            return currentCache
        }

        synchronized(
            this
        ) {

            val synchronizedNow =
                SystemClock.elapsedRealtime()

            val synchronizedCache =
                cachedTracks

            if (
                !forceRefresh &&
                synchronizedCache != null &&
                synchronizedNow -
                cacheCreatedAtElapsedMs <
                CACHE_MAX_AGE_MS
            ) {

                return synchronizedCache
            }

            val loaded =
                try {

                    loadLibrary(
                        autoDjInstance
                    )

                } catch (t: Throwable) {

                    Log.e(
                        TAG,
                        "Failed to read GMMP library",
                        t
                    )

                    synchronizedCache
                        ?: emptyList()
                }

            if (
                loaded.isNotEmpty()
            ) {

                cachedTracks =
                    loaded

                cacheCreatedAtElapsedMs =
                    SystemClock.elapsedRealtime()
            }

            return loaded
        }
    }

    fun hasFreshCache(): Boolean {

        val cache =
            cachedTracks
                ?: return false

        if (
            cache.isEmpty()
        ) {

            return false
        }

        return SystemClock.elapsedRealtime() -
                cacheCreatedAtElapsedMs <
                CACHE_MAX_AGE_MS
    }

    fun clearCache() {

        synchronized(
            this
        ) {

            cachedTracks =
                null

            cacheCreatedAtElapsedMs =
                0L
        }
    }

    private fun loadLibrary(
        autoDjInstance: Any
    ): List<GmmpLibraryTrack> {

        val startTime =
            System.nanoTime()

        val trackDaoField =
            findField(
                type = autoDjInstance.javaClass,
                name = "r"
            )

        trackDaoField.isAccessible =
            true

        val trackDao =
            trackDaoField.get(
                autoDjInstance
            )
                ?: throw IllegalStateException(
                    "qr.r / TrackDao is null"
                )

        val queryMethod =
            runCatching {
                resolveLibraryQueryMethod(trackDao)
            }.getOrElse { mappingError ->
                if (mappingError !is NoSuchMethodException &&
                    mappingError.cause !is AbstractMethodError
                ) {
                    throw mappingError
                }
                val tracks = loadLibraryThroughCursor(
                    autoDjInstance,
                    startTime
                )
                deterministicMappingFailure = false
                return tracks
            }

        queryMethod.isAccessible =
            true

        val queryClass =
            queryMethod
                .parameterTypes
                .single()

        val objectArrayClass =
            arrayOfNulls<Any>(
                0
            ).javaClass

        val queryConstructor =
            queryClass
                .getDeclaredConstructor(
                    String::class.java,
                    objectArrayClass
                )

        queryConstructor.isAccessible =
            true

        val query =
            queryConstructor
                .newInstance(
                    LIBRARY_QUERY,
                    emptyArray<Any>()
                )

        val queryResult =
            try {
                queryMethod.invoke(
                    trackDao,
                    query
                )
            } catch (t: java.lang.reflect.InvocationTargetException) {
                if (t.cause is AbstractMethodError) {
                    deterministicMappingFailure = true
                }
                throw t
            }

        @Suppress("UNCHECKED_CAST")
        val rows =
            queryResult as? List<Any?>
                ?: run {
                    deterministicMappingFailure = true
                    throw IllegalStateException(
                        "Resolved GMMP library query did not return a List"
                    )
                }

        val tracks =
            rows
                .mapNotNull { row ->

                    if (
                        row == null
                    ) {

                        null

                    } else {

                        readTrackRow(
                            row
                        )
                    }
                }

        val elapsedMs =
            (
                    System.nanoTime() -
                            startTime
                    ) /
                    1_000_000L

        Log.i(
            TAG,
            "GMMP library loaded through TrackDao: " +
                    "${tracks.size} track(s) | " +
                    "durationMs=$elapsedMs"
        )

        return tracks
    }

    private fun loadLibraryThroughCursor(
        autoDjInstance: Any,
        startTime: Long
    ): List<GmmpLibraryTrack> {
        val tracks = GmmpReadOnlySql.query(
            autoDjInstance = autoDjInstance,
            sql = LIBRARY_QUERY.trimIndent()
        ) { cursor ->
            fun column(name: String): Int {
                val index = cursor.getColumnIndex(name)
                require(index >= 0) {
                    "GMMP library Cursor missing column " + name
                }
                return index
            }

            val id = column("song_id")
            val title = column("track_name")
            val path = column("track_uri")
            val artist = column("artist")
            val albumArtist = column("albumartist")
            val year = column("track_year")
            val rating = column("song_rating")
            val playCount = column("playcount")
            val skipCount = column("skipcount")
            val dateAdded = column("track_date_added")
            val dateUpdated = column("track_date_updated")
            val lastPlayed = column("track_last_played")

            buildList {
                while (cursor.moveToNext()) {
                    val trackId = cursor.getLong(id)
                    add(
                        GmmpLibraryTrack(
                            track = TrackInfo(
                                id = trackId,
                                title = if (cursor.isNull(title)) null
                                    else cursor.getString(title),
                                artist = if (cursor.isNull(artist)) null
                                    else cursor.getString(artist),
                                albumArtist =
                                    if (cursor.isNull(albumArtist)) null
                                    else cursor.getString(albumArtist),
                                path = if (cursor.isNull(path)) null
                                    else cursor.getString(path)
                            ),
                            year = if (cursor.isNull(year)) 0
                                else cursor.getInt(year),
                            ratingRaw = if (cursor.isNull(rating)) 0
                                else cursor.getInt(rating),
                            playCount = if (cursor.isNull(playCount)) 0
                                else cursor.getInt(playCount),
                            skipCount = if (cursor.isNull(skipCount)) 0
                                else cursor.getInt(skipCount),
                            dateAddedEpochMs =
                                if (cursor.isNull(dateAdded)) null
                                else cursor.getLong(dateAdded)
                                    .takeIf { it > 0L },
                            dateUpdatedEpochMs =
                                if (cursor.isNull(dateUpdated)) null
                                else cursor.getLong(dateUpdated)
                                    .takeIf { it > 0L },
                            lastPlayedEpochMs =
                                if (cursor.isNull(lastPlayed)) null
                                else cursor.getLong(lastPlayed)
                                    .takeIf { it > 0L }
                        )
                    )
                }
            }
        }

        val elapsedMs =
            (System.nanoTime() - startTime) / 1_000_000L
        Log.i(
            TAG,
            "GMMP library loaded through read-only database Cursor: " +
                tracks.size + " track(s) | durationMs=" + elapsedMs
        )
        return tracks
    }

    private fun readTrackRow(
        row: Any
    ): GmmpLibraryTrack? {

        val id =
            invokeLongGetter(
                row = row,
                methodName = "getId"
            )
                ?: return null

        val title =
            invokeStringGetter(
                row = row,
                methodName = "getName"
            )

        val artist =
            invokeStringGetter(
                row = row,
                methodName = "getArtist"
            )

        val path =
            invokeStringGetter(
                row = row,
                methodName = "c"
            )

        val albumArtist =
            readStringField(
                row = row,
                fieldName = "H"
            )

        /*
         * q75 field mapping verified against GMMP's
         * Room row mapper:
         *
         * t = track_year
         * y = song_rating (0..10, half-star steps)
         * B = playcount
         * C = skipcount
         * E = track_date_added
         * F = track_date_updated
         * G = track_last_played
         */
        val year =
            readIntField(
                row = row,
                fieldName = "t"
            )
                ?: 0

        val ratingRaw =
            readIntField(
                row = row,
                fieldName = "y"
            )
                ?: 0

        val playCount =
            readIntField(
                row = row,
                fieldName = "B"
            )
                ?: 0

        val skipCount =
            readIntField(
                row = row,
                fieldName = "C"
            )
                ?: 0

        val dateAddedEpochMs =
            readDateField(
                row = row,
                fieldName = "E"
            )

        val dateUpdatedEpochMs =
            readDateField(
                row = row,
                fieldName = "F"
            )

        val lastPlayedEpochMs =
            readDateField(
                row = row,
                fieldName = "G"
            )

        return GmmpLibraryTrack(
            track =
                TrackInfo(
                    id = id,
                    title = title,
                    artist = artist,
                    albumArtist = albumArtist,
                    path = path
                ),
            year = year,
            ratingRaw = ratingRaw,
            playCount = playCount,
            skipCount = skipCount,
            dateAddedEpochMs = dateAddedEpochMs,
            dateUpdatedEpochMs = dateUpdatedEpochMs,
            lastPlayedEpochMs = lastPlayedEpochMs
        )
    }

    private fun invokeLongGetter(
        row: Any,
        methodName: String
    ): Long? {

        return try {

            val method =
                findMethod(
                    type = row.javaClass,
                    name = methodName,
                    parameterCount = 0
                )

            method.isAccessible =
                true

            when (
                val value =
                    method.invoke(
                        row
                    )
            ) {

                is Number ->
                    value.toLong()

                else ->
                    null
            }

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    private fun invokeStringGetter(
        row: Any,
        methodName: String
    ): String? {

        return try {

            val method =
                findMethod(
                    type = row.javaClass,
                    name = methodName,
                    parameterCount = 0
                )

            method.isAccessible =
                true

            method.invoke(
                row
            ) as? String

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    private fun readStringField(
        row: Any,
        fieldName: String
    ): String? {

        return try {

            val field =
                findField(
                    type = row.javaClass,
                    name = fieldName
                )

            field.isAccessible =
                true

            field.get(
                row
            ) as? String

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    private fun readIntField(
        row: Any,
        fieldName: String
    ): Int? {

        return try {

            val field =
                findField(
                    type = row.javaClass,
                    name = fieldName
                )

            field.isAccessible =
                true

            when (
                val value =
                    field.get(
                        row
                    )
            ) {

                is Number ->
                    value.toInt()

                else ->
                    null
            }

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    private fun readDateField(
        row: Any,
        fieldName: String
    ): Long? {

        return try {

            val field =
                findField(
                    type = row.javaClass,
                    name = fieldName
                )

            field.isAccessible =
                true

            val date =
                field.get(
                    row
                ) as? Date
                    ?: return null

            date.time
                .takeIf {
                    it > 0L
                }

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    private fun findField(
        type: Class<*>,
        name: String
    ): Field {

        var current:
                Class<*>? =
            type

        while (
            current != null
        ) {

            try {

                return current
                    .getDeclaredField(
                        name
                    )

            } catch (
                _: NoSuchFieldException
            ) {

                current =
                    current.superclass
            }
        }

        throw NoSuchFieldException(
            "${type.name}.$name"
        )
    }

    private fun resolveLibraryQueryMethod(
        trackDao: Any
    ): Method {
        val type =
            trackDao.javaClass
        runCatching {
            findMethod(
                type = type,
                name = "g2",
                parameterCount = 1
            )
        }.getOrNull()?.let {
            return it
        }

        val objectArrayClass =
            arrayOfNulls<Any>(0).javaClass

        val oneArgMethods =
            (
                generateSequence<Class<*>>(type) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() } +
                    type.methods.asSequence()
                )
                .filter { it.parameterTypes.size == 1 }
                .distinctBy { method ->
                    method.declaringClass.name + "|" +
                        method.name + "|" +
                        method.parameterTypes.joinToString(",") { it.name } + "|" +
                        method.returnType.name
                }
                .sortedWith(
                    compareBy<Method>(
                        { it.name },
                        { it.declaringClass.name }
                    )
                )
                .toList()

        val listCandidates =
            oneArgMethods.filter {
                java.util.List::class.java.isAssignableFrom(it.returnType)
            }

        fun hasRawQueryConstructor(method: Method): Boolean {
            val queryClass = method.parameterTypes.single()
            return runCatching {
                queryClass.getDeclaredConstructor(
                    String::class.java,
                    objectArrayClass
                )
            }.isSuccess
        }

        val rawQueryMethods =
            oneArgMethods.filter(::hasRawQueryConstructor)

        val concreteRawQueryMethods =
            rawQueryMethods.filter {
                !java.lang.reflect.Modifier.isAbstract(it.modifiers)
            }

        val concreteListCandidates =
            concreteRawQueryMethods.filter {
                java.util.List::class.java.isAssignableFrom(it.returnType)
            }

        val abstractListContracts =
            rawQueryMethods.filter {
                java.lang.reflect.Modifier.isAbstract(it.modifiers) &&
                    java.util.List::class.java.isAssignableFrom(it.returnType)
            }

        fun signature(method: Method): String =
            method.declaringClass.name + "." +
                method.name + "(" +
                method.parameterTypes.joinToString(",") { it.name } +
                "):" + method.returnType.name +
                if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) {
                    "[abstract]"
                } else {
                    ""
                }

        val resolved =
            when {
                concreteListCandidates.size == 1 ->
                    concreteListCandidates.single()

                abstractListContracts.size == 1 -> {
                    val contractParam =
                        abstractListContracts.single()
                            .parameterTypes
                            .single()

                    val concreteForContract =
                        concreteRawQueryMethods.filter {
                            it.parameterTypes.single() == contractParam &&
                                (
                                    it.returnType == Any::class.java ||
                                        java.util.List::class.java
                                            .isAssignableFrom(it.returnType)
                                    )
                        }

                    concreteForContract.singleOrNull()
                }

                else -> null
            }

        if (resolved != null) {
            Log.w(
                TAG,
                "GMMP LIBRARY MAPPING | expected=" + type.name +
                    ".g2/1 unavailable | structurally resolved=" +
                    signature(resolved) +
                    " | abstractContract=" +
                    abstractListContracts.singleOrNull()
                        ?.let(::signature)
                        .orEmpty()
                        .ifBlank { "none" }
            )
            return resolved
        }

        deterministicMappingFailure = true

        Log.w(
            TAG,
            "GMMP LIBRARY MAPPING | expected=" + type.name + ".g2/1 unavailable" +
                " | concreteRawQuery=" +
                concreteRawQueryMethods.take(8).joinToString(",") {
                    signature(it)
                }.ifBlank { "none" } +
                " | abstractContracts=" +
                abstractListContracts.take(8).joinToString(",") {
                    signature(it)
                }.ifBlank { "none" } +
                " | listCandidates=" +
                listCandidates.take(12).joinToString(",") { signature(it) }
                    .ifBlank { "none" } +
                " | oneArgMethodCount=" + oneArgMethods.size
        )

        diagnoseTrackDaoStructure(
            trackDao
        )

        throw NoSuchMethodException(
            type.name + ".g2/1 is unavailable; GMMP library mapping is unverified"
        )
    }

    private fun diagnoseTrackDaoStructure(
        trackDao: Any
    ) {
        val type =
            trackDao.javaClass

        val hierarchy =
            generateSequence<Class<*>>(type) {
                it.superclass
            }
                .take(8)
                .map { it.name }
                .toList()

        val interfaces =
            generateSequence<Class<*>>(type) {
                it.superclass
            }
                .flatMap { it.interfaces.asSequence() }
                .map { it.name }
                .distinct()
                .take(16)
                .toList()

        val instanceFields =
            generateSequence<Class<*>>(type) {
                it.superclass
            }
                .flatMap { it.declaredFields.asSequence() }
                .filter {
                    !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
                .distinctBy {
                    it.name + "|" + it.type.name
                }
                .take(24)
                .toList()

        val fields =
            instanceFields.map { field ->
                field.isAccessible = true
                val runtimeType =
                    runCatching {
                        field.get(trackDao)
                            ?.javaClass
                            ?.name
                    }.getOrNull()

                field.name + ":" +
                    field.type.name +
                    if (runtimeType == null) {
                        ""
                    } else {
                        "->" + runtimeType
                    }
            }

        val delegates =
            instanceFields
                .mapNotNull { field ->
                    field.isAccessible = true
                    val value =
                        runCatching {
                            field.get(trackDao)
                        }.getOrNull()
                            ?: return@mapNotNull null

                    val runtimeType =
                        value.javaClass

                    if (
                        runtimeType == type ||
                        runtimeType.name.startsWith("java.") ||
                        runtimeType.name.startsWith("android.") ||
                        runtimeType.name.startsWith("androidx.") ||
                        runtimeType.isPrimitive ||
                        Number::class.java.isAssignableFrom(runtimeType)
                    ) {
                        return@mapNotNull null
                    }

                    val oneArg =
                        GmmpReflectionDiagnostics.methods(
                            type = runtimeType,
                            limit = 12
                        ) {
                            it.parameterTypes.size == 1
                        }

                    field.name + "->" +
                        runtimeType.name +
                        "{" + oneArg + "}"
                }
                .take(6)

        Log.w(
            TAG,
            "GMMP TRACK DAO STRUCTURE | class=" +
                type.name +
                " | hierarchy=" +
                hierarchy.joinToString(">") +
                " | interfaces=" +
                interfaces.joinToString(",")
                    .ifBlank { "none" } +
                " | fields=" +
                fields.joinToString(",")
                    .ifBlank { "none" } +
                " | delegates=" +
                delegates.joinToString(";")
                    .ifBlank { "none" }
        )
    }

    private fun findMethod(
        type: Class<*>,
        name: String,
        parameterCount: Int
    ): Method {

        var current:
                Class<*>? =
            type

        while (
            current != null
        ) {

            val method =
                current
                    .declaredMethods
                    .firstOrNull {
                        it.name ==
                                name &&
                                it.parameterTypes.size ==
                                parameterCount
                    }

            if (
                method != null
            ) {

                return method
            }

            current =
                current.superclass
        }

        throw NoSuchMethodException(
            "${type.name}.$name/" +
                    parameterCount
        )
    }
}