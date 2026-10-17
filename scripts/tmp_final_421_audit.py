from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def sub_once(text: str, pattern: str, repl: str, label: str) -> str:
    result, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    return result


# 1) Retire accepted 4.2.1 Track-Mix probes/dead APIs without touching playback semantics.
path = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
text = read(path)
text = replace_once(
    text,
    'private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r45"',
    'private const val COMPAT_PROBE_REVISION =\n            "gmmp421-final-audit"',
    "compat revision",
)
text = sub_once(
    text,
    r'''\n        // r40: observe the real 4\.2\.1 playback-position boundary passively\.\n        // r39 proved that the natural title transition bypasses the three\n        // one-Int MusicService commands and also disproved qr\.t/ur\.b as the\n        // r45: 4\.2\.1 reused the historical class name ex3 for a context-\n        // action handler\. Its only declared MenuItem,List -> boolean method is\n        // a useful pre-materialization mapping candidate\. Observe the exact\n        // native Play call only while Track Mix is pending; never mutate the\n        // list and never invoke this method ourselves\.\n        runCatching \{.*?\n        \}\.onFailure \{\n            Log\.w\(TAG, "Track Mix context Play observer unavailable", it\)\n        \}\n\n        // current queue_position\. Bundle service-event and Auto-DJ child''',
    '''\n        // Accepted 4.2.1 playback-position observation is passive: service\n        // events and Auto-DJ child writers are wrapped only when GMMP invokes\n        // them naturally. No candidate is invoked for discovery.\n        // Bundle service-event and Auto-DJ child''',
    "remove r45 context observer hook",
)
write(path, text)

path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
text = read(path)
text = sub_once(
    text,
    r'''\n    /\*\*\n     \* r45 bounded read-only mapping for GMMP 4\.2\.1's context-action.*?\n    fun narrowNativePlaybackListForZeroPool\(\n        action: Int\?,\n        nativeItems: List<\*>\?\n    \): List<\*>\? = null\n''',
    "\n",
    "remove r45 observer and dead zero-pool list API",
)
write(path, text)

# Ensure the retired APIs had no hidden call sites before continuing.
all_main = "\n".join(
    p.read_text(encoding="utf-8")
    for p in (ROOT / "app/src/main/java").rglob("*.kt")
)
for retired in ("observeNativeContextPlayBoundary", "narrowNativePlaybackListForZeroPool"):
    if retired in all_main:
        raise SystemExit(f"retired API still referenced: {retired}")


