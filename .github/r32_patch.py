from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one exact replacement, found {count}")
    p.write_text(text.replace(old, new, 1))


def replace_all_exact(path, old, new, expected):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} replacements, found {count}")
    p.write_text(text.replace(old, new))


# ---------------------------------------------------------------------------
# r32 identity
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    '        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r31"\n',
    '        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r32"\n',
)

# ---------------------------------------------------------------------------
# Playlist Link evaluation: Smart-rule compilation is called synchronously on
# GMMP's main thread. Never hit Room there. Reuse the immutable library list
# that GoneSmart already prewarms off-main-thread at startup.
# ---------------------------------------------------------------------------
reader = Path("app/src/main/java/io/github/alagga/gonesmart/GmmpLibraryReader.kt")
r = reader.read_text()
anchor = '''    fun hasFreshCache(): Boolean {\n\n        val cache =\n            cachedTracks\n                ?: return false\n'''
insert = '''    internal fun cachedSnapshot(): List<GmmpLibraryTrack>? {\n        val cache = cachedTracks ?: return null\n        return cache.takeIf { it.isNotEmpty() }\n    }\n\n''' + anchor
if anchor not in r:
    raise SystemExit("GmmpLibraryReader cache anchor missing")
r = r.replace(anchor, insert, 1)
reader.write_text(r)

controller = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt")
c = controller.read_text()
old_class = 'internal class PlaylistBridgeController {\n'
new_class = '''internal class PlaylistBridgeController(\n    private val libraryTracksProvider: () -> List<GmmpLibraryTrack>? = { null }\n) {\n'''
if old_class not in c:
    raise SystemExit("PlaylistBridgeController class declaration missing")
c = c.replace(old_class, new_class, 1)

old_index = '''    private data class LibraryPathIndex(\n        val databaseIdentity: Int,\n        val createdAtElapsedMs: Long,\n        val idsByPath: Map<String, Long>,\n        val ambiguousPaths: Set<String>\n    )\n'''
new_index = '''    private data class LibraryPathIndex(\n        val sourceIdentity: Int,\n        val createdAtElapsedMs: Long,\n        val trackCount: Int,\n        val idsByPath: Map<String, Long>,\n        val ambiguousPaths: Set<String>\n    )\n'''
if old_index not in c:
    raise SystemExit("PlaylistBridgeController LibraryPathIndex missing")
c = c.replace(old_index, new_index, 1)

old_compile_call = '''            resolveTrackIds(native, membership.paths)\n'''
new_compile_call = '''            resolveTrackIds(membership.paths)\n'''
if old_compile_call not in c:
    raise SystemExit("PlaylistBridgeController resolveTrackIds call missing")
c = c.replace(old_compile_call, new_compile_call, 1)

