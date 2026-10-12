from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    if text.count(old) < count:
        raise SystemExit(f"guard failed {path}: expected at least {count} occurrences")
    text = text.replace(old, new, count)
    p.write_text(text)

# --- GmmpQueueReader: allow a pending Track Mix to pin CURRENT by the
# already-proven native queue_id. This is checked before mutable position
# state and only accepted when the queue entry is unique.
p = Path("app/src/main/java/io/github/alagga/gonesmart/GmmpQueueReader.kt")
t = p.read_text()
t = t.replace(
'''    fun read(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int? = null\n    ): QueueContext? {\n        readLegacy(autoDjInstance)?.let { return it }\n        return readThroughCursor(autoDjInstance, currentQueuePositionHint)\n    }''',
'''    fun read(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int? = null,\n        currentQueueEntryIdHint: Long? = null\n    ): QueueContext? {\n        readLegacy(autoDjInstance)?.let { return it }\n        return readThroughCursor(\n            autoDjInstance,\n            currentQueuePositionHint,\n            currentQueueEntryIdHint\n        )\n    }''')
t = t.replace(
'''    private fun readThroughCursor(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int?\n    ): QueueContext? =''',
'''    private fun readThroughCursor(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int?,\n        currentQueueEntryIdHint: Long?\n    ): QueueContext? =''')
t = t.replace(
'''            val marker = resolveCurrentMarker(\n                autoDjInstance,\n                rows,\n                currentQueuePositionHint\n            ) ?: return@runCatching null''',
'''            val marker = resolveCurrentMarker(\n                autoDjInstance,\n                rows,\n                currentQueuePositionHint,\n                currentQueueEntryIdHint\n            ) ?: return@runCatching null''')
t = t.replace(
'''    private fun resolveCurrentMarker(\n        autoDjInstance: Any,\n        rows: List<CursorRow>,\n        currentQueuePositionHint: Int?\n    ): CurrentMarker? {\n        currentQueuePositionHint?.let { position ->''',
'''    private fun resolveCurrentMarker(\n        autoDjInstance: Any,\n        rows: List<CursorRow>,\n        currentQueuePositionHint: Int?,\n        currentQueueEntryIdHint: Long?\n    ): CurrentMarker? {\n        currentQueueEntryIdHint?.let { entryId ->\n            val matches = rows.indices.filter { index ->\n                rows[index].queueEntryId == entryId\n            }\n            if (matches.size == 1) {\n                return CurrentMarker(\n                    rowIndex = matches.single(),\n                    orderKind = OrderKind.QUEUE,\n                    source = "track-mix-entry-hint"\n                )\n            }\n            Log.w(\n                TAG,\n                "GMMP QUEUE CURRENT | queue entry hint rejected" +\n                    " | entryId=" + entryId +\n                    " | matches=" + matches.size\n            )\n        }\n\n        currentQueuePositionHint?.let { position ->''')
if 'currentQueueEntryIdHint: Long? = null' not in t or 'track-mix-entry-hint' not in t:
    raise SystemExit('GmmpQueueReader r33 patch guards failed')
p.write_text(t)

# --- Queue mutation bridge: carry the same pinned queue_id through prune and
# verification. All other callers retain null/default behavior.
p = Path("app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt")
t = p.read_text()
t = t.replace(
'''class GmmpQueueMutationBridge(\n    private val autoDj: Any,\n    private val verifiedPositionWriter: NativeQueuePositionWriter? = null\n) {''',
'''class GmmpQueueMutationBridge(\n    private val autoDj: Any,\n    private val verifiedPositionWriter: NativeQueuePositionWriter? = null,\n    private val currentQueueEntryIdHint: Long? = null\n) {''')
t = t.replace(
'''GmmpQueueReader().read(\n            autoDj,\n            statePosition.read()\n        )''',
'''GmmpQueueReader().read(\n            autoDj,\n            statePosition.read(),\n            currentQueueEntryIdHint\n        )''')
t = t.replace(
'''GmmpQueueReader().read(\n            autoDj,\n            verifiedPositionWriter?.read()\n        )''',
'''GmmpQueueReader().read(\n            autoDj,\n            verifiedPositionWriter?.read(),\n            currentQueueEntryIdHint\n        )''')
t = t.replace(
'''GmmpQueueReader().read(\n            autoDj,\n            verifiedPositionWriter?.read()\n        )''',
'''GmmpQueueReader().read(\n            autoDj,\n            verifiedPositionWriter?.read(),\n            currentQueueEntryIdHint\n        )''')
t = t.replace(
'''val context = GmmpQueueReader().read(autoDj, absoluteHint)''',
'''val context = GmmpQueueReader().read(\n            autoDj,\n            absoluteHint,\n            currentQueueEntryIdHint\n        )''')
if 'private val currentQueueEntryIdHint: Long? = null' not in t:
    raise SystemExit('GmmpQueueMutationBridge constructor patch failed')
