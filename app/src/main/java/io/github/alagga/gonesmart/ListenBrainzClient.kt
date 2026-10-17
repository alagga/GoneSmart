package io.github.alagga.gonesmart

import android.util.Log
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
    val releaseName: String?,
    val artistMbids: List<String> = emptyList()
)

data class ListenBrainzSimilarTrack(
    val recordingMbid: String,
    val recordingName: String,
    val artistName: String,
    val releaseName: String?,
    val score: Double,
    val referenceMbid: String?
)

data class ListenBrainzSimilarArtist(
    val artistMbid: String,
    val artistName: String,
    val score: Double,
    val referenceMbid: String?
)

class ListenBrainzClient : RecommendationProviderSource {
    override val descriptor =
        RecommendationProviderRegistry.require(RecommendationProvider.LISTENBRAINZ)

    companion object {
        private const val TAG = "GoneSmart"
        private const val RECORDING_SEARCH_URL =
            "https://labs.api.listenbrainz.org/recording-search/json"
        private const val RECORDING_LOOKUP_URL =
            "https://labs.api.listenbrainz.org/recording-mbid-lookup/json"
        private const val SIMILAR_RECORDINGS_URL =
            "https://labs.api.listenbrainz.org/similar-recordings/json"
        private const val SIMILAR_ARTISTS_URL =
            "https://labs.api.listenbrainz.org/similar-artists/json"

        private const val SIMILARITY_ALGORITHM =
            "session_based_days_7500_session_300_" +
                "contribution_5_threshold_15_limit_50_" +
                "skip_30_top_n_listeners_1000"

        private const val SIMILAR_ARTISTS_ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_" +
                "threshold_10_limit_100_filter_True_skip_30"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val USER_AGENT =
            "GoneSmart/0.4.1 (https://github.com/alagga/GoneSmart)"
    }

    fun searchRecording(artist: String, title: String): List<ListenBrainzRecordingMatch> {
        val request = JSONArray().put(
            JSONObject().put("query", "$artist $title".trim())
        )
        val response = postJson(RECORDING_SEARCH_URL, request)
        requireSuccess("Recording search", response)
        return parseRecordingSearch(response.body).also { matches ->
            if (matches.isEmpty()) Log.i(TAG, "LB EMPTY | stage=recording-search")
        }
    }

