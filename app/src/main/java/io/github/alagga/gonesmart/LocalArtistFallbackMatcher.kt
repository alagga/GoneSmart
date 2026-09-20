package io.github.alagga.gonesmart

import android.util.Log
import java.text.Normalizer
import java.util.Locale

/**
 * Secondary local-library expansion used only when the normal
 * exact-track matcher cannot provide enough high-quality tracks.
 *
 * It deliberately works at artist level. This lets GoneSmart use
 * a different locally available song by an artist recommended by
 * the providers, or another local song by one of the seed artists.
 *
 * Artist identity includes acronym aliases, so e.g.
 * "Machine Gun Kelly" and "MGK" share the key "mgk".
 */
class LocalArtistFallbackMatcher {

    companion object {
        private const val TAG = "GoneSmart"

        private const val MIN_PROVIDER_SCORE = 0.35
        private const val PROVIDER_ARTIST_PENALTY = 0.62
        private const val SEED_ARTIST_BASE_SCORE = 0.52
        private const val MAX_TRACKS_PER_ARTIST = 4
        private const val MAX_PROVIDER_ARTISTS = 24
    }

    @Volatile
    private var cachedFingerprint: Long? = null

    @Volatile
    private var cachedArtistIndex: Map<String, List<GmmpLibraryTrack>>? = null