# All relevant cursor verification reads should now carry the hint. Guard that
# no old two-line verifiedPositionWriter read remains.
if 'autoDj,\n            verifiedPositionWriter?.read()\n        )' in t:
    raise SystemExit('GmmpQueueMutationBridge still has unpinned verification read')
p.write_text(t)

# --- TrackMixController: after selected native queue entry is first proven,
# use its queue_id as immutable startup identity across Smart-list materialize,
# explicit qr.z bootstrap, prune and final verification.
p = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt")
t = p.read_text()
t = t.replace(
'''            val loaded = loadedPlay.snapshot\n            val selectedId = loadedPlay.selectedTrackId\n\n            val nativeSettings = settings.read()''',
'''            val loaded = loadedPlay.snapshot\n            val selectedId = loadedPlay.selectedTrackId\n            val selectedEntryId = loaded.currentEntryId ?: run {\n                Log.e(TAG, "MIX CURRENT PIN | selected queue_id unavailable")\n                fail("Could not identify the selected queue entry safely.", request.context)\n                return\n            }\n            Log.i(\n                TAG,\n                "MIX CURRENT PIN | entry=$selectedEntryId | track=$selectedId | verified=true"\n            )\n\n            val nativeSettings = settings.read()''')
t = t.replace(
'''                    currentSnapshot = awaitQueue(request, 8_000L) { snapshot ->\n                        snapshot.currentId == selectedId &&''',
'''                    currentSnapshot = awaitQueue(\n                        request,\n                        8_000L,\n                        selectedEntryId\n                    ) { snapshot ->\n                        snapshot.currentId == selectedId &&''')
t = t.replace(
'''                    GmmpQueueMutationBridge(\n                        autoDj = autoDj,\n                        verifiedPositionWriter = nativePositionWriterProvider(autoDj)\n                    ).retargetTrackMixUpcoming(''',
'''                    GmmpQueueMutationBridge(\n                        autoDj = autoDj,\n                        verifiedPositionWriter = nativePositionWriterProvider(autoDj),\n                        currentQueueEntryIdHint = selectedEntryId\n                    ).retargetTrackMixUpcoming(''', 1)
t = t.replace(
'''                filled = queueSnapshot()?.takeIf {\n                    it.currentId == selectedId && it.ids.size == initial\n                }''',
'''                filled = queueSnapshot(selectedEntryId)?.takeIf {\n                    it.currentId == selectedId && it.ids.size == initial\n                }''', 1)
t = t.replace(
'''                val current = queueSnapshot()\n                if (current != null &&''',
'''                val current = if (!legacyQueue) {\n                    queueSnapshot(loaded.currentEntryId)\n                } else {\n                    queueSnapshot()\n                }\n                if (current != null &&''', 1)
t = t.replace(
'''            val current = queueSnapshot() ?: continue''',
'''            val current = queueSnapshot(targetIdentity?.queueEntryId) ?: continue''', 1)
t = t.replace(
'''    private fun awaitQueue(\n        request: Pending,\n        timeoutMs: Long,\n        condition: (Snapshot) -> Boolean\n    ): Snapshot? {''',
'''    private fun awaitQueue(\n        request: Pending,\n        timeoutMs: Long,\n        currentQueueEntryIdHint: Long? = null,\n        condition: (Snapshot) -> Boolean\n    ): Snapshot? {''')
t = t.replace(
'''            val snapshot = queueSnapshot()\n            if (snapshot != null && condition(snapshot)) return snapshot''',
'''            val snapshot = queueSnapshot(currentQueueEntryIdHint)\n            if (snapshot != null && condition(snapshot)) return snapshot''', 1)
t = t.replace(
'''    private fun queueSnapshot(): Snapshot? = runCatching {''',
'''    private fun queueSnapshot(\n        currentQueueEntryIdHint: Long? = null\n    ): Snapshot? = runCatching {''')
t = t.replace(
'''            val context = queueReader.read(\n                autoDj,\n                positionWriter?.read()\n            ) ?: return@runCatching null''',
'''            val context = queueReader.read(\n                autoDj,\n                positionWriter?.read(),\n                currentQueueEntryIdHint\n            ) ?: return@runCatching null''')
for guard in [
    'MIX CURRENT PIN | entry=',
    'currentQueueEntryIdHint: Long? = null',
    'queueSnapshot(targetIdentity?.queueEntryId)',
    'currentQueueEntryIdHint = selectedEntryId'
]:
    if guard not in t:
        raise SystemExit(f'TrackMixController guard missing: {guard}')
