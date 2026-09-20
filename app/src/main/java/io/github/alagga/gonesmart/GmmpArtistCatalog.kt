package io.github.alagga.gonesmart

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

class GmmpArtistCatalog {

    companion object {

        private const val TAG =
            "GoneSmart"

        /*
         * GMMPs ArtistDao erwartet bei R1(...)
         * einen RawQuery und erzeugt daraus
         * seine normalen Artist-Objekte.
         *
         * "custom_sort" wird von GMMPs Artist-Modell
         * erwartet. Für unseren Zweck können wir
         * artist_sort dafür verwenden.
         */
        private const val AMPERSAND_ARTIST_QUERY =
            """
            SELECT
                artist_id,
                artist,
                artist_sort AS custom_sort,
                artist_art,
                artist_date_added
            FROM artists
            WHERE artist LIKE '%&%'
            """
    }

    @Volatile
    private var loaded =
        false

    @Volatile
    private var knownAmpersandArtists:
            Set<String> =
        emptySet()

    /**
     * Lädt den Artist-Katalog einmalig über
     * GMMPs eigene Database-/DAO-Objekte.
     *
     * Kein direkter Zugriff auf:
     *
     * /data/data/...
     * /data/user/0/...
     * gmml.db
     *
     * GoneSmart benutzt ausschließlich die bereits
     * laufende GMMP-Datenbankinstanz.
     */
    @Synchronized
    fun ensureLoaded(
        autoDjInstance: Any
    ) {

        if (
            loaded
        ) {
            return
        }

        try {

            /*
             * qr.t = GMDatabase
             */
            val database =
                readObjectField(
                    target =
                        autoDjInstance,

                    fieldName =
                        "t"
                )

            if (
                database == null
            ) {

                Log.w(
                    TAG,
                    "GMMP artist catalog: " +
                            "GMDatabase not available"
                )

                return
            }

            /*
             * GMDatabase.y()
             *
             * =>
             *
             * ArtistDao
             *
             * declared type = zm
             * runtime type  = gn
             */
            val artistDaoMethod =
                findNoArgMethod(
                    startClass =
                        database.javaClass,

                    methodName =
                        "y"
                )

            if (
                artistDaoMethod == null
            ) {

                Log.w(
                    TAG,
                    "GMMP artist catalog: " +
                            "ArtistDao accessor not found"
                )

                return
            }

            artistDaoMethod.isAccessible =
                true

            val artistDao =
                artistDaoMethod.invoke(
                    database
                )

            if (
                artistDao == null
            ) {

                Log.w(
                    TAG,
                    "GMMP artist catalog: " +
                            "ArtistDao is null"
                )

                return
            }

            /*
             * tp4 =
             *
             * GMMP/Room SimpleSQLiteQuery.
             *
             * Wir erzeugen das Query-Objekt mit
             * demselben ClassLoader wie GMMP.
             */
            val classLoader =
                database.javaClass.classLoader

            if (
                classLoader == null
            ) {

                Log.w(
                    TAG,
                    "GMMP artist catalog: " +
                            "GMMP ClassLoader unavailable"
                )

                return
            }

            val queryClass =
                classLoader.loadClass(
                    "tp4"
                )

            val objectArrayClass =
                emptyArray<Any?>()
                    .javaClass

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
                        AMPERSAND_ARTIST_QUERY
                            .trimIndent(),

                        emptyArray<Any?>()
                    )

            /*
             * ArtistDao.R1(tp4)
             *
             * =>
             *
             * List<Artist>
             *
             * Das ist eine echte GMMP-DAO-Funktion.
             */
            val queryMethod =
                findSingleArgMethod(
                    startClass =
                        artistDao.javaClass,

                    methodName =
                        "R1",

                    argumentClass =
                        queryClass
                )

            if (
                queryMethod == null
            ) {

                Log.w(
                    TAG,
                    "GMMP artist catalog: " +
                            "ArtistDao.R1() not found"
                )

                return
            }

            queryMethod.isAccessible =
                true

            val result =
                queryMethod.invoke(
                    artistDao,
                    query
                )

            val artistObjects =
                result as? List<*>

            if (
                artistObjects == null
            ) {

                Log.w(
                    TAG,
                    "GMMP artist catalog: " +
                            "ArtistDao returned no List"
                )

                return
            }

            /*
             * GMMP Artist:
             *
             * runtime class = gm
             *
             * gm.p = Artistname
             */
            val artists =
                artistObjects
                    .mapNotNull { artistObject ->

                        if (
                            artistObject == null
                        ) {

                            return@mapNotNull null
                        }

                        readStringField(
                            target =
                                artistObject,

                            fieldName =
                                "p"
                        )
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank() &&
                                        it.contains(
                                            "&"
                                        )
                            }
                    }
                    .toSet()

