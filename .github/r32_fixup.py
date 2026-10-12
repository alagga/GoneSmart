from pathlib import Path
import re


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one exact replacement, found {count}")
    p.write_text(text.replace(old, new, 1))


# The managed-continuity path uses the same explicit verified qr receiver.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''                if (!requestNativeRefill(requested)) {\n''',
    '''                if (!requestNativeRefill(autoDj, requested)) {\n''',
)

# Build the Playlist-Link track_uri -> song_id index while GmmpLibraryReader
# is already running off the UI thread. The synchronous Smart-rule evaluator
# must only perform bounded map lookups.
reader = Path("app/src/main/java/io/github/alagga/gonesmart/GmmpLibraryReader.kt")
r = reader.read_text()
track_model = '''data class GmmpLibraryTrack(\n    val track: TrackInfo,\n    val year: Int,\n    val ratingRaw: Int,\n    val playCount: Int,\n    val skipCount: Int,\n    val dateAddedEpochMs: Long?,\n    val dateUpdatedEpochMs: Long?,\n    val lastPlayedEpochMs: Long?\n) {\n'''
if track_model not in r:
    raise SystemExit("GmmpLibraryTrack model anchor missing")
# Insert snapshot type before the existing track model.
r = r.replace(
    track_model,
    '''internal data class GmmpLibraryPathIndexSnapshot(\n    val trackCount: Int,\n    val idsByPath: Map<String, Long>,\n    val ambiguousPaths: Set<String>\n)\n\n''' + track_model,
    1,
)

cache_anchor = '''    @Volatile\n    private var cachedTracks:\n            List<GmmpLibraryTrack>? =\n        null\n'''
if cache_anchor not in r:
    raise SystemExit("GmmpLibraryReader cachedTracks anchor missing")
r = r.replace(
    cache_anchor,
    cache_anchor + '''\n    @Volatile\n    private var cachedPathIndex: GmmpLibraryPathIndexSnapshot? = null\n''',
    1,
)

old_cache_assign = '''                cachedTracks =\n                    loaded\n\n                cacheCreatedAtElapsedMs =\n                    SystemClock.elapsedRealtime()\n'''
new_cache_assign = '''                val pathIndex = buildPathIndex(loaded)\n                cachedPathIndex = pathIndex\n                cachedTracks =\n                    loaded\n\n                cacheCreatedAtElapsedMs =\n                    SystemClock.elapsedRealtime()\n\n                Log.i(\n                    TAG,\n                    "GMMP library path index ready | tracks=${pathIndex.trackCount} | " +\n                        "paths=${pathIndex.idsByPath.size} | " +\n                        "ambiguous=${pathIndex.ambiguousPaths.size}"\n                )\n'''
if old_cache_assign not in r:
    raise SystemExit("GmmpLibraryReader cache assignment missing")
r = r.replace(old_cache_assign, new_cache_assign, 1)

old_snapshot = '''    internal fun cachedSnapshot(): List<GmmpLibraryTrack>? {\n        val cache = cachedTracks ?: return null\n        return cache.takeIf { it.isNotEmpty() }\n    }\n\n'''
new_snapshot = '''    internal fun cachedPathIndexSnapshot(): GmmpLibraryPathIndexSnapshot? =\n        cachedPathIndex\n\n'''
if old_snapshot not in r:
    raise SystemExit("GmmpLibraryReader r32 cachedSnapshot missing")
r = r.replace(old_snapshot, new_snapshot, 1)

old_clear = '''            cachedTracks =\n                null\n\n            cacheCreatedAtElapsedMs =\n                0L\n'''
new_clear = '''            cachedTracks =\n                null\n\n            cachedPathIndex =\n                null\n\n            cacheCreatedAtElapsedMs =\n                0L\n'''
if old_clear not in r:
    raise SystemExit("GmmpLibraryReader clearCache block missing")
r = r.replace(old_clear, new_clear, 1)

load_anchor = '''    private fun loadLibrary(\n        autoDjInstance: Any\n    ): List<GmmpLibraryTrack> {\n'''
index_builder = '''    private fun buildPathIndex(\n        tracks: List<GmmpLibraryTrack>\n    ): GmmpLibraryPathIndexSnapshot {\n        val idsByPath = linkedMapOf<String, Long>()\n        val ambiguous = linkedSetOf<String>()\n        tracks.forEach { libraryTrack ->\n            val trackId = libraryTrack.track.id\n            pathKeys(libraryTrack.track.path).forEach { key ->\n                if (key in ambiguous) return@forEach\n                val previous = idsByPath[key]\n                if (previous == null || previous == trackId) {\n                    idsByPath[key] = trackId\n                } else {\n                    idsByPath.remove(key)\n                    ambiguous += key\n                }\n            }\n        }\n        return GmmpLibraryPathIndexSnapshot(\n            trackCount = tracks.size,\n            idsByPath = idsByPath,\n            ambiguousPaths = ambiguous\n        )\n    }\n\n    private fun pathKeys(path: String?): List<String> {\n        val raw = path?.trim()?.takeUnless(String::isBlank) ?: return emptyList()\n        if (!raw.startsWith("file://", ignoreCase = true)) return listOf(raw)\n        val stripped = raw.substring(7)\n        val decoded = runCatching { android.net.Uri.parse(raw).path }.getOrNull()\n        return listOfNotNull(raw, stripped, decoded)\n            .filter(String::isNotBlank)\n            .distinct()\n    }\n\n''' + load_anchor
if load_anchor not in r:
    raise SystemExit("GmmpLibraryReader loadLibrary anchor missing")
