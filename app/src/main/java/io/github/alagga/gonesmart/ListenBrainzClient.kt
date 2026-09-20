package io.github.alagga.gonesmart

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ListenBrainzHttpResponse(
    val statusCode: Int,
    val body: String
)

data class ListenBrainzRecordingMatch(
    val recordingMbid: String,
    val recordingName: String,
    val artistName: String,
    val releaseName: String?
)

data class ListenBrainzSimilarTrack(
    val recordingMbid: String,
    val recordingName: String,
    val artistName: String,
    val releaseName: String?,
    val score: Double,
    val referenceMbid: String?
)

class ListenBrainzClient {

    companion object {

        private const val RECORDING_SEARCH_URL =
            "https://labs.api.listenbrainz.org/recording-search/json"

        private const val SIMILAR_RECORDINGS_URL =
            "https://labs.api.listenbrainz.org/similar-recordings/json"

        /*
         * Session-basierter ListenBrainz-
         * Similarity-Algorithmus.
         *
         * Später können wir verschiedene
         * Algorithmen vergleichen oder diese
         * Einstellung konfigurierbar machen.
         */
        private const val SIMILARITY_ALGORITHM =
            "session_based_days_7500_session_300_" +
                    "contribution_5_threshold_15_limit_50_" +
                    "skip_30_top_n_listeners_1000"

        private const val CONNECT_TIMEOUT_MS =
            10_000

        private const val READ_TIMEOUT_MS =
            15_000

        private const val USER_AGENT =
            "GoneSmart/0.1 (https://github.com/alagga/GoneSmart)"
    }

    /**
     * Sucht anhand der lokalen GMMP-Metadaten
     *
     * Artist + Titel
     *
     * nach einem passenden MusicBrainz Recording.
     */
    fun searchRecording(
        artist: String,
        title: String
    ): List<ListenBrainzRecordingMatch> {

        val query =
            "$artist $title"
                .trim()

        /*
         * Beispiel:
         *
         * [
         *   {
         *     "query":
         *       "Swedish House Mafia,Lykke Li Happiness Is So Sad"
         *   }
         * ]
         */
        val request =
            JSONArray()
                .put(
                    JSONObject()
                        .put(
                            "query",
                            query
                        )
                )

        val response =
            postJson(
                url = RECORDING_SEARCH_URL,
                body = request
            )

        if (
            response.statusCode !in 200..299
        ) {
            throw IllegalStateException(
                "Recording search failed: " +
                        "HTTP ${response.statusCode} | " +
                        response.body
            )
        }

        return parseRecordingSearch(
            response.body
        )
    }

    /**
     * Holt für eine bereits bekannte
     * MusicBrainz Recording-MBID ähnliche Tracks.
     */
    fun getSimilarRecordings(
        recordingMbid: String
    ): List<ListenBrainzSimilarTrack> {

        /*
         * Anfrage:
         *
         * [
         *   {
         *     "recording_mbids": [
         *       "b3af5e0b-..."
         *     ],
         *     "algorithm": "..."
         *   }
         * ]
         */
        val request =
            JSONArray()
                .put(
                    JSONObject()
                        .put(
                            "recording_mbids",
                            JSONArray()
                                .put(
                                    recordingMbid
                                )
                        )
                        .put(
                            "algorithm",
                            SIMILARITY_ALGORITHM
                        )
                )

        val response =
            postJson(
                url = SIMILAR_RECORDINGS_URL,
                body = request
            )

        if (
            response.statusCode !in 200..299
        ) {
            throw IllegalStateException(
                "Similar-recordings request failed: " +
                        "HTTP ${response.statusCode} | " +
                        response.body
            )
        }

        return parseSimilarRecordings(
            body = response.body,
            requestedReferenceMbid = recordingMbid
        )
    }