# 2) Graduate the accepted 4.2.1 library Cursor fallback: discover once per
# concrete Auto-DJ runtime class, then reuse the proven read-only path.
path = "app/src/main/java/io/github/alagga/gonesmart/GmmpLibraryReader.kt"
text = read(path)
text = replace_once(
    text,
    '''    @Volatile\n    private var deterministicMappingFailure =\n        false\n''',
    '''    @Volatile\n    private var deterministicMappingFailure =\n        false\n\n    @Volatile\n    private var acceptedCursorAutoDjType: Class<*>? = null\n\n    private val reportedLibraryAccessBindings =\n        java.util.Collections.synchronizedSet(mutableSetOf<String>())\n''',
    "library accepted binding fields",
)
text = replace_once(
    text,
    '''        val startTime =\n            System.nanoTime()\n\n        val trackDaoField =\n            findField(\n                type = autoDjInstance.javaClass,\n                name = "r"\n            )\n\n        trackDaoField.isAccessible =\n            true\n\n        val trackDao =\n            trackDaoField.get(\n                autoDjInstance\n            )\n                ?: throw IllegalStateException(\n                    "qr.r / TrackDao is null"\n                )\n''',
    '''        val startTime =\n            System.nanoTime()\n\n        if (acceptedCursorAutoDjType == autoDjInstance.javaClass) {\n            return loadLibraryThroughCursor(\n                autoDjInstance,\n                startTime\n            )\n        }\n\n        val trackDaoField =\n            runCatching {\n                findField(\n                    type = autoDjInstance.javaClass,\n                    name = "r"\n                )\n            }.getOrElse { nativeMappingError ->\n                return loadLibraryThroughCursorFallback(\n                    autoDjInstance = autoDjInstance,\n                    startTime = startTime,\n                    trackDao = null,\n                    nativeMappingError = nativeMappingError\n                )\n            }\n\n        trackDaoField.isAccessible =\n            true\n\n        val trackDao =\n            trackDaoField.get(\n                autoDjInstance\n            )\n                ?: return loadLibraryThroughCursorFallback(\n                    autoDjInstance = autoDjInstance,\n                    startTime = startTime,\n                    trackDao = null,\n                    nativeMappingError = IllegalStateException(\n                        "GMMP TrackDao field is null"\n                    )\n                )\n''',
    "library pre-resolve fast path",
)
text = replace_once(
    text,
    '''                val tracks = loadLibraryThroughCursor(\n                    autoDjInstance,\n                    startTime\n                )\n                deterministicMappingFailure = false\n                return tracks\n''',
    '''                return loadLibraryThroughCursorFallback(\n                    autoDjInstance = autoDjInstance,\n                    startTime = startTime,\n                    trackDao = trackDao,\n                    nativeMappingError = mappingError\n                )\n''',
    "library fallback call",
)
insert_after = '''        return tracks\n    }\n\n    private fun loadLibraryThroughCursor(\n'''
helper = '''        return tracks\n    }\n\n    private fun loadLibraryThroughCursorFallback(\n        autoDjInstance: Any,\n        startTime: Long,\n        trackDao: Any?,\n        nativeMappingError: Throwable\n    ): List<GmmpLibraryTrack> {\n        return try {\n            val tracks = loadLibraryThroughCursor(\n                autoDjInstance,\n                startTime\n            )\n            acceptedCursorAutoDjType = autoDjInstance.javaClass\n            deterministicMappingFailure = false\n            val key = "cursor|" + autoDjInstance.javaClass.name\n            if (reportedLibraryAccessBindings.add(key)) {\n                Log.i(\n                    TAG,\n                    "GMMP library access bound | mode=read-only-cursor" +\n                        " | runtime=" + autoDjInstance.javaClass.name +\n                        " | discovery=retired"\n                )\n            }\n            tracks\n        } catch (cursorFailure: Throwable) {\n            deterministicMappingFailure = true\n            Log.w(\n                TAG,\n                "GMMP LIBRARY MAPPING | native query unavailable and " +\n                    "read-only Cursor fallback failed" +\n                    " | runtime=" + autoDjInstance.javaClass.name +\n                    " | dao=" + (trackDao?.javaClass?.name ?: "unavailable"),\n                cursorFailure\n            )\n            trackDao?.let(::diagnoseTrackDaoStructure)\n            cursorFailure.addSuppressed(nativeMappingError)\n            throw cursorFailure\n        }\n    }\n\n    private fun loadLibraryThroughCursor(\n'''
text = replace_once(text, insert_after, helper, "library fallback helper")
text = sub_once(
    text,
    r'''\n        deterministicMappingFailure = true\n\n        Log\.w\(\n            TAG,\n            "GMMP LIBRARY MAPPING \| expected=" \+ type\.name \+ "\.g2/1 unavailable" \+.*?\n        diagnoseTrackDaoStructure\(\n            trackDao\n        \)\n\n        throw NoSuchMethodException\(''',
    '''\n        throw NoSuchMethodException(''',
    "retire expected TrackDao failure diagnostics",
)
write(path, text)


# 3) Successful queue bindings log once per process/runtime shape, not once per
# reader instance or every queue size/current resolver.
path = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueReader.kt"
text = read(path)
text = replace_once(
    text,
    '''        private const val CURSOR_QUERY =\n            """''',
    '''        private val reportedCursorBindings =\n            java.util.Collections.synchronizedSet(mutableSetOf<String>())\n\n        private const val CURSOR_QUERY =\n            """''',
    "queue reader static reported bindings",
)
text = replace_once(
    text,
    '''\n    private val reportedCursorBindings =\n        java.util.Collections.synchronizedSet(mutableSetOf<String>())\n''',
    "\n",
    "remove per-instance queue mapping set",
)
text = replace_once(
    text,
    '''            val key =\n                autoDjInstance.javaClass.name + "|" +\n                    marker.source + "|" +\n                    if (useShuffle) "shuffle" else "queue"\n''',
    '''            val key =\n                autoDjInstance.javaClass.name + "|" +\n                    if (useShuffle) "shuffle" else "queue"\n''',
    "queue reader stable success key",
)
write(path, text)

path = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt"
text = read(path)
old = '''        Log.i(\n            TAG,\n            "QUEUE MUTATION MAPPING | dao=" + dao.javaClass.name +\n                " | model=" + modelClass.name +\n                " | readRows=" + rows.size +\n                " | queueId=" + queueId.name +\n                " | trackId=" + trackId.name +\n                " | position=" + position.name +\n                " | update=" + update.name +\n                " | delete=" + (delete?.name ?: "none") +\n                " | state=" +\n                (statePosition?.description ?: "deferred-if-needed") +\n                " | reader=" + reconstructed.boundary\n        )\n'''
new = '''        val mappingKey =\n            "mutation-bound|" + dao.javaClass.name + "|" + modelClass.name\n        if (reportedShapes.add(mappingKey)) {\n            Log.i(\n                TAG,\n                "QUEUE MUTATION MAPPING | dao=" + dao.javaClass.name +\n                    " | model=" + modelClass.name +\n                    " | readRows=" + rows.size +\n                    " | queueId=" + queueId.name +\n                    " | trackId=" + trackId.name +\n                    " | position=" + position.name +\n                    " | update=" + update.name +\n                    " | delete=" + (delete?.name ?: "none") +\n                    " | state=" +\n                    (statePosition?.description ?: "deferred-if-needed") +\n                    " | reader=" + reconstructed.boundary +\n                    " | discovery=retired"\n            )\n        }\n'''
text = replace_once(text, old, new, "queue mutation mapping log graduation")
write(path, text)


# 4) Host-UI localization: never inject an English Playlist noun if GMMP's
# live localized resource is unavailable; preserve the original native label.
path = "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt"
text = read(path)
text = replace_once(
    text,
    'val playlistLabel = NativeGmmpUiText.string(context, "playlist") ?: "Playlist"',
    'val playlistLabel = NativeGmmpUiText.string(context, "playlist") ?: return original',
    "Playlist Link localized playlist noun",
)
write(path, text)


# 5) Persistent rules/docs: mark 4.2.1 accepted, correct R72 menu evidence,
# codify probe graduation, native/read-only boundaries, logging and locale rules.
path = "AGENTS.md"
text = read(path)
text = replace_once(
    text,
    '**Last consolidated:** 2026-10-08. **Release line:** 0.4.1. **Accepted GMMP target:** 4.2.1.',
    '**Last consolidated:** 2026-10-17. **Release line:** 0.4.1. **Accepted GMMP target:** 4.2.1. **4.2.1 fix status:** accepted after R72 + final audit.',
    "AGENTS header status",
)
anchor = '''Graduation retires **runtime cost**, not just logging. Accepted-version hot paths must not keep broad reflection, SQL/Cursor readbacks, full view-tree scans or model reconstruction running continuously.\n\n## 4. Accepted GMMP 4.2.1 queue contract\n'''
rules = '''Graduation retires **runtime cost**, not just logging. Accepted-version hot paths must not keep broad reflection, SQL/Cursor readbacks, full view-tree scans or model reconstruction running continuously.\n\n### Final acceptance audit rules\n\n- A device-accepted binding is cached or otherwise reduced to its cheapest proven path for the concrete runtime type. A new/unverified runtime type or failed postcondition may re-enter semantic discovery; a normal accepted call must not redo broad inventories.\n- Successful structural/mapping diagnostics log at most once per stable runtime shape. Rich reflection/model inventories belong only to a new, ambiguous or failing boundary.\n- State-changing GMMP work uses a verified native callback/API/DAO and postcondition. Read-only SQL/Cursor access through GMMP's already-open Room database is allowed for observation or as a reader fallback when no verified native reader exists; it is never authority for an unverified write.\n- Text injected inside GMMP comes from the live host Resources/current GMMP locale. If a required native noun/action is unavailable, preserve the original native UI or omit the GoneSmart action rather than inserting arbitrary English. The companion app remains English-only.\n- A final compatibility audit checks native reuse, dynamic/fail-closed discovery, localization, hot-path reflection/view scans, repeated logging, dead probes/APIs, exact-head tests/build and release-tree residue before merge/tag.\n\n## 4. Accepted GMMP 4.2.1 queue contract\n'''
text = replace_once(text, anchor, rules, "AGENTS final audit rules")
old_r69 = '''- R69 evidence supersedes R68 as the accepted file-browser menu gate. Even with R68 expanded to 240 inspected objects, both song and directory menus still produced zero filesystem candidates, so that reflective scan is retired from the UI hot path. GMMP 4.2.1 itself exposes a stable final native menu shape after its caller configures the row: directory rows have exactly 8 visible native actions and playable-file rows have exactly 10. Because the MenuInflater hook runs before that caller-side visibility configuration, GoneSmart inserts Track Auto-DJ hidden and re-evaluates on the next main-loop turn; only the exact 10-item native shape may expose it, exact 8 stays hidden, and every other cardinality fails closed. The count is UI evidence only: click-time Track Auto-DJ still requires the existing strong pre-Play Track resolution before native Play, so menu shape never supplies Track identity or mutation authority.'''
new_r69 = '''- R72 supersedes the original R69 cardinality assumption while preserving its fail-closed design. Accepted GMMP 4.2.1 device evidence shows playable-file rows with either **9 or 10** visible native actions; exact **8** is the proven directory shape; **7 and every other cardinality remain unknown/fail-closed**. GoneSmart still inserts Track Auto-DJ hidden and re-evaluates after GMMP's caller-side visibility configuration. The count is UI eligibility evidence only: click-time Track Auto-DJ still requires the existing strong pre-Play Track resolution before native Play, so menu shape never supplies Track identity or mutation authority.'''
text = replace_once(text, old_r69, new_r69, "AGENTS R72 menu contract")
text = text.replace(
    'No playback method is replayed, prepared-match behavior is unchanged, and any identity/coordinate disagreement still fails closed. Device re-acceptance is required.',
    'No playback method is replayed, prepared-match behavior is unchanged, and any identity/coordinate disagreement still fails closed. Device re-acceptance completed successfully on 2026-10-17.',
    1,
)
text = text.replace(
    'A Current-state writer dispatch is therefore neither required nor permitted merely to prepare the verified I2 boundary. Device re-acceptance is required.',
    'A Current-state writer dispatch is therefore neither required nor permitted merely to prepare the verified I2 boundary. Device re-acceptance completed successfully on 2026-10-17.',
    1,
)
write(path, text)