r = r.replace(load_anchor, index_builder, 1)
reader.write_text(r)

controller = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt")
c = controller.read_text()
c = c.replace(
    '''internal class PlaylistBridgeController(\n    private val libraryTracksProvider: () -> List<GmmpLibraryTrack>? = { null }\n) {\n''',
    '''internal class PlaylistBridgeController(\n    private val libraryPathIndexProvider: () -> GmmpLibraryPathIndexSnapshot? = { null }\n) {\n''',
    1,
)
c = c.replace(
    '        private const val LIBRARY_PATH_INDEX_MAX_AGE_MS = 2L * 60L * 1000L\n',
    '',
    1,
)
index_data_pattern = re.compile(
    r'\n    private data class LibraryPathIndex\(.*?\n    \)\n',
    re.S,
)
c, count = index_data_pattern.subn('\n', c, count=1)
if count != 1:
    raise SystemExit(f"PlaylistBridgeController post-r32 LibraryPathIndex removal count={count}")
c = c.replace(
    '    @Volatile private var libraryPathIndex: LibraryPathIndex? = null\n',
    '',
    1,
)
c = c.replace(
    '            libraryPathIndex = null\n',
    '',
    1,
)

resolve_pattern = re.compile(
    r'    private fun resolveTrackIds\(\n        paths: List<String>\n    \): List<Long> \{.*?\n    \}\n\n    private fun trackPathKeys',
    re.S,
)
resolve_replacement = '''    private fun resolveTrackIds(\n        paths: List<String>\n    ): List<Long> {\n        if (paths.isEmpty()) return emptyList()\n        val index = libraryPathIndexProvider()\n            ?: throw IllegalStateException(\n                "Prewarmed GMMP library path index is unavailable"\n            )\n        val result = linkedSetOf<Long>()\n        var ambiguousSources = 0\n        paths.distinct().forEach { sourcePath ->\n            val keys = trackPathKeys(sourcePath)\n            if (keys.any { it in index.ambiguousPaths }) {\n                ambiguousSources++\n                return@forEach\n            }\n            keys.firstNotNullOfOrNull { index.idsByPath[it] }?.let(result::add)\n        }\n        Log.i(\n            TAG,\n            "BRIDGE SOURCE IDS | paths=${paths.size} | library=${result.size} | " +\n                "index=${index.idsByPath.size} | ambiguous=$ambiguousSources | " +\n                "source=prewarmed-cache"\n        )\n        return result.toList()\n    }\n\n    private fun trackPathKeys'''
c, count = resolve_pattern.subn(resolve_replacement, c, count=1)
if count != 1:
    raise SystemExit(f"PlaylistBridgeController post-r32 resolve replacement count={count}")

old_keys = '''    private fun trackPathKeys(path: String?): List<String> {\n        val raw = path?.trim()?.takeUnless(String::isBlank) ?: return emptyList()\n        val filePath = if (raw.startsWith("file://", ignoreCase = true)) {\n            raw.substring(7)\n        } else {\n            raw\n        }\n        return buildList {\n            add(raw)\n            if (filePath != raw) add(filePath)\n            if (!filePath.contains("://")) {\n                add(canonicalPath(File(filePath)))\n            }\n        }.filter(String::isNotBlank).distinct()\n    }\n'''
new_keys = '''    private fun trackPathKeys(path: String?): List<String> {\n        val raw = path?.trim()?.takeUnless(String::isBlank) ?: return emptyList()\n        if (!raw.startsWith("file://", ignoreCase = true)) return listOf(raw)\n        val stripped = raw.substring(7)\n        val decoded = runCatching { android.net.Uri.parse(raw).path }.getOrNull()\n        return listOfNotNull(raw, stripped, decoded)\n            .filter(String::isNotBlank)\n            .distinct()\n    }\n'''
if old_keys not in c:
    raise SystemExit("PlaylistBridgeController post-r32 trackPathKeys block missing")
c = c.replace(old_keys, new_keys, 1)
controller.write_text(c)

module = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
m = module.read_text()
old_provider = '''        PlaylistBridgeController(\n            libraryTracksProvider = { gmmpLibraryReader.cachedSnapshot() }\n        )\n'''
new_provider = '''        PlaylistBridgeController(\n            libraryPathIndexProvider = {\n                gmmpLibraryReader.cachedPathIndexSnapshot()\n            }\n        )\n'''
if old_provider not in m:
    raise SystemExit("GoneSmartModule post-r32 bridge provider missing")
m = m.replace(old_provider, new_provider, 1)
module.write_text(m)

# Align docs with the final off-main-thread index ownership.
for path in ["AGENTS.md", "docs/GMMP_COMPATIBILITY_PLAYBOOK.md", "docs/GMMP_421_REACCEPTANCE_CHECKLIST.md"]:
    p = Path(path)
    text = p.read_text()
    text += '''\n- r32 performance guard: the complete `track_uri -> song_id` index is constructed together with `GmmpLibraryReader`'s startup/cache refresh off the UI thread. Smart-rule compilation may only consume the finished immutable index; it must not scan/canonicalize the whole library synchronously.\n'''
    p.write_text(text)