p.write_text(t)

# --- PlaylistBridgeController: authoritative row-bind rewrite. It never
# changes the rule model. It rewrites one TextView only after proving the
# bound row is a native Smart Playlist reference (never a GoneSmart Playlist
# bridge reference).
p = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt")
t = p.read_text()
insert_before = '''    private fun menuContext(menu: Menu, inflater: Any?): Context? {'''
if insert_before not in t:
    raise SystemExit('PlaylistBridgeController insertion anchor missing')
addition = r'''
    fun rewriteBoundNativeSmartRuleLabel(
        adapter: Any?,
        holder: Any?,
        position: Int
    ): Boolean {
        if (!enabled || adapter == null || holder == null || position < 0) return false
        val native = bindings ?: return false
        val rule = resolveBoundRule(native, adapter, holder, position) ?: return false
        val value = runCatching { native.ruleValue.get(rule) as? String }.getOrNull()
            ?: return false
        if (PlaylistBridgeReference.decode(value) != null) return false
        if (!PlaylistBridgePolicy.isNativeSmartPlaylistReference(value)) return false
        val displayName = PlaylistBridgePolicy.nativeSmartPlaylistDisplayName(value)
            ?.trim()?.takeUnless(String::isBlank) ?: return false
        val context = currentContext() ?: return false
        val playlist = NativeGmmpUiText.string(context, "playlist")
            ?.trim()?.takeUnless(String::isBlank) ?: return false
        val smartPlaylist = NativeGmmpUiText.smartPlaylist(context)
            ?.trim()?.takeUnless(String::isBlank) ?: return false
        val expected = "$playlist: $displayName"
        val replacement = "$smartPlaylist: $displayName"
        if (expected == replacement) return false
        val root = holderItemView(holder) ?: return false
        val exact = arrayListOf<android.widget.TextView>()
        fun collect(view: android.view.View) {
            if (view is android.widget.TextView && view.text?.toString() == expected) {
                exact += view
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(::collect)
                }
            }
        }
        collect(root)
        if (exact.size != 1) return false
        exact.single().text = replacement
        return true
    }

    private fun resolveBoundRule(
        native: Bindings,
        adapter: Any,
        holder: Any,
        position: Int
    ): Any? {
        val candidates = java.util.IdentityHashMap<Any, Boolean>()
        fun add(value: Any?) {
            if (value != null && native.smartRuleClass.isInstance(value)) {
                candidates[value] = true
            }
        }
        fun inspectObject(value: Any?) {
            if (value == null) return
            add(value)
            generateSequence<Class<*>>(value.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    val nested = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull()
                    add(nested)
                }
        }
        inspectObject(holder)
        generateSequence<Class<*>>(adapter.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                val carrier = runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull()
                when (carrier) {
                    is List<*> -> inspectObject(carrier.getOrNull(position))
                    is Array<*> -> inspectObject(carrier.getOrNull(position))
                }
            }
        presenterRef?.get()?.let { presenter ->
            val state = runCatching { native.presenterState.invoke(presenter) }.getOrNull()
            val rules = state?.let {
                runCatching { native.stateRules.invoke(it) as? List<*> }.getOrNull()
            }
            inspectObject(rules?.getOrNull(position))
        }
        return candidates.keys.toList().singleOrNull()
    }

    private fun holderItemView(holder: Any): android.view.View? {
        generateSequence<Class<*>>(holder.javaClass) { it.superclass }.forEach { type ->
            runCatching {
                type.getDeclaredField("itemView").apply { isAccessible = true }.get(holder)
            }.getOrNull()?.let { if (it is android.view.View) return it }
        }
        val views = generateSequence<Class<*>>(holder.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    android.view.View::class.java.isAssignableFrom(it.type)
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder) as? android.view.View
                }.getOrNull()
            }
            .distinctBy { System.identityHashCode(it) }
            .toList()
        return views.singleOrNull()
    }

'''
t = t.replace(insert_before, addition + insert_before, 1)
if 'rewriteBoundNativeSmartRuleLabel' not in t:
    raise SystemExit('PlaylistBridgeController row rewrite patch failed')
