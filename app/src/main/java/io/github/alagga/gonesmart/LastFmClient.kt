package io.github.alagga.gonesmart

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

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

private data class LastFmSimilarArtist(
    val artistName: String,
    val match: Double
)

class LastFmClient(
    private val apiKey: String
) {

    companion object {
        private const val API_URL =
            "https://ws.audioscrobbler.com/2.0/"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        private const val USER_AGENT =
            "GoneSmart/0.4.1 (https://github.com/alagga/GoneSmart)"

        /*
         * Keep the artist fallback deliberately small. It is only used when
         * Last.fm cannot produce track-level similarity for the seed. Each
         * seed therefore adds at most one artist.getSimilar request plus
         * three artist.getTopTracks requests.
         */
        private const val ARTIST_FALLBACK_MAX_ARTISTS = 3
        private const val ARTIST_FALLBACK_TRACKS_PER_ARTIST = 8
        private const val ARTIST_FALLBACK_SCORE_PENALTY = 0.78
        private const val ARTIST_FALLBACK_RANK_DECAY = 0.24
    }

    fun isConfigured(): Boolean =
        apiKey.isNotBlank()

    /**
     * Lets Last.fm normalize the seed identity first. For tracks which are so
     * new or niche that Last.fm does not know the recording yet, return the
     * clean raw identity instead of dropping the provider completely. The
     * subsequent getSimilarTracks() call will first retry normal track-level
     * similarity and, if that still yields nothing, use the bounded artist
     * fallback.
     */
    fun resolveTrack(
        artist: String,
        title: String
    ): LastFmTrackIdentity? {

        ensureConfigured()

        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) {
            return null
        }

        val fallbackIdentity =
            LastFmTrackIdentity(
                artistName = cleanArtist,
                trackName = cleanTitle,
                mbid = null
            )

        val root =
            try {
                requestJson(
                    buildTrackInfoUrl(
                        artist = cleanArtist,
                        title = cleanTitle
                    )
                )
            } catch (error: LastFmApiException) {
                if (error.isMissingResource()) {
                    return fallbackIdentity
                }
                throw error
            }

        val track =
            root.optJSONObject("track")
                ?: return fallbackIdentity

        val trackName =
            track.optString("name").trim()
                .ifBlank { cleanTitle }

        val artistName =
            readArtistName(track.opt("artist"))
                .ifBlank { cleanArtist }

        val mbid =
            track.optString("mbid")
                .trim()
                .takeIf { it.isNotBlank() }

        return LastFmTrackIdentity(
            artistName = artistName,
            trackName = trackName,
            mbid = mbid
        )
    }

    /**
     * Prefer Last.fm's recording-level graph. If the seed is too new/niche or
     * Last.fm simply has no similar recordings for it, expand through similar
     * artists and their top tracks. This keeps the recommendation source
     * official and login-free while covering seeds that are absent from the
     * track similarity graph.
     */
    fun getSimilarTracks(
        identity: LastFmTrackIdentity,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {

        ensureConfigured()

        val normalizedLimit = limit.coerceAtLeast(1)

        val direct =
            try {
                readSimilarTracks(
                    if (!identity.mbid.isNullOrBlank()) {
                        buildSimilarTracksUrl(
                            mbid = identity.mbid,
                            limit = normalizedLimit
                        )
                    } else {
                        buildSimilarTracksUrl(
                            artist = identity.artistName,
                            title = identity.trackName,
                            limit = normalizedLimit
                        )
                    }
                )
            } catch (error: LastFmApiException) {
                if (error.isMissingResource()) {
                    emptyList()
                } else {
                    throw error
                }
            }

        if (direct.isNotEmpty()) {
            return direct
                .take(normalizedLimit)
        }

        return getArtistFallbackTracks(
            artist = identity.artistName,
            limit = normalizedLimit
        )
    }

    fun getSimilarTracks(
        artist: String,
        title: String,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {

        ensureConfigured()

        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) {
            return emptyList()
        }

        val normalizedLimit = limit.coerceAtLeast(1)

        val direct =
            try {
                readSimilarTracks(
                    buildSimilarTracksUrl(
                        artist = cleanArtist,
                        title = cleanTitle,
                        limit = normalizedLimit
                    )
                )
            } catch (error: LastFmApiException) {
                if (error.isMissingResource()) {
                    emptyList()
                } else {
                    throw error
                }
            }

        if (direct.isNotEmpty()) {
            return direct
                .take(normalizedLimit)
        }

        return getArtistFallbackTracks(
            artist = cleanArtist,
            limit = normalizedLimit
        )
    }

    private fun getArtistFallbackTracks(
        artist: String,
        limit: Int
    ): List<LastFmSimilarTrack> {

        val cleanArtist = artist.trim()

        if (cleanArtist.isBlank()) {
            return emptyList()
        }

        val similarArtists =
            try {
                readSimilarArtists(
                    buildSimilarArtistsUrl(
                        artist = cleanArtist,
                        limit = ARTIST_FALLBACK_MAX_ARTISTS
                    )
                )
            } catch (error: LastFmApiException) {
                if (error.isMissingResource()) {
                    emptyList()
                } else {
                    throw error
                }
            }

        if (similarArtists.isEmpty()) {
            return emptyList()
        }

        val result =
            mutableListOf<LastFmSimilarTrack>()

        similarArtists
            .take(ARTIST_FALLBACK_MAX_ARTISTS)
            .forEach { similarArtist ->

                val topTracks =
                    try {
                        readArtistTopTracks(
                            buildArtistTopTracksUrl(
                                artist = similarArtist.artistName,
                                limit = ARTIST_FALLBACK_TRACKS_PER_ARTIST
                            )
                        )
                    } catch (_: Throwable) {
                        emptyList()
                    }

                val denominator =
                    (topTracks.size - 1)
                        .coerceAtLeast(1)
                        .toDouble()

                topTracks.forEachIndexed { index, track ->
                    val rankProgress =
                        index.toDouble() / denominator

                    val rankFactor =
                        1.0 -
                            (ARTIST_FALLBACK_RANK_DECAY * rankProgress)

                    val fallbackScore =
                        (
                            similarArtist.match *
                                ARTIST_FALLBACK_SCORE_PENALTY *
                                rankFactor
                            )
                            .coerceIn(0.0, 1.0)

                    if (fallbackScore > 0.0) {
                        result +=
                            track.copy(
                                match = fallbackScore
                            )
                    }
                }
            }

        return result
            .distinctBy { track ->
                track.artistName
                    .trim()
                    .lowercase(Locale.ROOT) +
                    "\u0000" +
                    track.trackName
                        .trim()
                        .lowercase(Locale.ROOT)
            }
            .sortedByDescending { it.match }
            .take(limit)
    }

    private fun readSimilarTracks(
        url: String
    ): List<LastFmSimilarTrack> {

        val root = requestJson(url)
        val similarTracks =
            root.optJSONObject("similartracks")
                ?: return emptyList()

        return parseTrackCollection(
            similarTracks.opt("track"),
            defaultMatch = 0.0
        )
            .sortedByDescending { it.match }
    }

    private fun readArtistTopTracks(
        url: String
    ): List<LastFmSimilarTrack> {

        val root = requestJson(url)
        val topTracks =
            root.optJSONObject("toptracks")
                ?: return emptyList()

        return parseTrackCollection(
            topTracks.opt("track"),
            defaultMatch = 1.0
        )
    }

    private fun readSimilarArtists(
        url: String
    ): List<LastFmSimilarArtist> {

        val root = requestJson(url)
        val similarArtists =
            root.optJSONObject("similarartists")
                ?: return emptyList()

        return when (val rawArtists = similarArtists.opt("artist")) {
            is JSONArray -> {
                buildList {
                    for (index in 0 until rawArtists.length()) {
                        rawArtists.optJSONObject(index)
                            ?.let(::parseSimilarArtist)
                            ?.let(::add)
                    }
                }
            }

            is JSONObject ->
                listOfNotNull(parseSimilarArtist(rawArtists))

            else ->
                emptyList()
        }
            .sortedByDescending { it.match }
    }

    private fun parseTrackCollection(
        rawTracks: Any?,
        defaultMatch: Double
    ): List<LastFmSimilarTrack> {

        return when (rawTracks) {
            is JSONArray -> {
                buildList {
                    for (index in 0 until rawTracks.length()) {
                        rawTracks.optJSONObject(index)
                            ?.let { parseTrack(it, defaultMatch) }
                            ?.let(::add)
                    }
                }
            }

            is JSONObject ->
                listOfNotNull(parseTrack(rawTracks, defaultMatch))

            else ->
                emptyList()
        }
    }

    private fun parseTrack(
        item: JSONObject,
        defaultMatch: Double
    ): LastFmSimilarTrack? {

        val trackName =
            item.optString("name").trim()

        if (trackName.isBlank()) {
            return null
        }

        val artistName =
            readArtistName(item.opt("artist"))

        if (artistName.isBlank()) {
            return null
        }

        val match =
            readDouble(
                value = item.opt("match"),
                fallback = defaultMatch
            )

        val mbid =
            item.optString("mbid")
                .trim()
                .takeIf { it.isNotBlank() }

        return LastFmSimilarTrack(
            artistName = artistName,
            trackName = trackName,
            match = match,
            mbid = mbid
        )
    }

    private fun parseSimilarArtist(
        item: JSONObject
    ): LastFmSimilarArtist? {

        val artistName =
            item.optString("name").trim()

        if (artistName.isBlank()) {
            return null
        }

        val match =
            readDouble(
                value = item.opt("match"),
                fallback = 0.0
            )
                .coerceIn(0.0, 1.0)

        if (match <= 0.0) {
            return null
        }

        return LastFmSimilarArtist(
            artistName = artistName,
            match = match
        )
    }

    private fun readDouble(
        value: Any?,
        fallback: Double
    ): Double {

        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun readArtistName(
        value: Any?
    ): String {

        return when (value) {
            is JSONObject -> value.optString("name").trim()
            is String -> value.trim()
            else -> ""
        }
    }

    private fun buildTrackInfoUrl(
        artist: String,
        title: String
    ): String {

        return API_URL +
            "?method=track.getinfo" +
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
            "?method=track.getsimilar" +
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
            "?method=track.getsimilar" +
            "&mbid=${encode(mbid)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&limit=$limit" +
            "&format=json"
    }

    private fun buildSimilarArtistsUrl(
        artist: String,
        limit: Int
    ): String {

        return API_URL +
            "?method=artist.getsimilar" +
            "&artist=${encode(artist)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&limit=$limit" +
            "&format=json"
    }

    private fun buildArtistTopTracksUrl(
        artist: String,
        limit: Int
    ): String {

        return API_URL +
            "?method=artist.gettoptracks" +
            "&artist=${encode(artist)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&limit=$limit" +
            "&format=json"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun ensureConfigured() {
        if (apiKey.isBlank()) {
            throw IllegalStateException(
                "Last.fm API key is not configured"
            )
        }
    }

    private fun requestJson(
        url: String
    ): JSONObject {

        val root =
            JSONObject(get(url))

        if (root.has("error")) {
            throw LastFmApiException(
                errorCode = root.optInt("error", -1),
                message = root.optString(
                    "message",
                    "Unknown Last.fm error"
                )
            )
        }

        return root
    }

    private fun get(
        url: String
    ): String {

        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )
            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            val statusCode = connection.responseCode
            val stream =
                if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body =
                if (stream != null) {
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

            if (statusCode !in 200..299) {
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
    ) {
        fun isMissingResource(): Boolean =
            errorCode == 6 || errorCode == 7
    }
}
