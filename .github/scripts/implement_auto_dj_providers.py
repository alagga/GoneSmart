#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(rel, old, new):
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{rel}: expected exactly one literal match, got {count}: {old[:120]!r}")
    write(rel, text.replace(old, new, 1))


def regex_once(rel, pattern, replacement, flags=0):
    text = read(rel)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{rel}: expected exactly one regex match, got {count}: {pattern[:120]!r}")
    write(rel, updated)


# ---------------------------------------------------------------------------
# Provider contract + scoring
# ---------------------------------------------------------------------------
write("app/src/main/java/io/github/alagga/gonesmart/RecommendationProviderSource.kt", r'''package io.github.alagga.gonesmart

enum class RecommendationCapability {
    TRACK_SIMILARITY,
    ARTIST_SIMILARITY,
    LOCAL_AFFINITY
}

enum class RecommendationAuthentication {
    NONE,
    APPLICATION_KEY,
    USER_OPTIONAL,
    CLIENT_CREDENTIALS
}

data class RecommendationProviderDescriptor(
    val id: RecommendationProvider,
    val capabilities: Set<RecommendationCapability>,
    val authentication: RecommendationAuthentication,
    val networkRequired: Boolean,
    val requestBudgetPerSeed: Int,
    val experimental: Boolean = false
)

interface RecommendationProviderSource {
    val descriptor: RecommendationProviderDescriptor
}

object RecommendationProviderRegistry {
    private val descriptors = mapOf(
        RecommendationProvider.LISTENBRAINZ to RecommendationProviderDescriptor(
            id = RecommendationProvider.LISTENBRAINZ,
            capabilities = setOf(
                RecommendationCapability.TRACK_SIMILARITY,
                RecommendationCapability.ARTIST_SIMILARITY
            ),
            authentication = RecommendationAuthentication.NONE,
            networkRequired = true,
            requestBudgetPerSeed = 8
        ),
        RecommendationProvider.LASTFM to RecommendationProviderDescriptor(
            id = RecommendationProvider.LASTFM,
            capabilities = setOf(
                RecommendationCapability.TRACK_SIMILARITY,
                RecommendationCapability.ARTIST_SIMILARITY
            ),
            authentication = RecommendationAuthentication.APPLICATION_KEY,
            networkRequired = true,
            requestBudgetPerSeed = 8
        ),
        RecommendationProvider.LOCAL_PLAYLIST to RecommendationProviderDescriptor(
            id = RecommendationProvider.LOCAL_PLAYLIST,
            capabilities = setOf(RecommendationCapability.LOCAL_AFFINITY),
            authentication = RecommendationAuthentication.NONE,
            networkRequired = false,
            requestBudgetPerSeed = 0
        )
    )

    fun require(id: RecommendationProvider): RecommendationProviderDescriptor =
        descriptors[id] ?: error("No provider descriptor for $id")

    fun all(): List<RecommendationProviderDescriptor> = descriptors.values.toList()
}
''')

write("app/src/main/java/io/github/alagga/gonesmart/RecommendationScoring.kt", r'''package io.github.alagga.gonesmart

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

internal object RecommendationScoring {
    /**
     * Independent providers confirming the same logical track are stronger
     * evidence than repeated rows from one provider. Provider/seed duplicate
     * suppression happens in RecommendationAggregator before this multiplier.
     */
    fun corroborationMultiplier(providerCount: Int): Double {
        val additionalProviders = (providerCount - 1).coerceIn(0, 2)
        return 1.0 + (additionalProviders * 0.12)
    }
}

internal object PlaylistAffinityScoring {
    private const val REFERENCE_PLAYLIST_SIZE = 25.0
    private const val MIN_PLAYLIST_WEIGHT = 0.08
    private const val MAX_SCORE = 0.95

    fun playlistWeight(playlistSize: Int): Double {
        if (playlistSize <= 1) return 0.0
        val denominator = max(REFERENCE_PLAYLIST_SIZE, playlistSize.toDouble())
        return sqrt(REFERENCE_PLAYLIST_SIZE / denominator)
            .coerceAtLeast(MIN_PLAYLIST_WEIGHT)
    }

    fun score(sharedPlaylistSizes: Collection<Int>): Double {
        if (sharedPlaylistSizes.isEmpty()) return 0.0
        val evidence = sharedPlaylistSizes.sumOf(::playlistWeight)
        if (evidence <= 0.0) return 0.0

        // Saturating evidence: one focused playlist is useful, multiple
        // independent playlists become much stronger without exploding.
        val base = 1.0 - exp(-evidence)
        val corroboration = 1.0 +
            ((sharedPlaylistSizes.size - 1).coerceIn(0, 3) * 0.08)
        return (base * corroboration).coerceIn(0.0, MAX_SCORE)
    }
}
''')

