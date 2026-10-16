package io.github.alagga.gonesmart

import android.util.Log
import java.text.Normalizer
import java.util.Locale

internal data class ExpandedArtistSignal(
    val libraryTrack: GmmpLibraryTrack,
    val providerScore: Double,
    val remoteArtist: String
)

internal data class LocalArtistExpansionMetrics(
    val indexCacheHit: Boolean,
    val libraryTracks: Int,
    val indexedKeys: Int,
    val artists: Int,
    val signals: Int,
    val indexMs: Long,
    val expansionMs: Long,
    val totalMs: Long
)

/** Expand an artist-level provider signal only into already-local GMMP tracks. */
internal class LocalArtistSignalExpander {
    companion object {
        private const val TAG = "GoneSmart"
    }

    private data class ArtistIndexCache(
        val library: List<GmmpLibraryTrack>,
        val index: Map<String, List<GmmpLibraryTrack>>
    )

    /*
     * GmmpLibraryReader deliberately reuses the same immutable-ish snapshot
     * while its revision cache is valid and returns a new List instance after
     * a real refresh. Reference identity therefore gives us a zero-scan cache
     * key without hashing all tracks again on every artist fallback.
     */
    @Volatile
    private var artistIndexCache: ArtistIndexCache? = null

    @Volatile
    internal var lastMetrics: LocalArtistExpansionMetrics? = null
        private set

    fun expand(
        artists: List<ListenBrainzSimilarArtist>,
        library: List<GmmpLibraryTrack>,
        excludedTrackIds: Set<Long>,
        maxArtists: Int = 12,
        maxTracksPerArtist: Int = 3
    ): List<ExpandedArtistSignal> {
        if (artists.isEmpty() || library.isEmpty()) return emptyList()

        val totalStarted = System.nanoTime()
        val indexStarted = System.nanoTime()
        val (index, indexCacheHit) = indexFor(library)
        val indexMs = elapsedMs(indexStarted)

        val expansionStarted = System.nanoTime()
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
        val result = bestByTrack.values.sortedByDescending { it.providerScore }
        val expansionMs = elapsedMs(expansionStarted)
        val metrics = LocalArtistExpansionMetrics(
            indexCacheHit = indexCacheHit,
            libraryTracks = library.size,
            indexedKeys = index.size,
            artists = artists.take(maxArtists).size,
            signals = result.size,
            indexMs = indexMs,
            expansionMs = expansionMs,
            totalMs = elapsedMs(totalStarted)
        )
        lastMetrics = metrics

        // android.util.Log is unavailable in plain JVM tests; runtime logging
        // must never make the pure expansion helper fail there.
        runCatching {
            Log.i(
                TAG,
                "LOCAL ARTIST EXPANSION | library=${metrics.libraryTracks} | " +
                    "artistIndex=${if (metrics.indexCacheHit) "hit" else "rebuilt"} | " +
                    "keys=${metrics.indexedKeys} | artists=${metrics.artists} | " +
                    "signals=${metrics.signals} | indexMs=${metrics.indexMs} | " +
                    "expansionMs=${metrics.expansionMs} | totalMs=${metrics.totalMs}"
            )
        }

        return result
    }

    private fun indexFor(
        library: List<GmmpLibraryTrack>
    ): Pair<Map<String, List<GmmpLibraryTrack>>, Boolean> {
        artistIndexCache?.let { cache ->
            if (cache.library === library) return cache.index to true
        }

        synchronized(this) {
            artistIndexCache?.let { cache ->
                if (cache.library === library) return cache.index to true
            }

            val mutableIndex = linkedMapOf<String, MutableList<GmmpLibraryTrack>>()
            for (track in library) {
                artistKeys(track.track.artist).forEach { key ->
                    mutableIndex.getOrPut(key) { mutableListOf() }.add(track)
                }
            }
            mutableIndex.values.forEach { tracks ->
                tracks.sortWith(
                    compareByDescending<GmmpLibraryTrack> { it.ratingRaw }
                        .thenByDescending { it.playCount }
                        .thenBy { it.track.id }
                )
            }
            val built = mutableIndex.mapValuesTo(linkedMapOf()) { (_, tracks) ->
                tracks.toList()
            }
            artistIndexCache = ArtistIndexCache(library = library, index = built)
            return built to false
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs).coerceAtLeast(0L) / 1_000_000L

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