# Native audit: prepend current audit while retaining the historical 4.2.0 audit.
path = "docs/NATIVE_GMMP_AUDIT.md"
text = read(path)
final_audit = '''# GMMP 4.2.1 final native-function / compatibility audit — 17 October 2026\n\n**Scope:** `fix/playlist-link-gmmp-4.2.1` after R72 and the maintainer's successful bundled device run. This section is the current release-readiness audit; the older 4.2.0 audit is retained below as historical evidence. The branch is prepared for merge, but this document does not mean it has already been merged, tagged or released.\n\n## Final verdict\n\n- Playlist Link resumes GMMP's proven native editor/chooser/save/evaluate flow; semantic shape/ownership checks and postconditions replace R8 names as authority.\n- Track Auto-DJ dispatches the clicked row's native Play action, uses GMMP's own Auto-DJ/refill lifecycle, and limits queue mutation to the verified generated `queue_table` DAO. Queue IDs/order/Current are independently re-read after mutation. No direct SQL/Room writes are used.\n- Read-only Cursor access remains intentionally available for identity/queue/library observation where 4.2.1 exposes no independently verified native reader. It runs through GMMP's already-open Room database and is never promoted to write authority.\n- File-browser eligibility is the accepted R72 contract: playable file = 9 or 10 visible native actions; directory = 8; 7/other shapes = unknown and hidden. Click-time target identity remains independently verified.\n- The accepted `MusicService.I2(int)` event argument remains opaque. Absolute Current/Current+1 authority comes only from exact target + independently verified Current writer/readback. The R71 natural boundary preserves its absolute Current coordinate.\n- Final cleanup retires the obsolete r45 read-only context-Play observer and its dead list-narrowing placeholder, caches the accepted 4.2.1 library Cursor fallback per concrete Auto-DJ runtime class, makes successful queue mapping logs one-shot per runtime shape, and preserves rich diagnostics only for new/failing boundaries.\n- GMMP-visible Playlist Link text now preserves the original label when the live localized `playlist` resource is unavailable instead of falling back to an English noun. Companion UI/logging remains English-only by design.\n\n## Performance / logging conclusion\n\nThe accepted runtime no longer keeps the retired r45 context hook or repeatedly emits successful queue mutation/mapping inventories. Library/index work remains background work; broad compatibility inventories are disabled in normal operation and remain available only for future unknown/failing GMMP shapes. Device logs can still contain GMMP/Android frame-skip or GC messages, so they are not attributed to GoneSmart without a feature-specific causal trace.\n\n## Merge gate\n\nThe fix branch is merge-ready only after the normal Build workflow is green on the exact final audit head (generated-forum check, unit tests, Debug APK, unsigned Release smoke and artifact upload) and no temporary audit workflow/script remains. The later 0.5.0 integration must rerun CI after this branch and both feature branches are combined; it must not reuse this branch's green status as proof for the merged release tree.\n\n---\n\n# Historical audit\n\n'''
if text.startswith("# GMMP localization and native-function audit — 28 September 2026"):
    text = final_audit + text
else:
    raise SystemExit("NATIVE_GMMP_AUDIT historical heading changed")
write(path, text)

