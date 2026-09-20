package io.github.alagga.gonesmart

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LastFmTrackIdentity(
    val artistName: String,
    val trackName: String,
    val mbid: String?
)

data class LastFmSimilarTrack(
    val artistName: String,
    val trackName: String,
    val match: Double,
    val mbid: String?
)

class LastFmClient(
    private val apiKey: String
) {

    companion object {

        private const val API_URL =
            "https://ws.audioscrobbler.com/2.0/"

        private const val CONNECT_TIMEOUT_MS =
            10_000

        private const val READ_TIMEOUT_MS =
            15_000

        private const val USER_AGENT =
            "GoneSmart/0.1 " +
                    "(https://github.com/alagga/GoneSmart)"
    }

    fun isConfigured():
            Boolean {

        return apiKey.isNotBlank()
    }

    /**
     * Lässt Last.fm zuerst selbst bestimmen,
     * welchen Track es unter Artist + Titel kennt.
     *
     * autocorrect=1 erlaubt Last.fm dabei,
     * Schreibweisen zu korrigieren.
     *
     * Beispiel:
     *
     * 24kgoldn
     *
     * =>
     *
     * 24kGoldn
     *
     * Wenn Last.fm den Track nicht kennt,
     * geben wir null zurück.
     */
    fun resolveTrack(
        artist: String,
        title: String
    ): LastFmTrackIdentity? {

        if (
            apiKey.isBlank()
        ) {

            throw IllegalStateException(
                "Last.fm API key is not configured"
            )
        }

        if (
            artist.isBlank() ||
            title.isBlank()
        ) {
            return null
        }

        val url =
            buildTrackInfoUrl(
                artist = artist,
                title = title
            )

        val root =
            try {

                requestJson(
                    url
                )

            } catch (
                error: LastFmApiException
            ) {

                /*
                 * Last.fm verwendet Error 6 unter
                 * anderem für nicht auflösbare
                 * Artist-/Track-Kombinationen.
                 *
                 * Error 7 kann ebenfalls bedeuten,
                 * dass die Ressource nicht existiert.
                 *
                 * In diesen Fällen probieren wir
                 * einfach den nächsten Query-Kandidaten.
                 */
                if (
                    error.errorCode == 6 ||
                    error.errorCode == 7
                ) {

                    return null
                }

                throw error
            }

        val track =
            root.optJSONObject(
                "track"
            ) ?: return null

        val trackName =
            track
                .optString(
                    "name"
                )
                .trim()

        if (
            trackName.isBlank()
        ) {
            return null
        }

        val artistName =
            readArtistName(
                track.opt(
                    "artist"
                )
            )

        if (
            artistName.isBlank()
        ) {
            return null
        }

        val mbid =
            track
                .optString(
                    "mbid"
                )
                .trim()
                .takeIf {
                    it.isNotBlank()
                }

        return LastFmTrackIdentity(
            artistName =
                artistName,

            trackName =
                trackName,

            mbid =
                mbid
        )
    }

    /**
     * Similarity über eine vorher von Last.fm
     * aufgelöste Track-Identität.
     *
     * Falls eine MBID vorhanden ist, bevorzugen
     * wir diese.
     */
    fun getSimilarTracks(
        identity: LastFmTrackIdentity,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {

        val url =
            if (
                !identity.mbid.isNullOrBlank()
            ) {

                buildSimilarTracksUrl(
                    mbid =
                        identity.mbid,

                    limit =
                        limit
                )

            } else {

                buildSimilarTracksUrl(
                    artist =
                        identity.artistName,

                    title =
                        identity.trackName,

                    limit =
                        limit
                )
            }

        return readSimilarTracks(
            url
        )
    }

    /**
     * Direkte Variante bleibt bestehen.
     *
     * Für Build 24 verwenden wir normalerweise
     * resolveTrack() + getSimilarTracks(identity).
     */
    fun getSimilarTracks(
        artist: String,
        title: String,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {

        if (
            apiKey.isBlank()
        ) {

            throw IllegalStateException(
                "Last.fm API key is not configured"
            )
        }

        val url =
            buildSimilarTracksUrl(
                artist =
                    artist,

                title =
                    title,

                limit =
                    limit
            )

        return readSimilarTracks(
            url
        )
    }

    private fun readSimilarTracks(
        url: String
    ): List<LastFmSimilarTrack> {

        val root =
            requestJson(
                url
            )

        val similarTracks =
            root.optJSONObject(
                "similartracks"
            )
                ?: return emptyList()

        val rawTracks =
            similarTracks.opt(
                "track"
            )

        return when (
            rawTracks
        ) {

            is JSONArray -> {

                parseTrackArray(
                    rawTracks
                )
            }

            is JSONObject -> {

                listOfNotNull(
                    parseSimilarTrack(
                        rawTracks
                    )
                )
            }

            else -> {

                emptyList()
            }
        }
    }

    private fun parseTrackArray(
        array: JSONArray
    ): List<LastFmSimilarTrack> {

        val result =
            mutableListOf<LastFmSimilarTrack>()

        for (
        index in 0 until array.length()
        ) {

            val item =
                array.optJSONObject(
                    index
                ) ?: continue

            val track =
                parseSimilarTrack(
                    item
                ) ?: continue

            result +=
                track
        }

        return result
            .sortedByDescending {
                it.match
            }
    }

    private fun parseSimilarTrack(
        item: JSONObject
    ): LastFmSimilarTrack? {

        val trackName =
            item
                .optString(
                    "name"
                )
                .trim()

        if (
            trackName.isBlank()
        ) {
            return null
        }

        val artistName =
            readArtistName(
                item.opt(
                    "artist"
                )
            )

        if (
            artistName.isBlank()
        ) {
            return null
        }

        val match =
            when (
                val rawMatch =
                    item.opt(
                        "match"
                    )
            ) {

                is Number -> {

                    rawMatch.toDouble()
                }

                is String -> {

                    rawMatch
                        .toDoubleOrNull()
                        ?: 0.0
                }

                else -> {

                    0.0
                }
            }

        val mbid =
            item
                .optString(
                    "mbid"
                )
                .trim()
                .takeIf {
                    it.isNotBlank()
                }

        return LastFmSimilarTrack(
            artistName =
                artistName,

            trackName =
                trackName,

            match =
                match,

            mbid =
                mbid
        )
    }

    private fun readArtistName(
        value: Any?
    ): String {

        return when (
            value
        ) {

            is JSONObject -> {

                value
                    .optString(
                        "name"
                    )
                    .trim()
            }

            is String -> {

                value.trim()
            }

            else -> {

                ""
            }
        }
    }

    private fun buildTrackInfoUrl(
        artist: String,
        title: String
    ): String {

        return API_URL +
                "?" +
                "method=track.getinfo" +
                "&artist=${encode(artist)}" +
                "&track=${encode(title)}" +
                "&api_key=${encode(apiKey)}" +
                "&autocorrect=1" +
                "&format=json"
    }

    private fun buildSimilarTracksUrl(
        artist: String,
        title: String,
        limit: Int
    ): String {

        return API_URL +
                "?" +
                "method=track.getsimilar" +
                "&artist=${encode(artist)}" +
                "&track=${encode(title)}" +
                "&api_key=${encode(apiKey)}" +
                "&autocorrect=1" +
                "&limit=$limit" +
                "&format=json"
    }

    private fun buildSimilarTracksUrl(
        mbid: String,
        limit: Int
    ): String {

        return API_URL +
                "?" +
                "method=track.getsimilar" +
                "&mbid=${encode(mbid)}" +
                "&api_key=${encode(apiKey)}" +
                "&autocorrect=1" +
                "&limit=$limit" +
                "&format=json"
    }

    private fun encode(
        value: String
    ): String {

        return URLEncoder.encode(
            value,
            "UTF-8"
        )
    }

    private fun requestJson(
        url: String
    ): JSONObject {

        val body =
            get(
                url
            )

        val root =
            JSONObject(
                body
            )

        if (
            root.has(
                "error"
            )
        ) {

            val errorCode =
                root.optInt(
                    "error",
                    -1
                )

            val message =
                root.optString(
                    "message",
                    "Unknown Last.fm error"
                )

            throw LastFmApiException(
                errorCode =
                    errorCode,

                message =
                    message
            )
        }

        return root
    }

    private fun get(
        url: String
    ): String {

        val connection =
            URL(
                url
            ).openConnection() as HttpURLConnection

        try {

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                CONNECT_TIMEOUT_MS

            connection.readTimeout =
                READ_TIMEOUT_MS

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            val statusCode =
                connection.responseCode

            val stream =
                if (
                    statusCode in 200..299
                ) {

                    connection.inputStream

                } else {

                    connection.errorStream
                }

            val body =
                if (
                    stream != null
                ) {

                    BufferedReader(
                        InputStreamReader(
                            stream,
                            Charsets.UTF_8
                        )
                    ).use { reader ->

                        reader.readText()
                    }

                } else {

                    ""
                }

            if (
                statusCode !in 200..299
            ) {

                throw IllegalStateException(
                    "Last.fm HTTP $statusCode: $body"
                )
            }

            return body

        } finally {

            connection.disconnect()
        }
    }

    private class LastFmApiException(
        val errorCode: Int,
        message: String
    ) : Exception(
        "Last.fm error $errorCode: $message"
    )
}