# ---------------------------------------------------------------------------
# Read-only native playlist catalog + local affinity provider
# ---------------------------------------------------------------------------
write("app/src/main/java/io/github/alagga/gonesmart/GmmpPlaylistCatalogReader.kt", r'''package io.github.alagga.gonesmart

import android.database.Cursor
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Read-only enumeration of GMMP's indexed normal playlist files.
 *
 * playlist_file_table ownership is already established by GMMP's generated
 * Room adapters. We deliberately discover the path/URI column from row values
 * once per live cache instead of depending on an R8 field or guessed column
 * name. No playlist DB row is ever written by GoneSmart.
 */
internal class GmmpPlaylistCatalogReader {
    companion object {
        private const val TAG = "GoneSmart"
        private const val CACHE_MAX_AGE_MS = 2L * 60L * 1000L
        private const val SQL = "SELECT * FROM playlist_file_table"
    }

    @Volatile private var cachedPaths: List<String>? = null
    @Volatile private var cachedAtElapsedMs: Long = 0L
    @Volatile private var reportedColumn: String? = null

    fun read(autoDjInstance: Any, forceRefresh: Boolean = false): List<String> {
        val now = SystemClock.elapsedRealtime()
        cachedPaths?.let { paths ->
            if (!forceRefresh && now - cachedAtElapsedMs <= CACHE_MAX_AGE_MS) {
                return paths
            }
        }

        synchronized(this) {
            val synchronizedNow = SystemClock.elapsedRealtime()
            cachedPaths?.let { paths ->
                if (!forceRefresh &&
                    synchronizedNow - cachedAtElapsedMs <= CACHE_MAX_AGE_MS
                ) return paths
            }

            val paths = runCatching {
                GmmpReadOnlySql.query(autoDjInstance, SQL) { cursor ->
                    readPathColumn(cursor)
                }
            }.onFailure {
                Log.w(TAG, "PLAYLIST AFFINITY | normal playlist catalog unavailable", it)
            }.getOrDefault(emptyList())

            cachedPaths = paths
            cachedAtElapsedMs = synchronizedNow
            return paths
        }
    }

    private fun readPathColumn(cursor: Cursor): List<String> {
        if (cursor.columnCount <= 0) return emptyList()
        val values = Array(cursor.columnCount) { mutableListOf<String>() }

        while (cursor.moveToNext()) {
            for (column in 0 until cursor.columnCount) {
                val value = runCatching { cursor.getString(column) }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                if (value.isNotBlank()) values[column] += value
            }
        }

        data class Candidate(
            val column: Int,
            val playlistRefs: Int,
            val pathLike: Int
        )

        val candidates = values.mapIndexed { index, columnValues ->
            Candidate(
                column = index,
                playlistRefs = columnValues.count(::isPlaylistReference),
                pathLike = columnValues.count { isPlaylistReference(it) && isPathLike(it) }
            )
        }.filter { it.playlistRefs > 0 && it.pathLike > 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.pathLike }
                    .thenByDescending { it.playlistRefs }
            )

        val best = candidates.firstOrNull() ?: return emptyList()
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null &&
            best.pathLike == runnerUp.pathLike &&
            best.playlistRefs == runnerUp.playlistRefs
        ) {
            Log.w(TAG, "PLAYLIST AFFINITY | playlist path column is ambiguous")
            return emptyList()
        }

        val columnName = cursor.getColumnName(best.column)
        if (reportedColumn != columnName) {
            reportedColumn = columnName
            Log.i(
                TAG,
                "PLAYLIST AFFINITY | native catalog path column=$columnName | " +
                    "rows=${values[best.column].size}"
            )
        }

        return values[best.column]
            .asSequence()
            .filter(::isPlaylistReference)
            .mapNotNull(::normalizePlaylistReference)
            .distinct()
            .toList()
    }

    private fun normalizePlaylistReference(value: String): String? {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("file:", ignoreCase = true) ->
                runCatching { File(URI(trimmed)).absolutePath }.getOrNull()
            trimmed.startsWith("content:", ignoreCase = true) -> trimmed
            File(trimmed).isAbsolute -> trimmed
            else -> null
        }
    }

    private fun isPathLike(value: String): Boolean =
        value.startsWith("/") ||
            value.startsWith("file:", ignoreCase = true) ||
            value.startsWith("content:", ignoreCase = true)

    private fun isPlaylistReference(value: String): Boolean {
        val clean = value.substringBefore('?').substringBefore('#')
            .lowercase(Locale.ROOT)
        return clean.endsWith(".m3u") || clean.endsWith(".m3u8") ||
            clean.endsWith(".pls") || clean.endsWith(".wpl")
    }
}
''')