old_resolve = '''    private fun resolveTrackIds(\n        native: Bindings,\n        paths: List<String>\n    ): List<Long> {\n        if (paths.isEmpty()) return emptyList()\n        val database = native.databaseSingleton.get(null)\n            ?: throw IllegalStateException("GMDatabase singleton is null")\n        val databaseIdentity = System.identityHashCode(database)\n        val now = SystemClock.elapsedRealtime()\n        val cached = libraryPathIndex?.takeIf { index ->\n            index.databaseIdentity == databaseIdentity &&\n                now - index.createdAtElapsedMs <= LIBRARY_PATH_INDEX_MAX_AGE_MS\n        }\n        val index = cached ?: GmmpReadOnlySql.queryDatabase(\n            databaseInstance = database,\n            sql = "SELECT song_id, track_uri FROM tracks WHERE track_placeholder = 0"\n        ) { cursor ->\n            val idColumn = cursor.getColumnIndex("song_id")\n            val pathColumn = cursor.getColumnIndex("track_uri")\n            require(idColumn >= 0 && pathColumn >= 0) {\n                "GMMP track path index did not expose song_id/track_uri"\n            }\n            val idsByPath = linkedMapOf<String, Long>()\n            val ambiguous = linkedSetOf<String>()\n            while (cursor.moveToNext()) {\n                if (cursor.isNull(pathColumn)) continue\n                val trackId = cursor.getLong(idColumn)\n                trackPathKeys(cursor.getString(pathColumn)).forEach { key ->\n                    if (key in ambiguous) return@forEach\n                    val previous = idsByPath[key]\n                    if (previous == null || previous == trackId) {\n                        idsByPath[key] = trackId\n                    } else {\n                        idsByPath.remove(key)\n                        ambiguous += key\n                    }\n                }\n            }\n            LibraryPathIndex(\n                databaseIdentity = databaseIdentity,\n                createdAtElapsedMs = SystemClock.elapsedRealtime(),\n                idsByPath = idsByPath,\n                ambiguousPaths = ambiguous\n            )\n        }.also { loaded ->\n            libraryPathIndex = loaded\n            Log.i(\n                TAG,\n                "BRIDGE LIBRARY INDEX | paths=${loaded.idsByPath.size} | " +\n                    "ambiguous=${loaded.ambiguousPaths.size}"\n            )\n        }\n\n        val result = linkedSetOf<Long>()\n        var ambiguousSources = 0\n        paths.distinct().forEach { sourcePath ->\n            val keys = trackPathKeys(sourcePath)\n            if (keys.any { it in index.ambiguousPaths }) {\n                ambiguousSources++\n                return@forEach\n            }\n            keys.firstNotNullOfOrNull { index.idsByPath[it] }?.let(result::add)\n        }\n        Log.i(\n            TAG,\n            "BRIDGE SOURCE IDS | paths=${paths.size} | library=${result.size} | " +\n                "index=${index.idsByPath.size} | ambiguous=$ambiguousSources | " +\n                "cache=${cached != null}"\n        )\n        return result.toList()\n    }\n'''
new_resolve = '''    private fun resolveTrackIds(\n        paths: List<String>\n    ): List<Long> {\n        if (paths.isEmpty()) return emptyList()\n        val tracks = libraryTracksProvider()\n            ?.takeIf { it.isNotEmpty() }\n            ?: throw IllegalStateException(\n                "Prewarmed GMMP library snapshot is unavailable"\n            )\n        val sourceIdentity = System.identityHashCode(tracks)\n        val now = SystemClock.elapsedRealtime()\n        val cached = libraryPathIndex?.takeIf { index ->\n            index.sourceIdentity == sourceIdentity &&\n                index.trackCount == tracks.size &&\n                now - index.createdAtElapsedMs <= LIBRARY_PATH_INDEX_MAX_AGE_MS\n        }\n        val index = cached ?: run {\n            val idsByPath = linkedMapOf<String, Long>()\n            val ambiguous = linkedSetOf<String>()\n            tracks.forEach { libraryTrack ->\n                val trackId = libraryTrack.track.id\n                trackPathKeys(libraryTrack.track.path).forEach { key ->\n                    if (key in ambiguous) return@forEach\n                    val previous = idsByPath[key]\n                    if (previous == null || previous == trackId) {\n                        idsByPath[key] = trackId\n                    } else {\n                        idsByPath.remove(key)\n                        ambiguous += key\n                    }\n                }\n            }\n            LibraryPathIndex(\n                sourceIdentity = sourceIdentity,\n                createdAtElapsedMs = SystemClock.elapsedRealtime(),\n                trackCount = tracks.size,\n                idsByPath = idsByPath,\n                ambiguousPaths = ambiguous\n            )\n        }.also { loaded ->\n            libraryPathIndex = loaded\n            Log.i(\n                TAG,\n                "BRIDGE LIBRARY INDEX | source=prewarmed-cache | " +\n                    "tracks=${loaded.trackCount} | paths=${loaded.idsByPath.size} | " +\n                    "ambiguous=${loaded.ambiguousPaths.size}"\n            )\n        }\n\n        val result = linkedSetOf<Long>()\n        var ambiguousSources = 0\n        paths.distinct().forEach { sourcePath ->\n            val keys = trackPathKeys(sourcePath)\n            if (keys.any { it in index.ambiguousPaths }) {\n                ambiguousSources++\n                return@forEach\n            }\n            keys.firstNotNullOfOrNull { index.idsByPath[it] }?.let(result::add)\n        }\n        Log.i(\n            TAG,\n            "BRIDGE SOURCE IDS | paths=${paths.size} | library=${result.size} | " +\n                "index=${index.idsByPath.size} | ambiguous=$ambiguousSources | " +\n                "cache=${cached != null} | source=prewarmed-cache"\n        )\n        return result.toList()\n    }\n'''
if old_resolve not in c:
    raise SystemExit("PlaylistBridgeController r31 resolveTrackIds block missing")
