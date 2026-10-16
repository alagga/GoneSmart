package io.github.alagga.gonesmart

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
    val signals: Int = 0,
    val libraryIndexCacheHit: Boolean = false,
    val elapsedMs: Long = 0L
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

    private data class LibraryPathIndexCache(
        val fingerprint: Long,
        val librarySize: Int,
        val byPath: Map<String, Long>
    )

    private val cache = linkedMapOf<String, CacheEntry>()
    private val libraryPathIndexLock = Any()

    @Volatile
    private var libraryPathIndexCache: LibraryPathIndexCache? = null

    fun collect(
        autoDjInstance: Any,
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        playlistSizeWeighting: PlaylistSizeWeighting = PlaylistSizeWeighting.BALANCED,
        maxResultsPerSeed: Int = DEFAULT_MAX_RESULTS_PER_SEED
    ): PlaylistAffinityBatch {
        val startedNs = System.nanoTime()

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
        val (byPath, libraryIndexCacheHit) =
            getOrBuildLibraryPathIndex(
                library = library,
                fingerprint = fingerprint
            )
        val byId = library.associateBy { it.track.id }
        if (byPath.isEmpty()) {
            return PlaylistAffinityBatch(
                emptyList(),
                PlaylistAffinityMetrics(
                    catalogPlaylists = catalog.size,
                    libraryIndexCacheHit = libraryIndexCacheHit,
                    elapsedMs = elapsedSince(startedNs)
                )
            )
        }

        var cacheHits = 0
        var cacheMisses = 0
        val memberships = linkedMapOf<String, Set<Long>>()
        val activePaths = linkedSetOf<String>()

        for (raw in catalog) {
            val file = normalPlaylistFile(raw) ?: continue
            val path = PlaylistAffinityPathKey.fromFile(file) ?: continue
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
                    val score = PlaylistAffinityScoring.score(sizes, playlistSizeWeighting)
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
            signals = signals.size,
            libraryIndexCacheHit = libraryIndexCacheHit,
            elapsedMs = elapsedSince(startedNs)
        )

        Log.i(
            TAG,
            "PLAYLIST AFFINITY | catalog=${metrics.catalogPlaylists} | " +
                "usable=${metrics.usablePlaylists} | memberships=${metrics.memberships} | " +
                "cacheHits=${metrics.cacheHits} | cacheMisses=${metrics.cacheMisses} | " +
                "libraryIndex=${if (metrics.libraryIndexCacheHit) "hit" else "rebuilt"} | " +
                "seedHits=${metrics.seedsMatched}/${seeds.size} | signals=${metrics.signals} | " +
                "sizeWeighting=$playlistSizeWeighting | elapsedMs=${metrics.elapsedMs}"
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

    private fun getOrBuildLibraryPathIndex(
        library: List<GmmpLibraryTrack>,
        fingerprint: Long
    ): Pair<Map<String, Long>, Boolean> {
        val cached = libraryPathIndexCache
        if (cached != null &&
            cached.fingerprint == fingerprint &&
            cached.librarySize == library.size
        ) {
            return cached.byPath to true
        }

        synchronized(libraryPathIndexLock) {
            val current = libraryPathIndexCache
            if (current != null &&
                current.fingerprint == fingerprint &&
                current.librarySize == library.size
            ) {
                return current.byPath to true
            }

            val built = buildLibraryPathIndex(library)
            libraryPathIndexCache =
                LibraryPathIndexCache(
                    fingerprint = fingerprint,
                    librarySize = library.size,
                    byPath = built
                )
            return built to false
        }
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
        return PlaylistAffinityPathKey.fromFile(file)
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
        return PlaylistAffinityPathKey.fromFile(file)
    }

    private fun libraryFingerprint(library: List<GmmpLibraryTrack>): Long {
        var hash = 1125899906842597L
        for (entry in library) {
            hash = hash * 31L + entry.track.id
            hash = hash * 31L + (entry.track.path?.hashCode()?.toLong() ?: 0L)
        }
        return hash
    }

    private fun elapsedSince(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}

/**
 * Fast, side-effect-free normalization for GMMP library and playlist paths.
 *
 * File.canonicalPath resolves through the filesystem. Doing that for an entire
 * 18k+ track library turned the local playlist provider into a minute-long hot
 * path on removable storage. GMMP playlist entries and library rows already
 * carry absolute paths, so lexical normalization is sufficient for membership
 * matching and keeps the provider independent from storage latency.
 */
internal object PlaylistAffinityPathKey {
    fun fromFile(file: File): String? =
        fromAbsolutePath(file.absolutePath)

    fun fromAbsolutePath(rawPath: String): String? {
        val path = rawPath
            .trim()
            .replace('\\', '/')

        if (path.isBlank() || !path.startsWith('/')) return null

        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) {
                    segments.removeAt(segments.lastIndex)
                }
                else -> segments += segment
            }
        }

        return ("/" + segments.joinToString("/"))
            .lowercase(Locale.ROOT)
    }
}