path = "docs/GMMP_421_COMPLETION.md"
text = read(path)
completion = '''# GMMP 4.2.1 completion status — ACCEPTED\n\n**Final status (17 October 2026):** GMMP 4.2.1 is accepted/fixed on `fix/playlist-link-gmmp-4.2.1` after R72, the bundled maintainer device pass and the final native/performance/logging/localization audit. The chronology below is retained for provenance; older lines saying “pending” or “device re-acceptance required” describe their historical revision and are **not current blockers**. This branch is prepared for merge but is not merged/tagged/released by this status note.\n\nCurrent accepted Track Auto-DJ contracts: file-browser playable rows may have 9 or 10 visible native menu actions; 8 is the proven directory shape; 7/other shapes fail closed. `MusicService.I2(int)` identifies only the accepted natural next-source event and its integer argument is opaque. R71 preserves an independently verified absolute Current anchor on the natural I2 path; Queue-tail fallback retains its separate verified position-1 native-fill path.\n\nPlaylist Link and queue/Track Auto-DJ writers remain native-first and fail closed on ambiguous mappings. Successful compatibility discovery graduates to cached/one-shot runtime bindings; broad inventories are reserved for a new or failing runtime shape.\n\n---\n\n## Historical 4.2.1 repair ledger\n\n'''
write(path, completion + text)

path = "docs/GMMP_421_REACCEPTANCE_CHECKLIST.md"
text = read(path)
status = '''# GMMP 4.2.1 bundled re-acceptance — COMPLETED\n\n**Completed 17 October 2026.** The final R72-era device run accepted the repaired Playlist Link / Track Auto-DJ behavior, including repeated verified Track Auto-DJ initial queues and the corrected file-browser menu gate, without the previous Current-position writer dispatch timeout. The checklist below is retained as a historical investigation record from the r45 phase; it is no longer an open release blocker.\n\nFor current merge/release gating use `AGENTS.md`, `NATIVE_GMMP_AUDIT.md`, the exact-head normal Build workflow and the 0.5.0 integration test on the eventual merged tree.\n\n---\n\n## Historical r45 checklist\n\n'''
# Keep old heading/body below, but demote the duplicate heading.
if not text.startswith("# GMMP 4.2.1 bundled re-acceptance"):
    raise SystemExit("GMMP_421_REACCEPTANCE_CHECKLIST heading changed")
text = text.replace("# GMMP 4.2.1 bundled re-acceptance\n", "", 1)
write(path, status + text)

path = "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
text = read(path)
playbook = '''\n### 2026-10-17 R72 + final 4.2.1 acceptance audit\n\n- R72 corrects only the file-browser menu eligibility evidence: playable files are accepted at 9 or 10 visible native actions, exact 8 is the proven directory shape, and 7/other shapes remain unknown/fail-closed. Menu shape never supplies Track identity.\n- The final bundled device pass accepted the R70/R71 natural next-source coordinate model and Queue-tail fallback. `I2(int)` remains an event identity only; its argument is never a queue coordinate.\n- Probe graduation is now explicit implementation policy: once a concrete runtime shape is accepted, successful mapping/discovery logs are one-shot and the cheapest proven binding is reused. A new runtime type or failed postcondition re-enters semantic discovery and may emit rich diagnostics.\n- The obsolete r45 `MenuItem,List -> boolean` context-Play observer and dead zero-pool list placeholder are removed after acceptance. They were diagnostic only and supplied no mutation authority.\n- The accepted library Cursor fallback is cached per concrete Auto-DJ runtime type after the native TrackDao path is proven unavailable. This does not turn Cursor data into write authority; all state-changing queue work remains behind verified native callbacks/DAOs.\n- Final release-readiness auditing also includes GMMP-host localization, hot-path reflection/view scans, repeated logging, dead probes/APIs, exact-head CI and temporary-file residue.\n'''
write(path, text.rstrip() + "\n" + playbook)

path = "docs/LOGGING.md"
text = read(path)
needle = '''6. Update unit tests for `GoneSmartLogSummary`, and perform the relevant\n   Android device smoke test before release.\n'''
replacement = '''6. Update unit tests for `GoneSmartLogSummary`, and perform the relevant\n   Android device smoke test before release.\n7. After compatibility acceptance, a successful structural binding/mapping is\n   logged at most once per stable runtime shape. Do not key success diagnostics\n   by changing queue size/current position or recreate their de-duplication set\n   per reader instance. New/ambiguous/failing runtime shapes may still emit the\n   bounded rich diagnostics needed for the next compatibility investigation.\n'''
text = replace_once(text, needle, replacement, "logging acceptance rule")
write(path, text)

print("Final GMMP 4.2.1 audit patch applied successfully")