write("app/src/main/java/io/github/alagga/gonesmart/LocalPlaylistAffinityProvider.kt", r'''package io.github.alagga.gonesmart

import android.util.Log
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class PlaylistAffinitySignal(
    val seed: RecommendationSeed,
    val libraryTrack: GmmpLibraryTrack,
    val score: Double,
    val sharedPlaylistCount: Int
)

internal data class PlaylistAffinityMetrics(
    val catalogPlaylists: Int = 0,
    val usablePlaylists: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val memberships: Int = 0,
    val seedsMatched: Int = 0,
    val signals: Int = 0
)

internal data class PlaylistAffinityBatch(
    val signals: List<PlaylistAffinitySignal>,
    val metrics: PlaylistAffinityMetrics
)

/**
 * Personal collaborative-filtering signal from the user's normal GMMP
 * playlists. Smart Playlists are intentionally excluded: their broad dynamic
 * rules do not imply that every member is musically related.
 *
 * Only M3U/M3U8 membership is interpreted here. Other normal playlist formats
 * fail soft until their native read contract is mapped. Files are never
 * modified. Parsed membership is cached by path + mtime + length and rebuilt
 * only when the library/path fingerprint changes.
 */
internal class LocalPlaylistAffinityProvider(
    private val catalogReader: GmmpPlaylistCatalogReader = GmmpPlaylistCatalogReader()
) : RecommendationProviderSource {
    override val descriptor =
        RecommendationProviderRegistry.require(RecommendationProvider.LOCAL_PLAYLIST)

    companion object {
        private const val TAG = "GoneSmart"
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
        private const val MAX_TRACK_REFERENCES = 10_000
        private const val DEFAULT_MAX_RESULTS_PER_SEED = 80
    }

    private data class CacheEntry(
        val modified: Long,
        val length: Long,
        val libraryFingerprint: Long,
        val trackIds: Set<Long>
    )

    private val cache = linkedMapOf<String, CacheEntry>()

    fun collect(
        autoDjInstance: Any,
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        maxResultsPerSeed: Int = DEFAULT_MAX_RESULTS_PER_SEED
    ): PlaylistAffinityBatch {
        if (seeds.isEmpty() || library.isEmpty() || maxResultsPerSeed <= 0) {
            return PlaylistAffinityBatch(emptyList(), PlaylistAffinityMetrics())
        }

        val catalog = catalogReader.read(autoDjInstance)
        if (catalog.isEmpty()) {
            return PlaylistAffinityBatch(
                emptyList(),
                PlaylistAffinityMetrics(catalogPlaylists = 0)
            )
        }

        val fingerprint = libraryFingerprint(library)
        val byPath = buildLibraryPathIndex(library)
        val byId = library.associateBy { it.track.id }
        if (byPath.isEmpty()) {
            return PlaylistAffinityBatch(
                emptyList(),
                PlaylistAffinityMetrics(catalogPlaylists = catalog.size)
            )
        }

        var cacheHits = 0
        var cacheMisses = 0
        val memberships = linkedMapOf<String, Set<Long>>()
        val activePaths = linkedSetOf<String>()

        for (raw in catalog) {
            val file = normalPlaylistFile(raw) ?: continue
            val path = runCatching { file.canonicalPath }.getOrNull() ?: continue
            activePaths += path
            if (!file.isFile || !file.canRead() || file.length() > MAX_PLAYLIST_BYTES) continue

            val cached = synchronized(cache) { cache[path] }
            val trackIds = if (
                cached != null &&
                cached.modified == file.lastModified() &&
                cached.length == file.length() &&
                cached.libraryFingerprint == fingerprint
            ) {
                cacheHits++
                cached.trackIds
            } else {
                cacheMisses++
                val parsed = parseM3u(file, byPath) ?: continue
                val entry = CacheEntry(
                    modified = file.lastModified(),
                    length = file.length(),
                    libraryFingerprint = fingerprint,
                    trackIds = parsed
                )
                synchronized(cache) { cache[path] = entry }
                parsed
            }

            if (trackIds.size >= 2) memberships[path] = trackIds
        }

        synchronized(cache) {
            cache.keys.retainAll(activePaths)
        }

        val signals = mutableListOf<PlaylistAffinitySignal>()
        var seedsMatched = 0

        for (seed in seeds) {
            val shared = memberships.values.filter { seed.track.id in it }
            if (shared.isEmpty()) continue
            seedsMatched++

            val candidateEvidence = linkedMapOf<Long, MutableList<Int>>()
            for (playlist in shared) {
                val playlistSize = playlist.size
                for (trackId in playlist) {
                    if (trackId == seed.track.id) continue
                    candidateEvidence
                        .getOrPut(trackId) { mutableListOf() }
                        .add(playlistSize)
                }
            }

            candidateEvidence
                .mapNotNull { (trackId, sizes) ->
                    val track = byId[trackId] ?: return@mapNotNull null
                    val score = PlaylistAffinityScoring.score(sizes)
                    if (score <= 0.0) return@mapNotNull null
                    PlaylistAffinitySignal(
                        seed = seed,
                        libraryTrack = track,
                        score = score,
                        sharedPlaylistCount = sizes.size
                    )
                }
                .sortedWith(
                    compareByDescending<PlaylistAffinitySignal> { it.score }
                        .thenByDescending { it.sharedPlaylistCount }
                        .thenBy { it.libraryTrack.track.id }
                )
                .take(maxResultsPerSeed)
                .let(signals::addAll)
        }

        val metrics = PlaylistAffinityMetrics(
            catalogPlaylists = catalog.size,
            usablePlaylists = memberships.size,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            memberships = memberships.values.sumOf { it.size },
            seedsMatched = seedsMatched,
            signals = signals.size
        )

        Log.i(
            TAG,
            "PLAYLIST AFFINITY | catalog=${metrics.catalogPlaylists} | " +
                "usable=${metrics.usablePlaylists} | memberships=${metrics.memberships} | " +
                "cacheHits=${metrics.cacheHits} | cacheMisses=${metrics.cacheMisses} | " +
                "seedHits=${metrics.seedsMatched}/${seeds.size} | signals=${metrics.signals}"
        )

        return PlaylistAffinityBatch(signals, metrics)
    }

    private fun normalPlaylistFile(raw: String): File? {
        val value = raw.trim()
        val file = when {
            value.startsWith("file:", ignoreCase = true) ->
                runCatching { File(URI(value)) }.getOrNull()
            value.startsWith("content:", ignoreCase = true) -> null
            else -> File(value).takeIf { it.isAbsolute }
        } ?: return null

        val name = file.name.lowercase(Locale.ROOT)
        return file.takeIf { name.endsWith(".m3u") || name.endsWith(".m3u8") }
    }

    private fun parseM3u(
        playlist: File,
        libraryByPath: Map<String, Long>
    ): Set<Long>? {
        val bytes = runCatching { playlist.readBytes() }.getOrNull() ?: return null
        if (bytes.size > MAX_PLAYLIST_BYTES) return null
        val offset = if (
            bytes.size >= 3 && bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
        ) 3 else 0
        val text = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        }.getOrNull() ?: return null

        val ids = linkedSetOf<Long>()
        var references = 0
        for (rawLine in text.lineSequence()) {
            if (references >= MAX_TRACK_REFERENCES) break
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isBlank() || line.startsWith("#")) continue
            references++
            val key = normalizeTrackReference(playlist.parentFile, line) ?: continue
            libraryByPath[key]?.let(ids::add)
        }
        return ids
    }

    private fun buildLibraryPathIndex(library: List<GmmpLibraryTrack>): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        for (entry in library) {
            val raw = entry.track.path ?: continue
            normalizeLibraryPath(raw)?.let { result[it] = entry.track.id }
        }
        return result
    }

    private fun normalizeLibraryPath(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("content:", ignoreCase = true)) return null
        val file = if (value.startsWith("file:", ignoreCase = true)) {
            runCatching { File(URI(value)) }.getOrNull()
        } else {
            File(value).takeIf { it.isAbsolute }
        } ?: return null
        return pathKey(file)
    }

    private fun normalizeTrackReference(parent: File, raw: String): String? {
        if (
            raw.startsWith("content:", ignoreCase = true) ||
            raw.startsWith("http:", ignoreCase = true) ||
            raw.startsWith("https:", ignoreCase = true)
        ) return null

        val file = if (raw.startsWith("file:", ignoreCase = true)) {
            runCatching { File(URI(raw)) }.getOrNull()
        } else {
            val candidate = File(raw)
            if (candidate.isAbsolute) candidate else File(parent, raw)
        } ?: return null
        return pathKey(file)
    }

    private fun pathKey(file: File): String? =
        runCatching { file.canonicalPath.lowercase(Locale.ROOT) }.getOrNull()

    private fun libraryFingerprint(library: List<GmmpLibraryTrack>): Long {
        var hash = 1125899906842597L
        for (entry in library) {
            hash = hash * 31L + entry.track.id
            hash = hash * 31L + (entry.track.path?.hashCode()?.toLong() ?: 0L)
        }
        return hash
    }
}
''')