c = c.replace(old_resolve, new_resolve, 1)

# ---------------------------------------------------------------------------
# Playlist Link label: do not bind to only the first alias + method U. These
# are pure String formatters, so hook every shape-validated candidate in both
# accepted alias families and only rewrite the exact linked-rule text.
# ---------------------------------------------------------------------------
c = c.replace('        val ruleLabelFormatter: Method?,\n', '        val ruleLabelFormatters: List<Method>,\n')
c = c.replace('        val ruleLabelFormatter: Method?,\n', '        val ruleLabelFormatters: List<Method>,\n')
if 'ruleLabelFormatter' in c and 'ruleLabelFormatters' not in c:
    raise SystemExit("PlaylistBridgeController formatter field replacement failed")

c = c.replace(
    '            ruleLabelFormatter = native.ruleLabelFormatter,\n',
    '            ruleLabelFormatters = native.ruleLabelFormatters,\n',
    1,
)
old_formatter = '''        val ruleLabelFormatter = r.optionalClass(\n            loader,\n            listOf("ls2", "os2")\n        )?.let { formatterClass ->\n            r.optionalMethod(formatterClass, listOf("U")) { method ->\n                method.parameterCount == 1 &&\n                    method.parameterTypes[0].isAssignableFrom(smartRuleClass) &&\n                    method.returnType == String::class.java\n            }\n        }\n'''
new_formatter = '''        val ruleLabelFormatters = listOf("ls2", "os2")\n            .mapNotNull { alias ->\n                r.optionalClass(loader, listOf(alias))\n            }\n            .flatMap { formatterClass ->\n                r.methods(formatterClass).filter { method ->\n                    method.parameterCount == 1 &&\n                        method.parameterTypes[0].isAssignableFrom(smartRuleClass) &&\n                        method.returnType == String::class.java\n                }\n            }\n            .distinctBy { method ->\n                method.declaringClass.name + "#" + method.name + "(" +\n                    method.parameterTypes.joinToString(",") { it.name } + ")"\n            }\n            .onEach { it.isAccessible = true }\n'''
if old_formatter not in c:
    raise SystemExit("PlaylistBridgeController old rule formatter resolver missing")
c = c.replace(old_formatter, new_formatter, 1)
c = c.replace(
    '            ruleLabelFormatter = ruleLabelFormatter,\n',
    '            ruleLabelFormatters = ruleLabelFormatters,\n',
    1,
)
controller.write_text(c)

module = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
m = module.read_text()
old_bridge_init = '''    private val playlistBridgeController =\n        PlaylistBridgeController()\n'''
new_bridge_init = '''    private val playlistBridgeController =\n        PlaylistBridgeController(\n            libraryTracksProvider = { gmmpLibraryReader.cachedSnapshot() }\n        )\n'''
if old_bridge_init not in m:
    raise SystemExit("GoneSmartModule PlaylistBridgeController init missing")
m = m.replace(old_bridge_init, new_bridge_init, 1)

old_label_hook = '''        targets.ruleLabelFormatter?.let { method ->\n            runCatching {\n                hook(method).intercept { chain ->\n                    val original = chain.proceed() as? String\n                    playlistBridgeController.rewriteNativeSmartPlaylistRuleLabel(\n                        chain.getArg(0),\n                        original\n                    )\n                }\n                installed++\n            }.onFailure {\n                playlistBridgeWarn("Smart rule label hook unavailable", it)\n            }\n        }\n'''
new_label_hook = '''        targets.ruleLabelFormatters.forEach { method ->\n            runCatching {\n                hook(method).intercept { chain ->\n                    val original = chain.proceed() as? String\n                    val rewritten =\n                        playlistBridgeController.rewriteNativeSmartPlaylistRuleLabel(\n                            chain.getArg(0),\n                            original\n                        )\n                    if (rewritten != original) {\n                        playlistBridgeInfo(\n                            "BRIDGE RULE LABEL | formatter=" +\n                                method.declaringClass.name + "." + method.name +\n                                " | original=" + original +\n                                " | rewritten=" + rewritten\n                        )\n                    }\n                    rewritten\n                }\n                installed++\n            }.onFailure {\n                playlistBridgeWarn(\n                    "Smart rule label hook unavailable: " +\n                        method.declaringClass.name + "." + method.name,\n                    it\n                )\n            }\n        }\n'''
if old_label_hook not in m:
    raise SystemExit("GoneSmartModule old rule label hook missing")
