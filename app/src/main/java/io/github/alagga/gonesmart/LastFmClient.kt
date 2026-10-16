package io.github.alagga.gonesmart

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
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

class LastFmClient(private val apiKey: String) {

    companion object {
        private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val USER_AGENT = "GoneSmart/0.4.1 (https://github.com/alagga/GoneSmart)"

        // Bounded artist fallback: at most 1 + 3 extra requests per seed.
        private const val ARTIST_FALLBACK_MAX_ARTISTS = 3
        private const val ARTIST_FALLBACK_TRACKS_PER_ARTIST = 8
        private const val ARTIST_FALLBACK_SCORE_PENALTY = 0.78
        private const val ARTIST_FALLBACK_RANK_DECAY = 0.24

        private val TRAILING_VERSION_SUFFIX =
            Regex("\\s*[\\(\\[][^\\)\\]]+[\\)\\]]\\s*$")
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Resolves the strongest available Last.fm track identity. If getInfo
     * cannot resolve a recent/niche track, track.search gets one conservative
     * exact-title recovery attempt before the artist fallback is enabled.
     * Versioned titles deliberately return null after a failed search so the
     * caller can still try its existing base-title candidate first.
     */
    fun resolveTrack(artist: String, title: String): LastFmTrackIdentity? {
        ensureConfigured()

        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return null

        val fallbackIdentity = LastFmTrackIdentity(cleanArtist, cleanTitle, null)
        val root = try {
            requestJson(buildTrackInfoUrl(cleanArtist, cleanTitle))
        } catch (error: LastFmApiException) {
            if (error.isMissingResource()) {
                return recoverMissingTrackIdentity(cleanArtist, cleanTitle, fallbackIdentity)
            }
            throw error
        }

        val track = root.optJSONObject("track")
            ?: return recoverMissingTrackIdentity(cleanArtist, cleanTitle, fallbackIdentity)

        return LastFmTrackIdentity(
            artistName = readArtistName(track.opt("artist")).ifBlank { cleanArtist },
            trackName = track.optString("name").trim().ifBlank { cleanTitle },
            mbid = track.optString("mbid").trim().takeIf(String::isNotBlank)
        )
    }

    /**
     * Track-level similarity stays authoritative. Only an empty/missing
     * recording graph expands through similar artists and their top tracks.
     */
    fun getSimilarTracks(
        identity: LastFmTrackIdentity,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {
        ensureConfigured()
        val normalizedLimit = limit.coerceAtLeast(1)

        val direct = try {
            readSimilarTracks(
                if (!identity.mbid.isNullOrBlank()) {
                    buildSimilarTracksUrl(identity.mbid, normalizedLimit)
                } else {
                    buildSimilarTracksUrl(identity.artistName, identity.trackName, normalizedLimit)
                }
            )
        } catch (error: LastFmApiException) {
            if (error.isMissingResource()) emptyList() else throw error
        }

        return direct.takeIf(List<*>::isNotEmpty)?.take(normalizedLimit)
            ?: getArtistFallbackTracks(identity.artistName, normalizedLimit)
    }

    fun getSimilarTracks(
        artist: String,
        title: String,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {
        ensureConfigured()

        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return emptyList()

        val normalizedLimit = limit.coerceAtLeast(1)
        val direct = try {
            readSimilarTracks(buildSimilarTracksUrl(cleanArtist, cleanTitle, normalizedLimit))
        } catch (error: LastFmApiException) {
            if (error.isMissingResource()) emptyList() else throw error
        }

        return direct.takeIf(List<*>::isNotEmpty)?.take(normalizedLimit)
            ?: getArtistFallbackTracks(cleanArtist, normalizedLimit)
    }

    private fun recoverMissingTrackIdentity(
        artist: String,
        title: String,
        fallbackIdentity: LastFmTrackIdentity
    ): LastFmTrackIdentity? {
        val searched = try {
            searchTrackIdentity(artist, title)
        } catch (_: Throwable) {
            null
        }

        if (searched != null) return searched

        // Preserve GoneSmart's version-title -> base-title query cascade.
        if (TRAILING_VERSION_SUFFIX.containsMatchIn(title)) return null

        return fallbackIdentity
    }

    private fun searchTrackIdentity(artist: String, title: String): LastFmTrackIdentity? {
        val root = requestJson(buildTrackSearchUrl(artist, title, limit = 8))
        val matches = root.optJSONObject("results")
            ?.optJSONObject("trackmatches")
            ?: return null

        val candidates = jsonObjects(matches.opt("track"))
            .mapNotNull(::parseSearchIdentity)

        val requestedTitle = comparisonKey(title)
        val requestedArtist = comparisonKey(artist)

        return candidates.asSequence()
            .filter { comparisonKey(it.trackName) == requestedTitle }
            .map { candidate ->
                candidate to artistCompatibilityScore(
                    requestedArtist,
                    comparisonKey(candidate.artistName)
                )
            }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun parseSearchIdentity(item: JSONObject): LastFmTrackIdentity? {
        val track = item.optString("name").trim()
        val artist = readArtistName(item.opt("artist"))
        if (track.isBlank() || artist.isBlank()) return null

        return LastFmTrackIdentity(
            artistName = artist,
            trackName = track,
            mbid = item.optString("mbid").trim().takeIf(String::isNotBlank)
        )
    }

    private fun artistCompatibilityScore(requested: String, candidate: String): Int {
        if (requested.isBlank() || candidate.isBlank()) return 0
        if (requested == candidate) return 3

        val requestedPadded = " $requested "
        val candidatePadded = " $candidate "
        return if (
            candidatePadded.contains(requestedPadded) ||
            requestedPadded.contains(candidatePadded)
        ) 2 else 0
    }

    private fun getArtistFallbackTracks(
        artist: String,
        limit: Int
    ): List<LastFmSimilarTrack> {
        val cleanArtist = artist.trim()
        if (cleanArtist.isBlank()) return emptyList()

        val similarArtists = try {
            readSimilarArtists(
                buildSimilarArtistsUrl(cleanArtist, ARTIST_FALLBACK_MAX_ARTISTS)
            )
        } catch (error: LastFmApiException) {
            if (error.isMissingResource()) emptyList() else throw error
        }

        if (similarArtists.isEmpty()) return emptyList()

        return similarArtists
            .take(ARTIST_FALLBACK_MAX_ARTISTS)
            .flatMap { similarArtist ->
                val topTracks = try {
                    readArtistTopTracks(
                        buildArtistTopTracksUrl(
                            similarArtist.artistName,
                            ARTIST_FALLBACK_TRACKS_PER_ARTIST
                        )
                    )
                } catch (_: Throwable) {
                    emptyList()
                }

                val denominator = (topTracks.size - 1).coerceAtLeast(1).toDouble()
                topTracks.mapIndexedNotNull { index, track ->
                    val rankProgress = index.toDouble() / denominator
                    val rankFactor = 1.0 - (ARTIST_FALLBACK_RANK_DECAY * rankProgress)
                    val score = (
                        similarArtist.match *
                            ARTIST_FALLBACK_SCORE_PENALTY *
                            rankFactor
                        ).coerceIn(0.0, 1.0)

                    track.copy(match = score).takeIf { score > 0.0 }
                }
            }
            .distinctBy {
                comparisonKey(it.artistName) + "\u0000" + comparisonKey(it.trackName)
            }
            .sortedByDescending(LastFmSimilarTrack::match)
            .take(limit)
    }

    private fun readSimilarTracks(url: String): List<LastFmSimilarTrack> {
        val similarTracks = requestJson(url).optJSONObject("similartracks")
            ?: return emptyList()
        return parseTrackCollection(similarTracks.opt("track"), defaultMatch = 0.0)
            .sortedByDescending(LastFmSimilarTrack::match)
    }

    private fun readArtistTopTracks(url: String): List<LastFmSimilarTrack> {
        val topTracks = requestJson(url).optJSONObject("toptracks")
            ?: return emptyList()
        return parseTrackCollection(topTracks.opt("track"), defaultMatch = 1.0)
    }

    private fun readSimilarArtists(url: String): List<LastFmSimilarArtist> {
        val similarArtists = requestJson(url).optJSONObject("similarartists")
            ?: return emptyList()
        return jsonObjects(similarArtists.opt("artist"))
            .mapNotNull(::parseSimilarArtist)
            .sortedByDescending(LastFmSimilarArtist::match)
    }

    private fun parseTrackCollection(
        rawTracks: Any?,
        defaultMatch: Double
    ): List<LastFmSimilarTrack> =
        jsonObjects(rawTracks).mapNotNull { parseTrack(it, defaultMatch) }

    private fun parseTrack(item: JSONObject, defaultMatch: Double): LastFmSimilarTrack? {
        val track = item.optString("name").trim()
        val artist = readArtistName(item.opt("artist"))
        if (track.isBlank() || artist.isBlank()) return null

        return LastFmSimilarTrack(
            artistName = artist,
            trackName = track,
            match = readDouble(item.opt("match"), defaultMatch),
            mbid = item.optString("mbid").trim().takeIf(String::isNotBlank)
        )
    }

    private fun parseSimilarArtist(item: JSONObject): LastFmSimilarArtist? {
        val artist = item.optString("name").trim()
        val match = readDouble(item.opt("match"), 0.0).coerceIn(0.0, 1.0)
        if (artist.isBlank() || match <= 0.0) return null
        return LastFmSimilarArtist(artist, match)
    }

    private fun jsonObjects(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                value.optJSONObject(index)?.let(::add)
            }
        }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    private fun readDouble(value: Any?, fallback: Double): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: fallback
        else -> fallback
    }

    private fun readArtistName(value: Any?): String = when (value) {
        is JSONObject -> value.optString("name").trim()
        is String -> value.trim()
        else -> ""
    }

    private fun comparisonKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun buildTrackInfoUrl(artist: String, title: String): String =
        API_URL +
            "?method=track.getinfo" +
            "&artist=${encode(artist)}" +
            "&track=${encode(title)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&format=json"

    private fun buildTrackSearchUrl(artist: String, title: String, limit: Int): String =
        API_URL +
            "?method=track.search" +
            "&artist=${encode(artist)}" +
            "&track=${encode(title)}" +
            "&api_key=${encode(apiKey)}" +
            "&limit=$limit" +
            "&format=json"

    private fun buildSimilarTracksUrl(artist: String, title: String, limit: Int): String =
        API_URL +
            "?method=track.getsimilar" +
            "&artist=${encode(artist)}" +
            "&track=${encode(title)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&limit=$limit" +
            "&format=json"

    private fun buildSimilarTracksUrl(mbid: String, limit: Int): String =
        API_URL +
            "?method=track.getsimilar" +
            "&mbid=${encode(mbid)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&limit=$limit" +
            "&format=json"

    private fun buildSimilarArtistsUrl(artist: String, limit: Int): String =
        API_URL +
            "?method=artist.getsimilar" +
            "&artist=${encode(artist)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&limit=$limit" +
            "&format=json"

    private fun buildArtistTopTracksUrl(artist: String, limit: Int): String =
        API_URL +
            "?method=artist.gettoptracks" +
            "&artist=${encode(artist)}" +
            "&api_key=${encode(apiKey)}" +
            "&autocorrect=1" +
            "&limit=$limit" +
            "&format=json"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun ensureConfigured() {
        if (apiKey.isBlank()) error("Last.fm API key is not configured")
    }

    private fun requestJson(url: String): JSONObject {
        val root = JSONObject(get(url))
        if (root.has("error")) {
            throw LastFmApiException(
                errorCode = root.optInt("error", -1),
                message = root.optString("message", "Unknown Last.fm error")
            )
        }
        return root
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use(BufferedReader::readText)
            } else {
                ""
            }

            if (statusCode !in 200..299) {
                error("Last.fm HTTP $statusCode: $body")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private class LastFmApiException(
        val errorCode: Int,
        message: String
    ) : Exception("Last.fm error $errorCode: $message") {
        fun isMissingResource(): Boolean = errorCode == 6 || errorCode == 7
    }
}