write("app/src/main/java/io/github/alagga/gonesmart/LocalArtistSignalExpander.kt", r'''package io.github.alagga.gonesmart

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
''')

# ---------------------------------------------------------------------------
# ListenBrainz: recording similarity + artist-level fallback source
# ---------------------------------------------------------------------------
write("app/src/main/java/io/github/alagga/gonesmart/ListenBrainzClient.kt", r'''package io.github.alagga.gonesmart

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
        private const val RECORDING_SEARCH_URL =
            "https://labs.api.listenbrainz.org/recording-search/json"
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
        return parseRecordingSearch(response.body)
    }

    fun getSimilarRecordings(recordingMbid: String): List<ListenBrainzSimilarTrack> {
        val request = JSONArray().put(
            JSONObject()
                .put("recording_mbids", JSONArray().put(recordingMbid))
                .put("algorithm", SIMILARITY_ALGORITHM)
        )
        val response = postJson(SIMILAR_RECORDINGS_URL, request)
        requireSuccess("Similar-recordings request", response)
        return parseSimilarRecordings(response.body, recordingMbid)
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
                        artistMbids = parseStringList(item.opt("artist_credit_mbids"))
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
        } finally {
            connection.disconnect()
        }
    }
}
''')

# ---------------------------------------------------------------------------
# Existing provider model + aggregator
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/RecommendationCandidate.kt",
    "enum class RecommendationProvider {\n    LISTENBRAINZ,\n    LASTFM\n}",
    "enum class RecommendationProvider {\n    LISTENBRAINZ,\n    LASTFM,\n    LOCAL_PLAYLIST\n}"
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/RecommendationAggregator.kt",
    '''                    totalScore =\n                        mappedContributions\n                            .sumOf {\n                                it.weightedScore\n                            }\n''',
    '''                    totalScore =\n                        mappedContributions\n                            .sumOf {\n                                it.weightedScore\n                            } * RecommendationScoring.corroborationMultiplier(\n                                mappedContributions\n                                    .map { it.provider }\n                                    .distinct()\n                                    .size\n                            )\n'''
)

# ---------------------------------------------------------------------------
# Last.fm: keep identity retries authoritative, artist fallback only afterwards
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/LastFmClient.kt",
    "class LastFmClient(private val apiKey: String) {",
    '''class LastFmClient(private val apiKey: String) : RecommendationProviderSource {\n    override val descriptor =\n        RecommendationProviderRegistry.require(RecommendationProvider.LASTFM)\n'''
)

lastfm = read("app/src/main/java/io/github/alagga/gonesmart/LastFmClient.kt")
pattern = re.compile(
    r'''    /\*\*\n     \* Track-level similarity stays authoritative\.[\s\S]*?\n    fun getSimilarTracks\(\n        identity: LastFmTrackIdentity,\n        limit: Int = 50\n    \): List<LastFmSimilarTrack> \{[\s\S]*?\n    \}\n\n(?=    fun getSimilarTracks\(\n        artist: String,)'''
)
replacement = r'''    /** Track-level similarity only; no artist fallback is triggered here. */
    fun getSimilarTracksDirect(
        identity: LastFmTrackIdentity,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {
        ensureConfigured()
        val normalizedLimit = limit.coerceAtLeast(1)
        return try {
            readSimilarTracks(
                if (!identity.mbid.isNullOrBlank()) {
                    buildSimilarTracksUrl(identity.mbid, normalizedLimit)
                } else {
                    buildSimilarTracksUrl(identity.artistName, identity.trackName, normalizedLimit)
                }
            ).take(normalizedLimit)
        } catch (error: LastFmApiException) {
            if (error.isMissingResource()) emptyList() else throw error
        }
    }

    /** Compatibility helper; provider orchestration should prefer direct then fallback. */
    fun getSimilarTracks(
        identity: LastFmTrackIdentity,
        limit: Int = 50
    ): List<LastFmSimilarTrack> {
        val direct = getSimilarTracksDirect(identity, limit)
        return direct.takeIf(List<*>::isNotEmpty)
            ?: getArtistFallbackTracks(identity.artistName, limit.coerceAtLeast(1))
    }

'''
lastfm, count = pattern.subn(replacement, lastfm, count=1)
if count != 1:
    raise RuntimeError(f"LastFmClient: direct method rewrite matched {count}")
lastfm = lastfm.replace(
    "    private fun getArtistFallbackTracks(\n",
    "    fun getArtistFallbackTracks(\n",
    1
)
write("app/src/main/java/io/github/alagga/gonesmart/LastFmClient.kt", lastfm)

# ---------------------------------------------------------------------------
# GoneSmartModule integration
# ---------------------------------------------------------------------------
module = read("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")

def module_replace(old, new, count_expected=1):
    global module
    count = module.count(old)
    if count != count_expected:
        raise RuntimeError(f"GoneSmartModule literal expected {count_expected}, got {count}: {old[:120]!r}")
    module = module.replace(old, new, count_expected)

module_replace(
    '''        private const val BROAD_LISTENBRAINZ_IDENTITIES_TO_USE =\n            3\n\n        private const val MERGED_RESULTS_TO_LOG =\n''',
    '''        private const val BROAD_LISTENBRAINZ_IDENTITIES_TO_USE =\n            3\n\n        private const val LISTENBRAINZ_ARTIST_FALLBACK_PENALTY =\n            0.72\n\n        private const val MERGED_RESULTS_TO_LOG =\n'''
)