m = m.replace(old_label_hook, new_label_hook, 1)

# ---------------------------------------------------------------------------
# Track Mix refill: the controller already owns the verified qr instance.
# Pass that receiver through explicitly. Never use a module-level reference
# which a natural dx3 child/state-writer callback can overwrite.
# ---------------------------------------------------------------------------
old_callback = '''        requestNativeRefill = { count -> requestNativeTrackMixRefill(count) },\n'''
new_callback = '''        requestNativeRefill = { autoDj, count ->\n            requestNativeTrackMixRefill(autoDj, count)\n        },\n'''
if old_callback not in m:
    raise SystemExit("GoneSmartModule TrackMix refill callback missing")
m = m.replace(old_callback, new_callback, 1)

old_request = '''    private fun requestNativeTrackMixRefill(count: Int): Boolean {\n        if (count <= 0) return true\n        val manager = trackMixAutoDj?.get() ?: return false\n        return runCatching {\n            manager.javaClass.getDeclaredMethod(\n                "z", Int::class.javaPrimitiveType\n            ).apply { isAccessible = true }\n                .invoke(manager, count)\n            Log.i(\n                "GoneSmartTrackMix",\n                "MIX REQUEST REFILL | requested=$count"\n            )\n            true\n        }.onFailure {\n            Log.e(TAG, "Track Mix native refill unavailable", it)\n        }.getOrDefault(false)\n    }\n'''
new_request = '''    private fun requestNativeTrackMixRefill(\n        manager: Any,\n        count: Int\n    ): Boolean {\n        if (count <= 0) return true\n        if (manager.javaClass.name != "qr") {\n            Log.e(\n                TAG,\n                "Track Mix native refill rejected non-Auto-DJ receiver: " +\n                    manager.javaClass.name\n            )\n            return false\n        }\n        return runCatching {\n            manager.javaClass.getDeclaredMethod(\n                "z", Int::class.javaPrimitiveType\n            ).apply { isAccessible = true }\n                .invoke(manager, count)\n            Log.i(\n                "GoneSmartTrackMix",\n                "MIX REQUEST REFILL | receiver=qr | requested=$count"\n            )\n            true\n        }.onFailure {\n            Log.e(TAG, "Track Mix native refill unavailable", it)\n        }.getOrDefault(false)\n    }\n'''
if old_request not in m:
    raise SystemExit("GoneSmartModule old TrackMix refill method missing")
m = m.replace(old_request, new_request, 1)

old_state_capture = '''                    if (receiver != null) {\n                        trackMixAutoDj = WeakReference(receiver)\n                        trackMixController.captureNativeAutoDj(receiver)\n                        queueFlipController.captureNativeAutoDj(receiver)\n                    }\n'''
new_state_capture = '''                    if (receiver != null) {\n                        if (receiver.javaClass.name == "qr") {\n                            trackMixAutoDj = WeakReference(receiver)\n                        }\n                        trackMixController.captureNativeAutoDj(receiver)\n                        queueFlipController.captureNativeAutoDj(receiver)\n                    }\n'''
if old_state_capture not in m:
    raise SystemExit("GoneSmartModule state-writer capture block missing")
m = m.replace(old_state_capture, new_state_capture, 1)
module.write_text(m)

track_mix = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt")
t = track_mix.read_text()
old_sig = '''    private val requestNativeRefill: (Int) -> Boolean,\n'''
new_sig = '''    private val requestNativeRefill: (Any, Int) -> Boolean,\n'''
if old_sig not in t:
    raise SystemExit("TrackMixController refill callback signature missing")
t = t.replace(old_sig, new_sig, 1)
old_held = '''    private fun requestHeldNativeRefill(count: Int): Boolean {\n        if (count <= 0) return true\n        val previous = nativeRefillAllowance.get() ?: 0\n        nativeRefillAllowance.set(previous + 1)\n        return try {\n            requestNativeRefill(count)\n        } finally {\n'''
new_held = '''    private fun requestHeldNativeRefill(count: Int): Boolean {\n        if (count <= 0) return true\n        val autoDj = nativeAutoDj?.get() ?: return false\n        val previous = nativeRefillAllowance.get() ?: 0\n        nativeRefillAllowance.set(previous + 1)\n        return try {\n            requestNativeRefill(autoDj, count)\n        } finally {\n'''
if old_held not in t:
    raise SystemExit("TrackMixController requestHeldNativeRefill block missing")