    fun getArtistMbidsForRecording(recordingMbid: String): List<String> {
        if (recordingMbid.isBlank()) return emptyList()
        val request = JSONArray().put(
            JSONObject().put("recording_mbid", recordingMbid)
        )
        val response = postJson(RECORDING_LOOKUP_URL, request)
        if (response.statusCode == 404) return emptyList()
        requireSuccess("Recording MBID lookup", response)
        val json = JSONArray(response.body)
        val mbids = buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                addAll(parseStringList(item.opt("artist_credit_mbids")))
            }
        }.distinct()
        if (mbids.isEmpty()) Log.i(TAG, "LB EMPTY | stage=recording-artist-lookup")
        return mbids
    }

    fun getSimilarRecordings(recordingMbid: String): List<ListenBrainzSimilarTrack> {
        val request = JSONArray().put(
            JSONObject()
                .put("recording_mbids", JSONArray().put(recordingMbid))
                .put("algorithm", SIMILARITY_ALGORITHM)
        )
        val response = postJson(SIMILAR_RECORDINGS_URL, request)
        requireSuccess("Similar-recordings request", response)
        return parseSimilarRecordings(response.body, recordingMbid).also { tracks ->
            if (tracks.isEmpty()) Log.i(TAG, "LB EMPTY | stage=similar-recordings")
        }
    }

    fun getSimilarArtists(
        artistMbid: String,
        limit: Int = 12
    ): List<ListenBrainzSimilarArtist> {
        if (artistMbid.isBlank()) return emptyList()
        val request = JSONArray().put(
            JSONObject()
                .put("artist_mbids", JSONArray().put(artistMbid))
                .put("algorithm", SIMILAR_ARTISTS_ALGORITHM)
        )
        val response = postJson(SIMILAR_ARTISTS_URL, request)
        requireSuccess("Similar-artists request", response)
        val parsed = parseSimilarArtists(response.body, artistMbid)
        val highest = parsed.maxOfOrNull { it.score }?.takeIf { it > 0.0 } ?: 1.0
        return parsed.map { it.copy(score = (it.score / highest).coerceIn(0.0, 1.0)) }
            .sortedByDescending { it.score }
            .take(limit.coerceAtLeast(1))
            .also { artists ->
                if (artists.isEmpty()) Log.i(TAG, "LB EMPTY | stage=similar-artists")
            }
    }

    private fun parseRecordingSearch(body: String): List<ListenBrainzRecordingMatch> {
        val json = JSONArray(body)
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val recordingMbid = item.optString("recording_mbid").trim()
                val recordingName = item.optString("recording_name").trim()
                val artistName = item.optString("artist_credit_name").trim()
                if (recordingMbid.isBlank() || recordingName.isBlank() || artistName.isBlank()) continue
                add(
                    ListenBrainzRecordingMatch(
                        recordingMbid = recordingMbid,
                        recordingName = recordingName,
                        artistName = artistName,
                        releaseName = item.optString("release_name").trim()
                            .takeIf(String::isNotBlank),
                        // recording-search currently does not expose artist MBIDs.
                        // Resolve them lazily through recording-mbid-lookup only if
                        // the recording-similarity path cannot produce results.
                        artistMbids = emptyList()
                    )
                )
            }
        }
    }

    private fun parseSimilarRecordings(
        body: String,
        requestedReferenceMbid: String
    ): List<ListenBrainzSimilarTrack> {
        val json = JSONArray(body)
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val mbid = item.optString("recording_mbid").trim()
                val name = item.optString("recording_name").trim()
                val artist = item.optString("artist_credit_name").trim()
                if (mbid.isBlank() || name.isBlank() || artist.isBlank()) continue
                if (mbid.equals(requestedReferenceMbid, ignoreCase = true)) continue
                add(
                    ListenBrainzSimilarTrack(
                        recordingMbid = mbid,
                        recordingName = name,
                        artistName = artist,
                        releaseName = item.optString("release_name").trim()
                            .takeIf(String::isNotBlank),
                        score = item.optDouble("score", 0.0),
                        referenceMbid = item.optString("reference_mbid").trim()
                            .takeIf(String::isNotBlank)
                    )
                )
            }
        }.sortedByDescending { it.score }
    }

    private fun parseSimilarArtists(
        body: String,
        requestedReferenceMbid: String
    ): List<ListenBrainzSimilarArtist> {
        val json = JSONArray(body)
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val mbid = item.optString("artist_mbid").trim()
                val name = item.optString("name").trim()
                val score = item.optDouble("score", 0.0)
                if (mbid.isBlank() || name.isBlank() || score <= 0.0) continue
                if (mbid.equals(requestedReferenceMbid, ignoreCase = true)) continue
                add(
                    ListenBrainzSimilarArtist(
                        artistMbid = mbid,
                        artistName = name,
                        score = score,
                        referenceMbid = item.optString("reference_mbid").trim()
                            .takeIf(String::isNotBlank)
                    )
                )
            }
        }.sortedByDescending { it.score }
    }

    private fun parseStringList(raw: Any?): List<String> = when (raw) {
        is JSONArray -> buildList {
            for (index in 0 until raw.length()) {
                raw.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
        is String -> raw.split(',', ';')
            .map(String::trim)
            .filter(String::isNotBlank)
        else -> emptyList()
    }

    private fun requireSuccess(label: String, response: ListenBrainzHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                "$label failed: HTTP ${response.statusCode} | ${response.body}"
            )
        }
    }

    private fun postJson(url: String, body: JSONArray): ListenBrainzHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(body.toString())
                it.flush()
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use(BufferedReader::readText)
            } else ""
            return ListenBrainzHttpResponse(status, responseBody)
        } catch (t: Throwable) {
            val endpoint = url.substringAfter("listenbrainz.org/").substringBefore('/').ifBlank { "labs" }
            Log.w(TAG, "LB HTTP FAILURE | endpoint=$endpoint | ${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            connection.disconnect()
        }
    }
}