module_replace(
    '''    private val gmmpLibraryReader =\n        GmmpLibraryReader()\n\n    private val localLibraryMatcher =\n''',
    '''    private val gmmpLibraryReader =\n        GmmpLibraryReader()\n\n    private val localPlaylistAffinityProvider =\n        LocalPlaylistAffinityProvider()\n\n    private val localArtistSignalExpander =\n        LocalArtistSignalExpander()\n\n    private val localLibraryMatcher =\n'''
)

# Startup/background prewarm is useful offline now because LOCAL_PLAYLIST is local.
module_replace(
    '''        if (!options.enabled) return\n        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) return\n''',
    '''        if (!options.enabled) return\n        // Local Playlist Affinity can prewarm a useful pool without network.\n'''
)

background_guard = '''        if (\n            networkStateReader\n                .getState() !=\n            GoneSmartNetworkState.ONLINE\n        ) {\n\n            return\n        }\n\n'''
if module.count(background_guard) != 1:
    raise RuntimeError(f"GoneSmartModule background network guard count={module.count(background_guard)}")
module = module.replace(
    background_guard,
    '''        // Do not gate background refill on connectivity: the local\n        // Playlist Affinity provider remains productive offline. Network\n        // providers are gated inside collectSeedContributions().\n\n''',
    1
)

# Remove the early offline escape so the background pipeline gets one chance to
# fill from normal local playlists. The existing post-fill branch still falls
# back to native GMMP immediately when local recommendations are unavailable.
offline_pattern = re.compile(
    r'''            if \(\n                !poolAlreadyReady &&\n                networkState ==\n                GoneSmartNetworkState\.OFFLINE\n            \) \{[\s\S]*?                return@intercept result\n            \}\n\n(?=            val seeds =)'''
)
module, count = offline_pattern.subn(
    '''            // Offline no longer means immediate native fallback: give the\n            // local Playlist Affinity provider one bounded background-pipeline\n            // attempt first. The later failure branch still falls back natively.\n\n''',
    module,
    count=1
)
if count != 1:
    raise RuntimeError(f"GoneSmartModule offline block rewrite matched {count}")

# Load the local library once before provider collection and feed the local
# playlist provider into the same contribution aggregator as network sources.
module_replace(
    '''            val rawContributions =\n                mutableListOf<RawRecommendationContribution>()\n\n            seeds.forEachIndexed { seedIndex, seed ->\n''',
    '''            val library =\n                gmmpLibraryReader\n                    .read(autoDjInstance)\n\n            Log.i(TAG, "LOCAL LIBRARY TRACK COUNT = ${library.size}")\n\n            if (library.isEmpty()) {\n                return emptyList()\n            }\n\n            val playlistAffinityBatch =\n                localPlaylistAffinityProvider.collect(\n                    autoDjInstance = autoDjInstance,\n                    seeds = seeds,\n                    library = library,\n                    maxResultsPerSeed = (targetPoolSize * 3).coerceAtLeast(24)\n                )\n\n            val rawContributions =\n                mutableListOf<RawRecommendationContribution>()\n\n            rawContributions +=\n                playlistAffinityBatch.signals.mapNotNull { signal ->\n                    val artist = signal.libraryTrack.track.artist?.trim().orEmpty()\n                    val title = signal.libraryTrack.track.title?.trim().orEmpty()\n                    if (artist.isBlank() || title.isBlank()) {\n                        null\n                    } else {\n                        RawRecommendationContribution(\n                            provider = RecommendationProvider.LOCAL_PLAYLIST,\n                            seed = signal.seed,\n                            artist = artist,\n                            title = title,\n                            mbid = null,\n                            providerSimilarity = signal.score,\n                            seedWeight = seedWeight(signal.seed),\n                            identityConfidence = 1.0\n                        )\n                    }\n                }\n\n            seeds.forEachIndexed { seedIndex, seed ->\n'''
)

# The library was moved earlier; remove the old duplicate read.
old_library_block = '''            val library =\n                gmmpLibraryReader\n                    .read(\n                        autoDjInstance\n                    )\n\n            Log.i(\n                TAG,\n                "LOCAL LIBRARY TRACK COUNT = " +\n                    library.size\n            )\n\n            if (\n                library.isEmpty()\n            ) {\n\n                return emptyList()\n            }\n\n'''
if module.count(old_library_block) != 1:
    raise RuntimeError(f"GoneSmartModule old library block count={module.count(old_library_block)}")
module = module.replace(old_library_block, "", 1)

# Pipeline call sites now pass the already-read local library.
module_replace(
    '''                    collectSeedContributions(\n                        seedNumber = seedNumber,\n                        seed = seed,\n                        metadata = metadata\n                    )\n''',
    '''                    collectSeedContributions(\n                        seedNumber = seedNumber,\n                        seed = seed,\n                        metadata = metadata,\n                        library = library\n                    )\n'''
)
module_replace(
    '''                                collectSeedContributions(\n                                    seedNumber = seedNumber,\n                                    seed = seed,\n                                    metadata = fallbackMetadata\n                                )\n''',
    '''                                collectSeedContributions(\n                                    seedNumber = seedNumber,\n                                    seed = seed,\n                                    metadata = fallbackMetadata,\n                                    library = library\n                                )\n'''
)
module_replace(
    '''                    collectBroadSeedContributions(\n                        seeds = seeds\n                    )\n''',
    '''                    collectBroadSeedContributions(\n                        seeds = seeds,\n                        library = library\n                    )\n''',
    count_expected=2
)

# Make provider wording accurate now that a local provider can stand alone.
module = module.replace(
    "reason=no-external-recommendations",
    "reason=no-provider-recommendations"
)
module = module.replace(
    "SMART DJ PIPELINE | no external recommendations after broad search",
    "SMART DJ PIPELINE | no provider recommendations after broad search"
)

# Log provider diversity/corroboration alongside the existing detailed rows.
needle = '''            logMergedRecommendations(\n                merged\n            )\n\n            if (\n                merged.isEmpty()\n            ) {\n'''
replacement = '''            logMergedRecommendations(\n                merged\n            )\n\n            logProviderBlendMetrics(\n                rawContributions = rawContributions,\n                merged = merged,\n                playlistMetrics = playlistAffinityBatch.metrics\n            )\n\n            if (\n                merged.isEmpty()\n            ) {\n'''
module_replace(needle, replacement)