            knownAmpersandArtists =
                artists

            loaded =
                true

            Log.i(
                TAG,
                "GMMP artist catalog loaded via ArtistDao: " +
                        "${artists.size} artist(s) containing '&'"
            )

        } catch (t: Throwable) {

            /*
             * loaded bleibt false.
             *
             * Dadurch dürfen wir es bei einem
             * späteren Auto-DJ-Aufruf noch einmal
             * versuchen.
             */
            Log.e(
                TAG,
                "Could not load GMMP artist catalog " +
                        "through ArtistDao",
                t
            )
        }
    }

    /**
     * Vom TrackMetadataNormalizer aufgerufen.
     *
     * Nach ensureLoaded() erfolgt hier keinerlei
     * Datenbankzugriff mehr.
     */
    fun getKnownAmpersandArtists():
            Set<String> {

        return knownAmpersandArtists
    }

    private fun readObjectField(
        target: Any,
        fieldName: String
    ): Any? {

        return try {

            val field =
                findField(
                    startClass =
                        target.javaClass,

                    fieldName =
                        fieldName
                ) ?: return null

            field.isAccessible =
                true

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

    private fun readStringField(
        target: Any,
        fieldName: String
    ): String? {

        return try {

            val field =
                findField(
                    startClass =
                        target.javaClass,

                    fieldName =
                        fieldName
                ) ?: return null

            field.isAccessible =
                true

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

    private fun findField(
        startClass: Class<*>,
        fieldName: String
    ): Field? {

        var currentClass:
                Class<*>? =
            startClass

        while (
            currentClass != null &&
            currentClass !=
            Any::class.java
        ) {

            try {

                return currentClass
                    .getDeclaredField(
                        fieldName
                    )

            } catch (
                _: NoSuchFieldException
            ) {

                currentClass =
                    currentClass.superclass
            }
        }

        return null
    }

    private fun findNoArgMethod(
        startClass: Class<*>,
        methodName: String
    ): Method? {

        var currentClass:
                Class<*>? =
            startClass

        while (
            currentClass != null &&
            currentClass !=
            Any::class.java
        ) {

            val method =
                currentClass
                    .declaredMethods
                    .firstOrNull {

                        it.name ==
                                methodName &&
                                it.parameterTypes
                                    .isEmpty()
                    }

            if (
                method != null
            ) {
                return method
            }

            currentClass =
                currentClass.superclass
        }

        return null
    }

    private fun findSingleArgMethod(
        startClass: Class<*>,
        methodName: String,
        argumentClass: Class<*>
    ): Method? {

        var currentClass:
                Class<*>? =
            startClass

        while (
            currentClass != null &&
            currentClass !=
            Any::class.java
        ) {

            val method =
                currentClass
                    .declaredMethods
                    .firstOrNull { candidate ->

                        if (
                            candidate.name !=
                            methodName
                        ) {
                            return@firstOrNull false
                        }

                        val parameters =
                            candidate.parameterTypes

                        if (
                            parameters.size !=
                            1
                        ) {
                            return@firstOrNull false
                        }

                        parameters[0]
                            .isAssignableFrom(
                                argumentClass
                            )
                    }

            if (
                method != null
            ) {
                return method
            }

            currentClass =
                currentClass.superclass
        }

        return null
    }
}