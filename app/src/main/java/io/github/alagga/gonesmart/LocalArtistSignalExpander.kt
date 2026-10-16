package io.github.alagga.gonesmart

import java.text.Normalizer
import java.util.Locale

internal data class ExpandedArtistSignal(
    val libraryTrack: GmmpLibraryTrack,
    val providerScore: Double,
    val remoteArtist: String
)

/** Expand an artist-level provider signal only into already-local GMMP tracks. */
internal class LocalArtistSignalExpander {
    fun expand(
        artists: List<ListenBrainzSimilarArtist>,
        library: List<GmmpLibraryTrack>,
        excludedTrackIds: Set<Long>,
        maxArtists: Int = 12,
        maxTracksPerArtist: Int = 3
    ): List<ExpandedArtistSignal> {
        if (artists.isEmpty() || library.isEmpty()) return emptyList()

        val index = linkedMapOf<String, MutableList<GmmpLibraryTrack>>()
        for (track in library) {
            artistKeys(track.track.artist).forEach { key ->
                index.getOrPut(key) { mutableListOf() }.add(track)
            }
        }
        index.values.forEach { tracks ->
            tracks.sortWith(
                compareByDescending<GmmpLibraryTrack> { it.ratingRaw }
                    .thenByDescending { it.playCount }
                    .thenBy { it.track.id }
            )
        }

        val bestByTrack = linkedMapOf<Long, ExpandedArtistSignal>()
        for (signal in artists.take(maxArtists)) {
            val found = linkedMapOf<Long, GmmpLibraryTrack>()
            artistKeys(signal.artistName).forEach { key ->
                index[key].orEmpty().forEach { track ->
                    if (track.track.id !in excludedTrackIds) found[track.track.id] = track
                }
            }
            found.values.take(maxTracksPerArtist).forEachIndexed { rank, track ->
                val score = (signal.score * (1.0 - rank * 0.08)).coerceIn(0.0, 1.0)
                val expanded = ExpandedArtistSignal(track, score, signal.artistName)
                val previous = bestByTrack[track.track.id]
                if (previous == null || expanded.providerScore > previous.providerScore) {
                    bestByTrack[track.track.id] = expanded
                }
            }
        }
        return bestByTrack.values.sortedByDescending { it.providerScore }
    }

    private fun artistKeys(rawArtist: String?): Set<String> {
        val raw = rawArtist?.trim().orEmpty()
        if (raw.isBlank()) return emptySet()
        val pieces = linkedSetOf(raw)
        raw.split(Regex("""\s*(?:,|;|/|\s+&\s+|\s+x\s+)\s*""", RegexOption.IGNORE_CASE))
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(pieces::add)
        return pieces.flatMapTo(linkedSetOf()) { piece ->
            buildList {
                comparisonKey(piece).takeIf(String::isNotBlank)?.let(::add)
                acronymKey(piece)?.takeIf { it.length >= 3 }?.let(::add)
            }
        }
    }

    private fun acronymKey(value: String): String? {
        val words = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
        if (words.size < 2) return null
        return words.mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("")
            .takeIf(String::isNotBlank)
    }

    private fun comparisonKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")
}
