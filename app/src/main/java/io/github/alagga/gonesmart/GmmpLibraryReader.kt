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
            findMethod(
                type = trackDao.javaClass,
                name = "g2",
                parameterCount = 1
            )

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

        @Suppress("UNCHECKED_CAST")
        val rows =
            queryMethod.invoke(
                trackDao,
                query
            ) as? List<Any?>
                ?: emptyList()

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