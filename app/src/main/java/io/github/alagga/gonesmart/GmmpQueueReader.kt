package io.github.alagga.gonesmart

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

class GmmpQueueReader {

    companion object {
        private const val TAG = "GoneSmart"
    }

    fun read(
        autoDjInstance: Any
    ): QueueContext? {

        return try {

            /*
             * qr.q = GMMP Queue
             *
             * runtime class = ex3
             */
            val queue =
                readObjectField(
                    autoDjInstance,
                    "q"
                ) ?: return null

            /*
             * qr.r = TrackDao
             *
             * declared type = r75
             * runtime type  = v75
             */
            val trackDao =
                readObjectField(
                    autoDjInstance,
                    "r"
                ) ?: return null

            /*
             * ex3.D()
             *
             * Bestätigt:
             * aktuelle queuePosition.
             */
            val currentQueuePosition =
                invokeNoArgMethod(
                    queue,
                    "D"
                ) as? Int ?: return null

            /*
             * ex3.r = QueueDao
             *
             * declared type = tx3
             * runtime type  = xx3
             */
            val queueDao =
                readObjectField(
                    queue,
                    "r"
                ) ?: return null

            /*
             * tx3.H1()
             *
             * Liefert alle Queue-Entities.
             *
             * WICHTIG:
             * Die Reihenfolge dieser Liste entspricht
             * NICHT zuverlässig der Wiedergabereihenfolge.
             *
             * Deshalb sortieren wir später anhand
             * von ey3.a = queuePosition.
             */
            val rawQueueItems =
                invokeNoArgMethod(
                    queueDao,
                    "H1"
                ) as? List<*> ?: return null

            /*
             * r75.R1(long) -> q75
             *
             * Bestätigter Track-Lookup anhand song_id.
             */
            val trackLookupMethod =
                findMethod(
                    trackDao.javaClass,
                    "R1",
                    java.lang.Long.TYPE
                ) ?: return null

            trackLookupMethod.isAccessible = true

            val unsortedItems =
                rawQueueItems.mapNotNull { rawQueueItem ->

                    if (rawQueueItem == null) {
                        return@mapNotNull null
                    }

                    /*
                     * ey3.a = queuePosition
                     */
                    val queuePosition =
                        readIntField(
                            rawQueueItem,
                            "a"
                        ) ?: return@mapNotNull null

                    /*
                     * ey3.b = song_id
                     */
                    val trackId =
                        readLongField(
                            rawQueueItem,
                            "b"
                        ) ?: return@mapNotNull null

                    /*
                     * ey3.c = shufflePosition
                     */
                    val shufflePosition =
                        readIntField(
                            rawQueueItem,
                            "c"
                        ) ?: -1

                    /*
                     * ey3.d = eindeutige Queue-Entry-ID
                     */
                    val queueEntryId =
                        readLongField(
                            rawQueueItem,
                            "d"
                        ) ?: return@mapNotNull null

                    /*
                     * Diese Logik haben wir mit der
                     * sichtbaren GMMP-Warteschlange
                     * bestätigt.
                     */
                    val state =
                        when {

                            queuePosition < currentQueuePosition ->
                                QueueItemState.PAST

                            queuePosition == currentQueuePosition ->
                                QueueItemState.CURRENT

                            else ->
                                QueueItemState.UPCOMING
                        }

                    val track =
                        readTrack(
                            trackDao = trackDao,
                            trackLookupMethod = trackLookupMethod,
                            trackId = trackId
                        )

                    QueueItemInfo(
                        orderIndex = -1,
                        queuePosition = queuePosition,
                        shufflePosition = shufflePosition,
                        queueEntryId = queueEntryId,
                        track = track,
                        state = state
                    )
                }

            /*
             * H1() liefert keine zuverlässige Reihenfolge.
             *
             * queuePosition dagegen entspricht exakt
             * der sichtbaren Wiedergabereihenfolge.
             */
            val sortedItems =
                unsortedItems
                    .sortedBy {
                        it.queuePosition
                    }
                    .mapIndexed { index, item ->

                        item.copy(
                            orderIndex = index
                        )
                    }

            QueueContext(
                currentQueuePosition = currentQueuePosition,
                items = sortedItems
            )

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "Could not read GMMP queue",
                t
            )

            null
        }
    }

    private fun readTrack(
        trackDao: Any,
        trackLookupMethod: Method,
        trackId: Long
    ): TrackInfo {

        return try {

            val trackObject =
                trackLookupMethod.invoke(
                    trackDao,
                    trackId
                )

            if (trackObject == null) {

                return TrackInfo(
                    id = trackId,
                    title = null,
                    artist = null,
                    albumArtist = null,
                    path = null
                )
            }

            /*
             * q75:
             *
             * o = song_id
             * p = Titel
             * r = Dateipfad
             * u = Track-Interpret(en)
             * H = Albuminterpret
             */
            TrackInfo(
                id =
                    readLongField(
                        trackObject,
                        "o"
                    ) ?: trackId,

                title =
                    readStringField(
                        trackObject,
                        "p"
                    ),

                artist =
                    readStringField(
                        trackObject,
                        "u"
                    ),

                albumArtist =
                    readStringField(
                        trackObject,
                        "H"
                    ),

                path =
                    readStringField(
                        trackObject,
                        "r"
                    )
            )

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "Track lookup failed for trackId=$trackId",
                t
            )

            TrackInfo(
                id = trackId,
                title = null,
                artist = null,
                albumArtist = null,
                path = null
            )
        }
    }

    private fun readObjectField(
        target: Any,
        fieldName: String
    ): Any? {

        return try {

            val field =
                findField(
                    target.javaClass,
                    fieldName
                ) ?: return null

            field.isAccessible = true

            field.get(
                target
            )

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "Could not read field " +
                        "${target.javaClass.name}.$fieldName",
                t
            )

            null
        }
    }

    private fun readIntField(
        target: Any,
        fieldName: String
    ): Int? {

        return try {

            val field =
                findField(
                    target.javaClass,
                    fieldName
                ) ?: return null

            field.isAccessible = true

            field.getInt(
                target
            )

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "Could not read int field " +
                        "${target.javaClass.name}.$fieldName",
                t
            )

            null
        }
    }

    private fun readLongField(
        target: Any,
        fieldName: String
    ): Long? {

        return try {

            val field =
                findField(
                    target.javaClass,
                    fieldName
                ) ?: return null

            field.isAccessible = true

            field.getLong(
                target
            )

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "Could not read long field " +
                        "${target.javaClass.name}.$fieldName",
                t
            )

            null
        }
    }

    private fun readStringField(
        target: Any,
        fieldName: String
    ): String? {

        return try {

            val field =
                findField(
                    target.javaClass,
                    fieldName
                ) ?: return null

            field.isAccessible = true

            field.get(
                target
            ) as? String

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "Could not read String field " +
                        "${target.javaClass.name}.$fieldName",
                t
            )

            null
        }
    }

    private fun invokeNoArgMethod(
        target: Any,
        methodName: String
    ): Any? {

        return try {

            val method =
                findMethod(
                    target.javaClass,
                    methodName
                ) ?: return null

            method.isAccessible = true

            method.invoke(
                target
            )

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "Could not invoke " +
                        "${target.javaClass.name}.$methodName()",
                t
            )

            null
        }
    }

    private fun findField(
        startClass: Class<*>,
        fieldName: String
    ): Field? {

        var currentClass: Class<*>? =
            startClass

        while (
            currentClass != null &&
            currentClass != Any::class.java
        ) {

            try {

                return currentClass
                    .getDeclaredField(
                        fieldName
                    )

            } catch (_: NoSuchFieldException) {

                currentClass =
                    currentClass.superclass
            }
        }

        return null
    }

    private fun findMethod(
        startClass: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method? {

        var currentClass: Class<*>? =
            startClass

        while (
            currentClass != null &&
            currentClass != Any::class.java
        ) {

            try {

                return currentClass
                    .getDeclaredMethod(
                        methodName,
                        *parameterTypes
                    )

            } catch (_: NoSuchMethodException) {

                currentClass =
                    currentClass.superclass
            }
        }

        return null
    }
}