t = t.replace(old_held, new_held, 1)
track_mix.write_text(t)

# ---------------------------------------------------------------------------
# Documentation: preserve the newly device-proven ownership rules.
# ---------------------------------------------------------------------------
agents = Path("AGENTS.md")
a = agents.read_text()
a += '''\n\n## r32 device-proven follow-up rules\n\n- GMMP 4.2.1 invokes Playlist Link Smart-rule compilation on the main thread. That synchronous evaluator must never perform a Room/Cursor query. Resolve ordinary-playlist membership only against GoneSmart's already prewarmed immutable GMMP library snapshot; if that snapshot is unavailable, fail closed without blocking the UI.\n- A Track Auto-DJ refill is owned by the exact `qr` instance already verified/captured by `TrackMixController`. Pass that receiver explicitly into `qr.z(int)`. Natural child/state-writer callbacks such as `dx3` must never overwrite the module's Auto-DJ reference or become a refill receiver.\n- Linked-rule label formatting is read-only UI behavior. On accepted 4.2.x mappings, discover all shape-valid `SmartRule -> String` formatter methods across the bounded known formatter families rather than trusting one obfuscated method name; only rewrite the returned linked-rule label and leave GMMP's rule model untouched.\n'''
agents.write_text(a)

playbook = Path("docs/GMMP_COMPATIBILITY_PLAYBOOK.md")
p = playbook.read_text()
p += '''\n\n### r32 evidence: Playlist Link main-thread evaluator and Track Mix receiver ownership\n\nThe r31 device pass proved that the 4.2.1 Smart-Playlist evaluator calls Playlist Link compilation synchronously on GMMP's main thread. Even a parameter-free direct Room query therefore fails with Room's main-thread guard. Playlist Link must consume the prewarmed in-memory `GmmpLibraryReader` snapshot and must not query Room from that evaluator. The same pass proved that Track Mix can resolve the clicked track and prepare four GoneSmart recommendations correctly while still failing when the module-level refill reference has been overwritten by a `dx3` state object. Explicit Track Mix refill now receives the controller-verified `qr` instance directly.\n'''
playbook.write_text(p)

checklist = Path("docs/GMMP_421_REACCEPTANCE_CHECKLIST.md")
q = checklist.read_text()
q = q.replace("current **r31**", "current **r32**", 1)
q += '''\n\n## r32 regression focus\n\n- Playlist Link evaluation must log `BRIDGE LIBRARY INDEX | source=prewarmed-cache` / `BRIDGE SOURCE IDS` and must not touch Room from the Smart-rule evaluator, throw the main-thread database exception, or fall back to `Long.MIN_VALUE` for a valid linked source playlist.\n- Native Smart-Playlist links must render as **Smart Playlist: <name>** (localized host wording), while GoneSmart ordinary-playlist bridges remain **Playlist: <name>**. A changed label should emit one `BRIDGE RULE LABEL` marker identifying the formatter boundary.\n- Track Auto-DJ with prepared GoneSmart IDs must issue the explicit refill on `receiver=qr`, reach the existing bootstrap selection hook, and never attempt `dx3.z(int)`. A genuine zero-recommendation shortfall may be filled natively, but must not fail merely because no GoneSmart recommendations exist.\n- Keep the r30/r31 safety invariants: no 4.2.1 `CLEAR_QUEUE`, no post-materialization Track-ID retarget, and natural Track 1 -> Track 2 playback must produce a real decoder/audio start.\n'''
checklist.write_text(q)

completion = Path("docs/GMMP_421_COMPLETION.md")
co = completion.read_text()
co += '''\n\n### r32 pending re-acceptance\n\nThe r31 device pass isolated two remaining deterministic regressions: Playlist Link attempted a read-only Room lookup from GMMP's main-thread Smart-rule evaluator, and Track Mix invoked refill through a module reference that had been overwritten by a `dx3` child state object. r32 moves Playlist Link membership lookup to the prewarmed in-memory library snapshot and passes the controller-verified `qr` receiver explicitly for Track Mix refill. Device acceptance remains pending for the bundled r32 pass.\n'''
completion.write_text(co)