# Replace the seed/broad collector block so network sources are skipped when
# offline but the local provider remains available from the earlier phase.
collector_pattern = re.compile(
    r'''    private fun collectSeedContributions\([\s\S]*?\n    private fun buildProviderFallbackTrack\('''
)
collector_replacement = r'''    private fun collectSeedContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        library: List<GmmpLibraryTrack>,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) {
            return emptyList()
        }

        val result = mutableListOf<RawRecommendationContribution>()
        result += collectListenBrainzContributions(
            seedNumber = seedNumber,
            seed = seed,
            metadata = metadata,
            library = library,
            broadSearch = broadSearch
        )
        result += collectLastFmContributions(
            seedNumber = seedNumber,
            seed = seed,
            metadata = metadata,
            broadSearch = broadSearch
        )
        return result
    }

    private fun collectBroadSeedContributions(
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>
    ): List<RawRecommendationContribution> {
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) {
            return emptyList()
        }

        val result = mutableListOf<RawRecommendationContribution>()
        seeds.forEachIndexed { seedIndex, seed ->
            val seedNumber = seedIndex + 1
            val resolvedInput = trackInputResolver.resolve(seed.track)
            val metadata = metadataNormalizer.normalize(resolvedInput.track)
            if (metadata.searchArtist.isBlank() || metadata.searchTitle.isBlank()) {
                return@forEachIndexed
            }

            var contributions = collectSeedContributions(
                seedNumber = seedNumber,
                seed = seed,
                metadata = metadata,
                library = library,
                broadSearch = true
            )

            if (contributions.isEmpty() && resolvedInput.source != TrackInputSource.TAGS) {
                val fallbackTrack = buildProviderFallbackTrack(resolvedInput.track)
                if (fallbackTrack != null) {
                    val fallbackMetadata = metadataNormalizer.normalize(fallbackTrack)
                    if (fallbackMetadata.searchArtist.isNotBlank() &&
                        fallbackMetadata.searchTitle.isNotBlank()
                    ) {
                        contributions = collectSeedContributions(
                            seedNumber = seedNumber,
                            seed = seed,
                            metadata = fallbackMetadata,
                            library = library,
                            broadSearch = true
                        )
                    }
                }
            }
            result += contributions
        }
        return result
    }

    private fun buildProviderFallbackTrack('''
module, count = collector_pattern.subn(collector_replacement, module, count=1)
if count != 1:
    raise RuntimeError(f"GoneSmartModule collector block rewrite matched {count}")