    /**
     * Parst die Antwort von recording-search.
     */
    private fun parseRecordingSearch(
        body: String
    ): List<ListenBrainzRecordingMatch> {

        val json =
            JSONArray(
                body
            )

        val results =
            mutableListOf<ListenBrainzRecordingMatch>()

        for (
        index in 0 until json.length()
        ) {

            val item =
                json.optJSONObject(
                    index
                ) ?: continue

            val recordingMbid =
                item
                    .optString(
                        "recording_mbid"
                    )
                    .trim()

            val recordingName =
                item
                    .optString(
                        "recording_name"
                    )
                    .trim()

            val artistName =
                item
                    .optString(
                        "artist_credit_name"
                    )
                    .trim()

            /*
             * Ohne diese drei Informationen
             * ist der Treffer für GoneSmart
             * nicht sinnvoll verwendbar.
             */
            if (
                recordingMbid.isBlank() ||
                recordingName.isBlank() ||
                artistName.isBlank()
            ) {
                continue
            }

            val releaseName =
                item
                    .optString(
                        "release_name"
                    )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }

            results +=
                ListenBrainzRecordingMatch(
                    recordingMbid =
                        recordingMbid,

                    recordingName =
                        recordingName,

                    artistName =
                        artistName,

                    releaseName =
                        releaseName
                )
        }

        return results
    }

    /**
     * Parst die Similar-Recordings-Antwort.
     */
    private fun parseSimilarRecordings(
        body: String,
        requestedReferenceMbid: String
    ): List<ListenBrainzSimilarTrack> {

        val json =
            JSONArray(
                body
            )

        val results =
            mutableListOf<ListenBrainzSimilarTrack>()

        for (
        index in 0 until json.length()
        ) {

            val item =
                json.optJSONObject(
                    index
                ) ?: continue

            val recordingMbid =
                item
                    .optString(
                        "recording_mbid"
                    )
                    .trim()

            val recordingName =
                item
                    .optString(
                        "recording_name"
                    )
                    .trim()

            val artistName =
                item
                    .optString(
                        "artist_credit_name"
                    )
                    .trim()

            if (
                recordingMbid.isBlank() ||
                recordingName.isBlank() ||
                artistName.isBlank()
            ) {
                continue
            }

            /*
             * Den Seed-Track selbst wollen wir
             * nicht als Empfehlung zurückgeben.
             */
            if (
                recordingMbid.equals(
                    requestedReferenceMbid,
                    ignoreCase = true
                )
            ) {
                continue
            }

            val releaseName =
                item
                    .optString(
                        "release_name"
                    )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }

            val referenceMbid =
                item
                    .optString(
                        "reference_mbid"
                    )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }

            val score =
                item.optDouble(
                    "score",
                    0.0
                )

            results +=
                ListenBrainzSimilarTrack(
                    recordingMbid =
                        recordingMbid,

                    recordingName =
                        recordingName,

                    artistName =
                        artistName,

                    releaseName =
                        releaseName,

                    score =
                        score,

                    referenceMbid =
                        referenceMbid
                )
        }

        /*
         * Höchste Similarity zuerst.
         */
        return results
            .sortedByDescending {
                it.score
            }
    }

    /**
     * Gemeinsame POST-Methode für die
     * ListenBrainz-Labs-Endpunkte.
     */
    private fun postJson(
        url: String,
        body: JSONArray
    ): ListenBrainzHttpResponse {

        val connection =
            URL(
                url
            ).openConnection() as HttpURLConnection

        try {

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                CONNECT_TIMEOUT_MS

            connection.readTimeout =
                READ_TIMEOUT_MS

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            /*
             * JSON senden.
             */
            connection
                .outputStream
                .bufferedWriter(
                    Charsets.UTF_8
                )
                .use { writer ->

                    writer.write(
                        body.toString()
                    )

                    writer.flush()
                }

            /*
             * HTTP-Status abfragen.
             */
            val statusCode =
                connection.responseCode

            /*
             * Bei Fehlercodes den ErrorStream lesen,
             * damit wir eine brauchbare Fehlermeldung
             * im Log erhalten.
             */
            val stream =
                if (
                    statusCode in 200..299
                ) {

                    connection.inputStream

                } else {

                    connection.errorStream
                }

            val responseBody =
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

            return ListenBrainzHttpResponse(
                statusCode =
                    statusCode,

                body =
                    responseBody
            )

        } finally {

            connection.disconnect()
        }
    }
}