p.write_text(t)

# --- Rule label fallback: unknown provenance must no longer rewrite generic
# Playlist text. Only decoded ordinary bridge or proven native Smart reference
# may alter the label.
p = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeRuleLabelPolicy.kt")
t = p.read_text()
t = t.replace(
'''        val text = original ?: return null\n        val prefix = "$playlistLabel:"\n        if (text.startsWith(prefix, ignoreCase = true)) {\n            val suffix = text.substring(prefix.length).trimStart()\n            if (suffix.isNotBlank()) return "$smartPlaylistLabel: $suffix"\n        }\n        return original''',
'''        return original''')
p.write_text(t)

p = Path("app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeRuleLabelPolicyTest.kt")
t = p.read_text().replace(
'''    @Test fun formatterFallbackRewritesPlaylistPrefix() {\n        assertEquals("Smart-Playlist: Favorites", PlaylistBridgeRuleLabelPolicy.rewrite("Playlist: Favorites", "Playlist", "Smart-Playlist", null, null))\n    }''',
'''    @Test fun unknownRuleProvenanceLeavesNativeLabelUntouched() {\n        assertEquals("Playlist: Favorites", PlaylistBridgeRuleLabelPolicy.rewrite("Playlist: Favorites", "Playlist", "Smart-Playlist", null, null))\n    }''')
p.write_text(t)

# --- GoneSmartModule: exact smart-rule RecyclerView boundary. The actual
# concrete adapter is discovered from the exact resource, then its stable
# RecyclerView onBindViewHolder override is hooked once. No global TextView
# hooks and no extra R8 formatter aliases.
p = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
t = p.read_text()
t = t.replace('''        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r32"''', '''        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r33"''')
anchor = '''    private val playlistBridgeSmartChooserTitleDepth =\n        ThreadLocal.withInitial { 0 }\n'''
if anchor not in t:
    raise SystemExit('module bridge field anchor missing')
t = t.replace(anchor, anchor + '''\n    private val playlistBridgeRuleBindHookKeys =\n        java.util.Collections.synchronizedSet(mutableSetOf<String>())\n''', 1)
t = t.replace(
'''        installed += installPlaylistBridgeEditorHooks(loader)\n        installed += installPlaylistBridgeEvaluationHook(loader)''',
'''        installed += installPlaylistBridgeEditorHooks(loader)\n        installed += installPlaylistBridgeEvaluationHook(loader)\n        installed += installPlaylistBridgeRuleRowHooks(loader)''', 1)
insert_anchor = '''    private fun installPlaylistBridgeEvaluationHook(\n        loader: ClassLoader\n    ): Int {'''
if insert_anchor not in t:
    raise SystemExit('module row hook insertion anchor missing')