# ListenBrainz and Last.fm provider orchestration. Recording-level evidence is
# always tried first; artist fallback is only used after identity variants are
# exhausted.
provider_pattern = re.compile(
    r'''    private fun collectListenBrainzContributions\([\s\S]*?\n    private fun seedWeight\('''
)
provider_replacement = r'''    private fun collectListenBrainzContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        library: List<GmmpLibraryTrack>,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {
        val broadContributions = mutableListOf<RawRecommendationContribution>()
        var identitiesUsed = 0

        try {
            val matches = listenBrainzClient.searchRecording(
                artist = metadata.searchArtist,
                title = metadata.searchTitle
            )
            val evaluations = matchSelector.evaluateAll(metadata, matches)
                .filter { it.accepted }
                .take(
                    if (broadSearch) BROAD_LISTENBRAINZ_MAX_RECORDING_MATCHES
                    else MAX_RECORDING_MATCHES_TO_TRY
                )

            for (evaluation in evaluations) {
                val similar = try {
                    listenBrainzClient.getSimilarRecordings(evaluation.match.recordingMbid)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (similar.isEmpty()) continue

                val highest = similar.maxOfOrNull { it.score }
                    ?.takeIf { it > 0.0 } ?: 1.0
                val mapped = similar.map { item ->
                    RawRecommendationContribution(
                        provider = RecommendationProvider.LISTENBRAINZ,
                        seed = seed,
                        artist = item.artistName,
                        title = item.recordingName,
                        mbid = item.recordingMbid,
                        providerSimilarity = (item.score / highest).coerceIn(0.0, 1.0),
                        seedWeight = seedWeight(seed),
                        identityConfidence = evaluation.totalScore.coerceIn(0.0, 1.0)
                    )
                }

                Log.i(
                    TAG,
                    (if (broadSearch) "LB BROAD" else "LB") +
                        " TRACK CONTRIBUTIONS seed=$seedNumber | results=${mapped.size} | " +
                        "identityConfidence=${formatScore(evaluation.totalScore)}"
                )

                if (!broadSearch) return mapped
                broadContributions += mapped
                identitiesUsed++
                if (identitiesUsed >= BROAD_LISTENBRAINZ_IDENTITIES_TO_USE) break
            }

            if (broadContributions.isNotEmpty()) {
                return broadContributions.distinctBy {
                    Triple(it.artist, it.title, it.mbid)
                }
            }

            // Recording graph exhausted: use ListenBrainz's official
            // similar-artists dataset as a lower-confidence signal and expand
            // it only to tracks that are already in the local GMMP library.
            val fallbackEvaluation = evaluations.firstOrNull {
                it.match.artistMbids.isNotEmpty()
            }
            val artistMbid = fallbackEvaluation?.match?.artistMbids?.firstOrNull()
            if (fallbackEvaluation != null && !artistMbid.isNullOrBlank()) {
                val artists = try {
                    listenBrainzClient.getSimilarArtists(
                        artistMbid = artistMbid,
                        limit = if (broadSearch) 20 else 12
                    )
                } catch (_: Throwable) {
                    emptyList()
                }
                val expanded = localArtistSignalExpander.expand(
                    artists = artists,
                    library = library,
                    excludedTrackIds = setOf(seed.track.id),
                    maxArtists = if (broadSearch) 20 else 12,
                    maxTracksPerArtist = 3
                )
                if (expanded.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "LB ARTIST FALLBACK seed=$seedNumber | artists=${artists.size} | " +
                            "localTracks=${expanded.size}"
                    )
                    return expanded.mapNotNull { item ->
                        val artist = item.libraryTrack.track.artist?.trim().orEmpty()
                        val title = item.libraryTrack.track.title?.trim().orEmpty()
                        if (artist.isBlank() || title.isBlank()) null else
                            RawRecommendationContribution(
                                provider = RecommendationProvider.LISTENBRAINZ,
                                seed = seed,
                                artist = artist,
                                title = title,
                                mbid = null,
                                providerSimilarity = (
                                    item.providerScore * LISTENBRAINZ_ARTIST_FALLBACK_PENALTY
                                ).coerceIn(0.0, 1.0),
                                seedWeight = seedWeight(seed),
                                identityConfidence = fallbackEvaluation.totalScore
                                    .coerceIn(0.0, 1.0)
                            )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ListenBrainz contribution collection failed for seed $seedNumber", t)
        }
        return emptyList()
    }

    private fun collectLastFmContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {
        if (!lastFmClient.isConfigured()) return emptyList()

        val queryCandidates = buildLastFmQueryCandidates(metadata)
        val testedIdentityKeys = mutableSetOf<String>()
        var fallbackIdentity: LastFmTrackIdentity? = null
        var fallbackConfidence = 0.0

        for (candidate in queryCandidates) {
            val identity = try {
                lastFmClient.resolveTrack(candidate.artist, candidate.title)
            } catch (_: Throwable) {
                null
            } ?: continue

            val identityKey = buildLastFmIdentityKey(identity)
            if (!testedIdentityKeys.add(identityKey)) continue

            val identityConfidence = lastFmIdentityConfidence(metadata, candidate)
            if (identityConfidence > fallbackConfidence) {
                fallbackIdentity = identity
                fallbackConfidence = identityConfidence
            }

            val similar = try {
                lastFmClient.getSimilarTracksDirect(
                    identity = identity,
                    limit = if (broadSearch) BROAD_LASTFM_RESULT_LIMIT else LASTFM_RESULT_LIMIT
                )
            } catch (_: Throwable) {
                emptyList()
            }
            if (similar.isEmpty()) continue

            Log.i(
                TAG,
                (if (broadSearch) "LASTFM BROAD" else "LASTFM") +
                    " TRACK CONTRIBUTIONS seed=$seedNumber | results=${similar.size} | " +
                    "identityConfidence=${formatScore(identityConfidence)} | " +
                    "source=${candidate.artist} - ${candidate.title}"
            )
            return mapLastFmContributions(similar, seed, identityConfidence)
        }

        val identity = fallbackIdentity ?: return emptyList()
        val fallback = try {
            lastFmClient.getArtistFallbackTracks(
                artist = identity.artistName,
                limit = if (broadSearch) BROAD_LASTFM_RESULT_LIMIT else LASTFM_RESULT_LIMIT
            )
        } catch (_: Throwable) {
            emptyList()
        }
        if (fallback.isEmpty()) return emptyList()

        Log.i(
            TAG,
            (if (broadSearch) "LASTFM BROAD" else "LASTFM") +
                " ARTIST FALLBACK seed=$seedNumber | results=${fallback.size} | " +
                "artist=${identity.artistName}"
        )
        return mapLastFmContributions(fallback, seed, fallbackConfidence)
    }

    private fun mapLastFmContributions(
        tracks: List<LastFmSimilarTrack>,
        seed: RecommendationSeed,
        identityConfidence: Double
    ): List<RawRecommendationContribution> =
        tracks.map { item ->
            RawRecommendationContribution(
                provider = RecommendationProvider.LASTFM,
                seed = seed,
                artist = item.artistName,
                title = item.trackName,
                mbid = item.mbid,
                providerSimilarity = item.match.coerceIn(0.0, 1.0),
                seedWeight = seedWeight(seed),
                identityConfidence = identityConfidence.coerceIn(0.0, 1.0)
            )
        }

    private fun seedWeight('''
module, count = provider_pattern.subn(provider_replacement, module, count=1)
if count != 1:
    raise RuntimeError(f"GoneSmartModule provider block rewrite matched {count}")

# Add concise blend metrics without per-result spam.
metrics_method = r'''    private fun logProviderBlendMetrics(
        rawContributions: List<RawRecommendationContribution>,
        merged: List<RecommendationCandidate>,
        playlistMetrics: PlaylistAffinityMetrics
    ) {
        val counts = rawContributions
            .groupingBy { it.provider }
            .eachCount()
        val corroborated = merged.count { candidate ->
            candidate.contributions.map { it.provider }.distinct().size >= 2
        }
        Log.i(
            TAG,
            "SMART DJ PROVIDER METRICS | contributions=$counts | " +
                "merged=${merged.size} | multiProvider=$corroborated | " +
                "playlistUsable=${playlistMetrics.usablePlaylists} | " +
                "playlistMemberships=${playlistMetrics.memberships} | " +
                "playlistSeedHits=${playlistMetrics.seedsMatched} | " +
                "playlistSignals=${playlistMetrics.signals}"
        )
    }

'''
marker = "    private fun logMergedRecommendations(\n"
if module.count(marker) != 1:
    raise RuntimeError(f"GoneSmartModule log marker count={module.count(marker)}")
module = module.replace(marker, metrics_method + marker, 1)
write("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt", module)

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
write("app/src/test/java/io/github/alagga/gonesmart/RecommendationScoringTest.kt", r'''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationScoringTest {
    @Test
    fun corroborationRewardsIndependentProviders() {
        assertEquals(1.0, RecommendationScoring.corroborationMultiplier(1), 0.0001)
        assertTrue(
            RecommendationScoring.corroborationMultiplier(2) >
                RecommendationScoring.corroborationMultiplier(1)
        )
        assertTrue(
            RecommendationScoring.corroborationMultiplier(3) >
                RecommendationScoring.corroborationMultiplier(2)
        )
    }

    @Test
    fun focusedPlaylistWeighsMoreThanHugePlaylist() {
        assertTrue(
            PlaylistAffinityScoring.playlistWeight(25) >
                PlaylistAffinityScoring.playlistWeight(2500)
        )
    }

    @Test
    fun repeatedCoOccurrenceStrengthensAffinity() {
        val one = PlaylistAffinityScoring.score(listOf(25))
        val two = PlaylistAffinityScoring.score(listOf(25, 30))
        val huge = PlaylistAffinityScoring.score(listOf(2500))
        assertTrue(two > one)
        assertTrue(one > huge)
        assertTrue(two <= 0.95)
    }
}
''')