    fun match(
        recommendations: List<RecommendationCandidate>,
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        excludedTrackIds: Set<Long>,
        maxResults: Int
    ): List<LocalRecommendationMatch> {

        if (library.isEmpty() || maxResults <= 0) {
            return emptyList()
        }

        val artistIndex = getOrBuildArtistIndex(library)
        if (artistIndex.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<LocalRecommendationMatch>()
        val occupiedTrackIds = linkedSetOf<Long>()

        recommendations
            .asSequence()
            .filter { it.totalScore >= MIN_PROVIDER_SCORE }
            .take(MAX_PROVIDER_ARTISTS)
            .forEach { recommendation ->

                val artistTracks = findArtistTracks(
                    rawArtist = recommendation.artist,
                    index = artistIndex,
                    excludedTrackIds = excludedTrackIds + occupiedTrackIds
                )

                artistTracks
                    .take(MAX_TRACKS_PER_ARTIST)
                    .forEach { libraryTrack ->

                        val penalizedScore =
                            recommendation.totalScore * PROVIDER_ARTIST_PENALTY

                        val syntheticRecommendation =
                            recommendation.copy(
                                title = libraryTrack.track.title.orEmpty(),
                                totalScore = penalizedScore
                            )

                        result += LocalRecommendationMatch(
                            recommendation = syntheticRecommendation,
                            libraryTrack = libraryTrack,
                            matchScore = 0.92,
                            titleScore = 0.0,
                            artistScore = 1.0,
                            versionScore = 1.0,
                            finalScore = penalizedScore
                        )

                        occupiedTrackIds += libraryTrack.track.id
                    }
            }

        seeds.forEach { seed ->

            if (result.size >= maxResults) {
                return@forEach
            }

            val rawArtist = seed.track.artist
            if (rawArtist.isNullOrBlank()) {
                return@forEach
            }

            val seedScore =
                (SEED_ARTIST_BASE_SCORE * seed.weightMultiplier)
                    .coerceIn(0.0, 1.0)

            val artistTracks = findArtistTracks(
                rawArtist = rawArtist,
                index = artistIndex,
                excludedTrackIds = excludedTrackIds + occupiedTrackIds
            )

            artistTracks
                .take(MAX_TRACKS_PER_ARTIST)
                .forEach { libraryTrack ->

                    if (result.size >= maxResults) {
                        return@forEach
                    }

                    val syntheticRecommendation =
                        RecommendationCandidate(
                            artist = rawArtist,
                            title = libraryTrack.track.title.orEmpty(),
                            mbids = emptySet(),
                            contributions = emptyList(),
                            totalScore = seedScore
                        )

                    result += LocalRecommendationMatch(
                        recommendation = syntheticRecommendation,
                        libraryTrack = libraryTrack,
                        matchScore = 0.92,
                        titleScore = 0.0,
                        artistScore = 1.0,
                        versionScore = 1.0,
                        finalScore = seedScore
                    )

                    occupiedTrackIds += libraryTrack.track.id
                }
        }

        val trimmed =
            result
                .sortedByDescending { it.finalScore }
                .take(maxResults)

        Log.i(
            TAG,
            "LOCAL ARTIST FALLBACK | " +
                "providerRecommendations=${recommendations.size} | " +
                "seeds=${seeds.size} | " +
                "matches=${trimmed.size}"
        )

        return trimmed
    }

    private fun getOrBuildArtistIndex(
        library: List<GmmpLibraryTrack>
    ): Map<String, List<GmmpLibraryTrack>> {

        val fingerprint = calculateFingerprint(library)
        val currentFingerprint = cachedFingerprint
        val currentIndex = cachedArtistIndex

        if (currentFingerprint == fingerprint && currentIndex != null) {
            return currentIndex
        }

        synchronized(this) {
            val synchronizedFingerprint = cachedFingerprint
            val synchronizedIndex = cachedArtistIndex

            if (synchronizedFingerprint == fingerprint && synchronizedIndex != null) {
                return synchronizedIndex
            }

            val mutable =
                mutableMapOf<String, MutableList<GmmpLibraryTrack>>()

            library.forEach { libraryTrack ->
                artistIdentityKeys(libraryTrack.track.artist)
                    .forEach { key ->
                        mutable
                            .getOrPut(key) { mutableListOf() }
                            .add(libraryTrack)
                    }
            }

            val built =
                mutable.mapValues { (_, tracks) ->
                    tracks
                        .distinctBy { it.track.id }
                        .sortedWith(
                            compareByDescending<GmmpLibraryTrack> { it.ratingRaw }
                                .thenByDescending { it.playCount }
                                .thenBy { it.track.id }
                        )
                }

            cachedFingerprint = fingerprint
            cachedArtistIndex = built

            Log.i(
                TAG,
                "LOCAL ARTIST INDEX BUILT | " +
                    "tracks=${library.size} | keys=${built.size}"
            )

            return built
        }
    }

    private fun findArtistTracks(
        rawArtist: String?,
        index: Map<String, List<GmmpLibraryTrack>>,
        excludedTrackIds: Set<Long>
    ): List<GmmpLibraryTrack> {

        val found = linkedMapOf<Long, GmmpLibraryTrack>()

        artistIdentityKeys(rawArtist)
            .forEach { key ->
                index[key]
                    .orEmpty()
                    .forEach { track ->
                        if (track.track.id !in excludedTrackIds) {
                            found[track.track.id] = track
                        }
                    }
            }

        return found.values.toList()
    }

    private fun artistIdentityKeys(
        rawArtist: String?
    ): Set<String> {

        val raw = rawArtist?.trim().orEmpty()
        if (raw.isBlank()) {
            return emptySet()
        }

        val pieces = linkedSetOf<String>()
        pieces += raw

        raw.split(
            Regex("""\s*(?:,|;|/|\s+&\s+|\s+x\s+)\s*""", RegexOption.IGNORE_CASE)
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { pieces += it }

        val keys = linkedSetOf<String>()

        pieces.forEach { piece ->
            val normalized = comparisonKey(piece)
            if (normalized.isNotBlank()) {
                keys += normalized
            }

            acronymKey(piece)
                ?.takeIf { it.length >= 3 }
                ?.let { keys += it }
        }

        return keys
    }

    private fun acronymKey(value: String): String? {
        val words =
            Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotBlank() }

        if (words.size < 2) {
            return null
        }

        return words
            .mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("")
            .takeIf { it.isNotBlank() }
    }

    private fun comparisonKey(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")
    }

    private fun calculateFingerprint(
        library: List<GmmpLibraryTrack>
    ): Long {

        var hash = 1125899906842597L

        library.forEach { libraryTrack ->
            hash = hash * 31L + libraryTrack.track.id
            hash = hash * 31L +
                (libraryTrack.track.artist?.hashCode()?.toLong() ?: 0L)
            hash = hash * 31L + libraryTrack.ratingRaw
            hash = hash * 31L + libraryTrack.playCount
        }

        return hash
    }
}