row_hook = r'''
    private fun installPlaylistBridgeRuleRowHooks(loader: ClassLoader): Int {
        return runCatching {
            val recycler = loader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val setAdapter = recycler.declaredMethods.firstOrNull {
                it.name == "setAdapter" && it.parameterCount == 1
            } ?: throw NoSuchMethodException("RecyclerView.setAdapter")
            setAdapter.isAccessible = true
            hook(setAdapter).intercept { chain ->
                val result = chain.proceed()
                val list = chain.getThisObject() as? android.view.View
                val adapter = chain.getArg(0)
                if (list != null && adapter != null &&
                    compatibilityResourceName(list) == "smartRuleListRecyclerView"
                ) {
                    installPlaylistBridgeConcreteRuleBindHooks(adapter)
                }
                result
            }
            playlistBridgeInfo(
                "BRIDGE RULE ROW | smartRuleListRecyclerView adapter observer ready"
            )
            1
        }.getOrElse {
            playlistBridgeWarn("Smart rule RecyclerView observer unavailable", it)
            0
        }
    }

    private fun installPlaylistBridgeConcreteRuleBindHooks(adapter: Any) {
        val methods = GmmpReflectionPolicy.callableMethods(adapter.javaClass)
            .filter { method ->
                !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.name == "onBindViewHolder" &&
                    method.returnType == Void.TYPE &&
                    method.parameterCount in 2..3 &&
                    method.parameterTypes.getOrNull(1) == Integer.TYPE
            }
            .distinctBy { method ->
                method.declaringClass.name + "|" + method.name + "|" +
                    method.parameterTypes.joinToString(",") { it.name }
            }
        methods.forEach { method ->
            val key = method.declaringClass.name + "|" + method.name + "|" +
                method.parameterTypes.joinToString(",") { it.name }
            if (!playlistBridgeRuleBindHookKeys.add(key)) return@forEach
            runCatching {
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val holder = chain.getArg(0)
                    val position = (chain.getArg(1) as? Number)?.toInt() ?: -1
                    val changed = runCatching {
                        playlistBridgeController.rewriteBoundNativeSmartRuleLabel(
                            chain.getThisObject(),
                            holder,
                            position
                        )
                    }.onFailure {
                        playlistBridgeWarn("Smart rule row label rewrite failed", it)
                    }.getOrDefault(false)
                    if (changed) {
                        playlistBridgeInfo(
                            "BRIDGE RULE ROW LABEL | adapter=" +
                                adapter.javaClass.name +
                                " | position=$position | rewritten=true"
                        )
                    }
                    result
                }
                playlistBridgeInfo(
                    "BRIDGE RULE ROW HOOK | adapter=" + adapter.javaClass.name +
                        " | method=" + method.declaringClass.name + "." + method.name
                )
            }.onFailure {
                playlistBridgeRuleBindHookKeys.remove(key)
                playlistBridgeWarn("Smart rule row bind hook unavailable", it)
            }
        }
    }

'''
t = t.replace(insert_anchor, row_hook + insert_anchor, 1)
for guard in ['gmmp421-r33', 'installPlaylistBridgeRuleRowHooks(loader)', 'BRIDGE RULE ROW LABEL']:
    if guard not in t:
        raise SystemExit(f'GoneSmartModule guard missing: {guard}')
p.write_text(t)