# ---------------------------------------------------------------------------
# Documentation / durable project rules
# ---------------------------------------------------------------------------
agents = read("AGENTS.md")
old = '''## 9. Smart Auto-DJ performance invariants\n\n- Keep the recommendation pool ahead of GMMP's visible queue through background prewarm/low-water top-up.\n'''
new = '''## 9. Smart Auto-DJ performance invariants\n\n- Normal GMMP playlists are a first-class local recommendation signal: use read-only indexed playlist identities, cache membership by file fingerprint, weight focused playlists more strongly than huge catch-all lists, and reward independent playlist co-occurrence. Smart Playlists are not part of this signal unless a future explicitly weaker opt-in policy is implemented.\n- Provider corroboration is evidence: after provider/seed deduplication, independent providers confirming the same logical track may receive a bounded bonus. A local playlist signal must never bypass the normal local-library quality/rating/duplicate gates.\n- Keep the recommendation pool ahead of GMMP's visible queue through background prewarm/low-water top-up, including local-only prewarm when the network is unavailable.\n'''
if agents.count(old) != 1:
    raise RuntimeError("AGENTS.md invariant insertion point changed")
write("AGENTS.md", agents.replace(old, new, 1))

roadmap = read("docs/ROADMAP.md")
old = '''### More recommendation providers\n\nExplore additional recommendation sources for Smart Auto-DJ. Possible directions include services such as Spotify, YouTube and YouTube Music, but these names are examples rather than committed providers. The exact services, available APIs and practical feasibility still need to be evaluated before implementation is decided.\n\nAny future provider would be used as an additional **recommendation source**, not as a way to stream its catalog through GoneSmart. The goal is to improve recommendation coverage and give users more choice while GoneSmart continues to resolve playable tracks against the local GMMP library.\n'''
new = '''### Smarter recommendation blend\n\nContinue strengthening the existing Last.fm and ListenBrainz signals and combine them with local Playlist Affinity: tracks that repeatedly occur together in the user's normal GMMP playlists can reinforce one another even when a new release is not yet represented well by public recommendation graphs. Independent provider agreement receives a bounded ranking boost while every final result still has to resolve to the local GMMP library.\n\nSoundCloud remains an interesting later provider to investigate, especially for electronic music, remixes and new releases, but it is not committed until a safe public-client credential model is practical. Spotify, YouTube and YouTube Music remain evaluated examples rather than planned integrations.\n'''
if roadmap.count(old) != 1:
    raise RuntimeError("ROADMAP provider section changed")
roadmap = roadmap.replace(old, new, 1)
roadmap = roadmap.replace(
    "## Later ideas\n\nAdditional smart-library, playback and GMMP workflow extensions can be considered after the 0.5.0 work is stable.\n",
    "## Later ideas\n\n- Optionally let Smart Playlists contribute a deliberately much weaker affinity signal; broad/dynamic Smart Playlist membership must never be treated like a hand-curated normal playlist by default.\n- Revisit SoundCloud recommendations if authentication/credential handling becomes suitable for a distributed Android module.\n- Additional smart-library, playback and GMMP workflow extensions can be considered after the 0.5.0 work is stable.\n"
)
write("docs/ROADMAP.md", roadmap)

providers = read("docs/AUTO_DJ_PROVIDERS.md")
append = r'''

## Implemented provider blend on this branch

The branch now implements the first six provider/quality steps as one pipeline rather than isolated experiments:

- Last.fm exhausts recording identity/title variants and direct `track.getSimilar` evidence before its bounded similar-artist/top-track fallback is allowed to contribute.
- ListenBrainz keeps recording-level similarity first, then uses the official `similar-artists` Labs dataset as a lower-confidence artist signal that is expanded only into tracks already present in the local GMMP library.
- Normal GMMP playlists contribute through `LOCAL_PLAYLIST`. GoneSmart enumerates the already indexed `playlist_file_table` read-only, parses only supported M3U/M3U8 membership, and never mutates playlist files or database rows for recommendation purposes.
- Playlist membership is cached by canonical playlist path, modification time, file length and local-library fingerprint. Unchanged playlists are not reparsed on every refill.
- Playlist affinity uses saturating co-occurrence evidence. Small/focused playlists carry more weight than very large catch-all playlists, and the same pair appearing in multiple independent playlists becomes stronger evidence.
- Smart Playlists are deliberately excluded from affinity today. A future opt-in may add them only as a substantially weaker signal because rule-based membership can be very broad.
- The aggregator deduplicates provider+seed evidence as before, then applies a small bounded bonus when independent providers agree on the same logical track. Local Playlist Affinity still passes the same local matching, rating, duplicate and minimum-quality gates as network recommendations.
- Provider metrics now summarize contribution counts, multi-provider corroboration and playlist cache/membership/seed-hit counts without per-result logging spam.
- Local Playlist Affinity remains usable without network; network-only providers are skipped while offline before any HTTP request is attempted.

### Deferred sources

SoundCloud stays on the later-evaluation list because its related-track signal is attractive for electronic/new music, but a public GoneSmart build must not embed a shared confidential client secret. Spotify, YouTube and YouTube Music remain evaluated but unaccepted directions rather than committed providers.
'''
if "## Implemented provider blend on this branch" not in providers:
    providers += append
write("docs/AUTO_DJ_PROVIDERS.md", providers)

print("Auto-DJ provider implementation patch prepared successfully")