# --- Durable docs.
p = Path('AGENTS.md')
t = p.read_text()
t += r'''

### GMMP 4.2.1 r33 Track Mix current identity + Smart rule row labels
- r32 device evidence proves the selected song can already be playing/decoded while the Smart-Playlist native list is still materializing. Once Track Mix has uniquely observed the selected native `queue_id`, that immutable queue-entry identity is the authoritative temporary CURRENT hint through list completion, initial `qr.z(int)` refill, prune and final verification. Mutable `queue_position`/R8 state remains secondary evidence; a missing or duplicated queue entry never permits guessing by track ID alone.
- The 4.2.1 Track Mix queue mutation remains prune-only after native materialization: preserve the verified CURRENT row and `Initial Size - 1` earliest upcoming rows, including `current+1`; never use `CLEAR_QUEUE`, never post-retarget a materialized upcoming Track ID, and never restore the retired same-position refresh without new playback evidence.
- Smart editor type labels are presentation-only at the exact host `smartRuleListRecyclerView` row-bind boundary. A row may be relabeled from the host `Playlist: <name>` to the host-localized `Smart Playlist: <name>` only when the bound model is uniquely proven to be a native Smart-Playlist reference. GoneSmart ordinary Playlist bridge references always retain `Playlist: <name>`. Do not install a global TextView rewrite or guess another R8 formatter alias.
'''
p.write_text(t)

for doc in ['docs/GMMP_COMPATIBILITY_PLAYBOOK.md', 'docs/GMMP_421_COMPLETION.md']:
    p = Path(doc)
    t = p.read_text()
    t += r'''

## r33 focused repair
- Pending Track Mix now pins CURRENT by the uniquely proven native queue entry ID across Smart-list materialization/refill/prune. The hint is request-scoped and fails closed if no longer unique.
- Smart-Playlist link type text is corrected only after the native `smartRuleListRecyclerView` row bind and only for a uniquely proven native Smart-Playlist reference. Ordinary Playlist bridge rules are untouched.
- r32 Playlist Link membership evaluation remains accepted and unchanged. `CLEAR_QUEUE`, post-materialization Track-ID retarget and same-position playback refresh remain retired.
'''
    p.write_text(t)

p = Path('docs/GMMP_421_REACCEPTANCE_CHECKLIST.md')
t = p.read_text()
# Make the header and compatibility marker current while preserving history below.
t = t.replace('current **r32** Playlist Link / Track Mix repair candidate', 'current **r33** Playlist Link / Track Mix repair candidate')
t = t.replace('Compatibility startup log reports revision `gmmp421-r31`;', 'Compatibility startup log reports revision `gmmp421-r33`;')
t += r'''

## r33 regression focus
- Playlist Link membership evaluation is already accepted from r32 and must remain unchanged.
- Open a Smart Playlist editor containing both a native Smart-Playlist link and a GoneSmart ordinary Playlist link. The native row must log `BRIDGE RULE ROW LABEL ... rewritten=true` and display **Smart Playlist: <name>**; the ordinary bridge must remain **Playlist: <name>**.
- Start Track Auto-DJ from the BUNT. Smart-Playlist row used in r32. After `MIX PLAY DETECTED`, the selected native `queue_id` must log `MIX CURRENT PIN`; the original Smart-Playlist queue must be pruned to GMMP Initial Size while preserving selected/current+1, then Track Mix must reach `MIX AUTO-DJ` rather than falsely report that the selected song never started.
- Start Track Auto-DJ on Avicii `Levels (Radio Edit)`. The four prepared GoneSmart IDs must still reach `SMART DJ TRACK MIX BOOTSTRAP`, then the pinned queue entry must allow post-refill verification, prune and the native Auto-DJ command. Let playback cross naturally into Track 2 and verify decoder/audio, not only metadata.
- Safety invariants remain: no 4.2.1 `CLEAR_QUEUE`, no post-materialization Track-ID retarget, no same-position playback refresh, and no current selection inferred from track ID alone.
'''
p.write_text(t)

p = Path('docs/SMART_PLAYLIST_LINKS.md')
t = p.read_text()
t += r'''

### GMMP 4.2.1 editor labels (r33)
The rule row keeps native GMMP vocabulary but distinguishes reference type: a native Smart-Playlist reference is displayed as `Smart Playlist: <name>` (localized host text), while a GoneSmart link to an ordinary playlist remains `Playlist: <name>`. This is a presentation-only change at the exact Smart-rule RecyclerView bind; serialized rule values are not modified.
'''
p.write_text(t)

print('r33 patch applied')
