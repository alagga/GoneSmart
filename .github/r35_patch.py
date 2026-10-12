from pathlib import Path
import re

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected one exact match, got {count}')
    write(path, text.replace(old, new, 1))

# 1) Expose how CURRENT was resolved so Track Mix can adopt only an explicitly
# proven request-scoped path rebind, never infer it from track ID alone.
replace_once(
    'app/src/main/java/io/github/alagga/gonesmart/QueueContext.kt',
    '''data class QueueContext(\n    val currentQueuePosition: Int,\n    val items: List<QueueItemInfo>\n) {''',
    '''data class QueueContext(\n    val currentQueuePosition: Int,\n    val items: List<QueueItemInfo>,\n    val currentResolutionSource: String? = null\n) {'''
)

reader = 'app/src/main/java/io/github/alagga/gonesmart/GmmpQueueReader.kt'
replace_once(
    reader,
    '''    fun read(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int? = null,\n        currentQueueEntryIdHint: Long? = null\n    ): QueueContext? {\n        readLegacy(autoDjInstance)?.let { return it }\n        return readThroughCursor(\n            autoDjInstance,\n            currentQueuePositionHint,\n            currentQueueEntryIdHint\n        )\n    }''',
    '''    fun read(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int? = null,\n        currentQueueEntryIdHint: Long? = null,\n        currentTrackPathHint: String? = null\n    ): QueueContext? {\n        readLegacy(autoDjInstance)?.let { return it }\n        return readThroughCursor(\n            autoDjInstance,\n            currentQueuePositionHint,\n            currentQueueEntryIdHint,\n            currentTrackPathHint\n        )\n    }'''
)
replace_once(
    reader,
    '''    private fun readThroughCursor(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int?,\n        currentQueueEntryIdHint: Long?\n    ): QueueContext? =''',
    '''    private fun readThroughCursor(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int?,\n        currentQueueEntryIdHint: Long?,\n        currentTrackPathHint: String?\n    ): QueueContext? ='''
)
replace_once(
    reader,
    '''            val marker = resolveCurrentMarker(\n                autoDjInstance,\n                rows,\n                currentQueuePositionHint,\n                currentQueueEntryIdHint\n            ) ?: return@runCatching null''',
    '''            val marker = resolveCurrentMarker(\n                autoDjInstance,\n                rows,\n                currentQueuePositionHint,\n                currentQueueEntryIdHint,\n                currentTrackPathHint\n            ) ?: return@runCatching null'''
)
replace_once(
    reader,
    '''            QueueContext(\n                currentQueuePosition = currentQueuePosition,\n                items = items\n            )''',
    '''            QueueContext(\n                currentQueuePosition = currentQueuePosition,\n                items = items,\n                currentResolutionSource = marker.source\n            )'''
)
replace_once(
    reader,
    '''    private fun resolveCurrentMarker(\n        autoDjInstance: Any,\n        rows: List<CursorRow>,\n        currentQueuePositionHint: Int?,\n        currentQueueEntryIdHint: Long?\n    ): CurrentMarker? {''',
    '''    private fun resolveCurrentMarker(\n        autoDjInstance: Any,\n        rows: List<CursorRow>,\n        currentQueuePositionHint: Int?,\n        currentQueueEntryIdHint: Long?,\n        currentTrackPathHint: String?\n    ): CurrentMarker? {'''
)
replace_once(
    reader,
    '''            if (matches.size == 1) {\n                return CurrentMarker(\n                    rowIndex = matches.single(),\n                    orderKind = OrderKind.QUEUE,\n                    source = "track-mix-entry-hint"\n                )\n            }\n            Log.w(\n                TAG,\n                "GMMP QUEUE CURRENT | queue entry hint rejected" +\n                    " | entryId=" + entryId +\n                    " | matches=" + matches.size\n            )''',
    '''            if (matches.size == 1) {\n                return CurrentMarker(\n                    rowIndex = matches.single(),\n                    orderKind = OrderKind.QUEUE,\n                    source = "track-mix-entry-hint"\n                )\n            }\n            if (matches.isEmpty() && currentTrackPathHint != null) {\n                val resolution = TrackMixCurrentPathRebindPolicy.resolve(\n                    queuePaths = rows.map { it.track.path },\n                    expectedPath = currentTrackPathHint\n                )\n                resolution.index?.let { index ->\n                    val rebound = rows[index]\n                    Log.i(\n                        TAG,\n                        "GMMP QUEUE CURRENT | track-mix path rebind" +\n                            " | oldEntry=" + entryId +\n                            " | newEntry=" + rebound.queueEntryId +\n                            " | index=" + index +\n                            " | exact=true"\n                    )\n                    return CurrentMarker(\n                        rowIndex = index,\n                        orderKind = OrderKind.QUEUE,\n                        source = "track-mix-path-rebind"\n                    )\n                }\n                Log.w(\n                    TAG,\n                    "GMMP QUEUE CURRENT | track-mix path rebind rejected" +\n                        " | oldEntry=" + entryId +\n                        " | matches=" + resolution.matchCount\n                )\n            }\n            Log.w(\n                TAG,\n                "GMMP QUEUE CURRENT | queue entry hint rejected" +\n                    " | entryId=" + entryId +\n                    " | matches=" + matches.size\n            )'''
)

# 2) Request-scoped exact path policy: exact equality only; ambiguity closes.
write(
    'app/src/main/java/io/github/alagga/gonesmart/TrackMixCurrentPathRebindPolicy.kt',
    '''package io.github.alagga.gonesmart\n\ninternal object TrackMixCurrentPathRebindPolicy {\n    data class Resolution(\n        val index: Int?,\n        val matchCount: Int\n    )\n\n    fun resolve(\n        queuePaths: List<String?>,\n        expectedPath: String?\n    ): Resolution {\n        if (expectedPath.isNullOrBlank()) return Resolution(null, 0)\n        val matches = queuePaths.indices.filter { index ->\n            queuePaths[index] == expectedPath\n        }\n        return Resolution(\n            index = matches.singleOrNull(),\n            matchCount = matches.size\n        )\n    }\n}\n'''
)
write(
    'app/src/test/java/io/github/alagga/gonesmart/TrackMixCurrentPathRebindPolicyTest.kt',
    '''package io.github.alagga.gonesmart\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Test\n\nclass TrackMixCurrentPathRebindPolicyTest {\n    @Test\n    fun `unique exact path resolves its native row`() {\n        val result = TrackMixCurrentPathRebindPolicy.resolve(\n            listOf("/a.mp3", "/b.mp3", "/c.mp3"),\n            "/b.mp3"\n        )\n        assertEquals(1, result.index)\n        assertEquals(1, result.matchCount)\n    }\n\n    @Test\n    fun `missing exact path fails closed`() {\n        val result = TrackMixCurrentPathRebindPolicy.resolve(\n            listOf("/a.mp3", "/b.mp3"),\n            "/c.mp3"\n        )\n        assertNull(result.index)\n        assertEquals(0, result.matchCount)\n    }\n\n    @Test\n    fun `duplicate exact path fails closed`() {\n        val result = TrackMixCurrentPathRebindPolicy.resolve(\n            listOf("/b.mp3", "/b.mp3"),\n            "/b.mp3"\n        )\n        assertNull(result.index)\n        assertEquals(2, result.matchCount)\n    }\n\n    @Test\n    fun `blank or null path never resolves`() {\n        assertNull(\n            TrackMixCurrentPathRebindPolicy.resolve(\n                listOf("/a.mp3"),\n                null\n            ).index\n        )\n        assertNull(\n            TrackMixCurrentPathRebindPolicy.resolve(\n                listOf("/a.mp3"),\n                ""\n            ).index\n        )\n    }\n\n    @Test\n    fun `path comparison is exact and never normalized`() {\n        val result = TrackMixCurrentPathRebindPolicy.resolve(\n            listOf("/Music/BUNT.mp3"),\n            "/music/BUNT.mp3"\n        )\n        assertNull(result.index)\n        assertEquals(0, result.matchCount)\n    }\n}\n'''
)

# 3) Track Mix adopts the replacement native queue_id only when the queue
# reader says CURRENT came from the exact request path during the short guard.
controller = 'app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt'
replace_once(
    controller,
    '''    private data class Snapshot(\n        val ids: List<Long>,\n        val currentIndex: Int,\n        val entryIds: List<Long> = emptyList()\n    ) {''',
    '''    private data class Snapshot(\n        val ids: List<Long>,\n        val currentIndex: Int,\n        val entryIds: List<Long> = emptyList(),\n        val currentResolutionSource: String? = null\n    ) {'''
)
replace_once(
    controller,
    '''        @Volatile var prePlayInitialSize = 0\n        @Volatile var nativePlaybackWindowApplied = false\n        @Volatile var bootstrapAppliedTrackIds: List<Long> = emptyList()''',
    '''        @Volatile var prePlayInitialSize = 0\n        @Volatile var bootstrapAppliedTrackIds: List<Long> = emptyList()'''
)
text = read(controller)
start = text.find('    /**\n     * r34: if provider recommendations are genuinely empty')
end = text.find('    fun onNativeAutoDjRefillRequested(requested: Int) {', start)
if start < 0 or end < 0:
    raise SystemExit('TrackMixController: r34 native play window block not found')
text = text[:start] + text[end:]
write(controller, text)
replace_once(
    controller,
    '''        var stableSnapshot: Snapshot? = null\n        var stableAt = 0L\n        val deadline = SystemClock.elapsedRealtime() +''',
    '''        var stableSnapshot: Snapshot? = null\n        var stableAt = 0L\n        var pathRebindApplied = false\n        val deadline = SystemClock.elapsedRealtime() +'''
)
replace_once(
    controller,
    '''            Thread.sleep(if (targetIdentity == null) 160L else 400L)\n            val current = queueSnapshot(targetIdentity?.queueEntryId) ?: continue\n            val currentTrack = current.currentId ?: continue''',
    '''            Thread.sleep(if (targetIdentity == null) 160L else 400L)\n            val sampleAt = SystemClock.elapsedRealtime()\n            val rebindPathHint = if (\n                targetIdentity != null &&\n                !pathRebindApplied &&\n                detectedAt > 0L &&\n                completionGuard > 0L &&\n                sampleAt - detectedAt <= completionGuard\n            ) {\n                request.prePlayTarget?.path\n            } else {\n                null\n            }\n            val current = queueSnapshot(\n                currentQueueEntryIdHint = targetIdentity?.queueEntryId,\n                currentTrackPathHint = rebindPathHint\n            ) ?: continue\n            val currentTrack = current.currentId ?: continue'''
)
replace_once(
    controller,
    '''            val target = targetIdentity ?: continue\n            val currentStillSelected =\n                TrackMixPlaybackIdentityPolicy.sameCurrent(target, identity)''',
    '''            val target = targetIdentity ?: continue\n            if (current.currentResolutionSource == "track-mix-path-rebind") {\n                val replacementEntry = identity.queueEntryId\n                if (\n                    replacementEntry != null &&\n                    replacementEntry != target.queueEntryId &&\n                    identity.trackId == target.trackId\n                ) {\n                    pathRebindApplied = true\n                    Log.i(\n                        TAG,\n                        "MIX CURRENT REBIND | oldEntry=" +\n                            (target.queueEntryId ?: -1L) +\n                            " | newEntry=" + replacementEntry +\n                            " | track=" + identity.trackId +\n                            " | source=exact-path | verified=true"\n                    )\n                    targetIdentity = identity\n                    detectedAt = now\n                    detectedQueueSize = current.ids.size\n                    stableSnapshot = current\n                    stableAt = now\n                    continue\n                }\n                Log.w(\n                    TAG,\n                    "MIX CURRENT REBIND | rejected=true" +\n                        " | oldEntry=" + (target.queueEntryId ?: -1L) +\n                        " | candidateEntry=" + (replacementEntry ?: -1L) +\n                        " | oldTrack=" + target.trackId +\n                        " | candidateTrack=" + identity.trackId\n                )\n                stableSnapshot = null\n                stableAt = now\n                continue\n            }\n            val currentStillSelected =\n                TrackMixPlaybackIdentityPolicy.sameCurrent(target, identity)'''
)
replace_once(
    controller,
    '''    private fun queueSnapshot(\n        currentQueueEntryIdHint: Long? = null\n    ): Snapshot? = runCatching {''',
    '''    private fun queueSnapshot(\n        currentQueueEntryIdHint: Long? = null,\n        currentTrackPathHint: String? = null\n    ): Snapshot? = runCatching {'''
)
replace_once(
    controller,
    '''            val context = queueReader.read(\n                autoDj,\n                positionWriter?.read(),\n                currentQueueEntryIdHint\n            ) ?: return@runCatching null''',
    '''            val context = queueReader.read(\n                autoDj,\n                positionWriter?.read(),\n                currentQueueEntryIdHint,\n                currentTrackPathHint\n            ) ?: return@runCatching null'''
)
replace_once(
    controller,
    '''            return@runCatching Snapshot(\n                context.items.map { it.track.id },\n                currentIndex,\n                context.items.map { it.queueEntryId }\n            )''',
    '''            return@runCatching Snapshot(\n                context.items.map { it.track.id },\n                currentIndex,\n                context.items.map { it.queueEntryId },\n                context.currentResolutionSource\n            )'''
)

# 4) Retire the r34 w1 source-window experiment. The latest exact-head BUNT
# log proved context-track Play never crossed that list boundary.
module = 'app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt'
replace_once(module, 'private const val COMPAT_PROBE_REVISION = "gmmp421-r34"', 'private const val COMPAT_PROBE_REVISION = "gmmp421-r35"')
replace_once(
    module,
    '''                val originalList = chain.getArg(listIndex) as? List<*>\n                val trackMixWindow = originalList?.let { source ->\n                    trackMixController.windowNativePlaybackForTrackMix(\n                        action,\n                        source\n                    )\n                }\n                val effectiveList = trackMixWindow ?: originalList\n                val reversed = queueFlipController\n                    .consumeReversePlaylistForNativePlay(\n                        action,\n                        effectiveList\n                    )\n                val replacementList: List<*>? =\n                    reversed?.tracks ?: trackMixWindow''',
    '''                val originalList = chain.getArg(listIndex) as? List<*>\n                val reversed = queueFlipController\n                    .consumeReversePlaylistForNativePlay(\n                        action,\n                        originalList\n                    )\n                val replacementList: List<*>? = reversed?.tracks'''
)

for dead in [
    'app/src/main/java/io/github/alagga/gonesmart/TrackMixNativePlayWindowPolicy.kt',
    'app/src/test/java/io/github/alagga/gonesmart/TrackMixNativePlayWindowPolicyTest.kt'
]:
    p = ROOT / dead
    if not p.exists():
        raise SystemExit(f'{dead}: expected r34 file missing')
    p.unlink()

# 5) Durable evidence and one-pass device acceptance.
append = '''\n\n## GMMP 4.2.1 r35 Track Mix CURRENT identity\n- r34 device evidence proved the BUNT. context-track Play does not traverse the `MusicService.w1(action=0, List, ...)` list boundary: `prepared=0` was observed but `MIX NATIVE PLAY WINDOW` never fired. That source-window experiment is retired and must not be revived as a Track Mix dependency.\n- On 4.2.1 a Smart-Playlist rebuild may replace the selected row's native `queue_id` while the already-proven selected audio keeps playing. After Track Mix has first proven CURRENT by native queue entry, and only inside the existing Smart-source completion guard, a vanished entry hint may rebind CURRENT to the pre-resolved exact `track_uri` iff that exact path occurs exactly once in the rebuilt native queue.\n- The exact-path rebind is request-scoped, one-shot, case-sensitive, and non-normalizing. Zero or multiple exact path matches fail closed. Track ID alone never authorizes the rebind. Once accepted, the replacement native `queue_id` becomes the new pinned identity for settle/prune/Auto-DJ verification.\n- The accepted r34 Smart-Playlist row-label behavior remains invariant: preserve GMMP's native styled `CharSequence` and replace only the localized prefix; never mutate row gravity, text size, typeface, or layout.\n'''
for doc in ['AGENTS.md', 'docs/GMMP_COMPATIBILITY_PLAYBOOK.md', 'docs/GMMP_421_COMPLETION.md']:
    text = read(doc)
    if '## GMMP 4.2.1 r35 Track Mix CURRENT identity' not in text:
        write(doc, text.rstrip() + append + '\n')

check = 'docs/GMMP_421_REACCEPTANCE_CHECKLIST.md'
text = read(check)
text = text.replace('current **r34** Playlist Link / Track Mix repair candidate', 'current **r35** Playlist Link / Track Mix repair candidate', 1)
text = re.sub(r'Repair source commit: `[^`]+`\.', 'Repair source commit: `R35_REPAIR_SOURCE_PENDING`.', text, count=1)
r35 = '''\n\n## r35 regression focus\n- The Smart-Playlist rule label is accepted from r34: it must remain **Smart Playlist: <name>** with native typography/alignment unchanged.\n- Re-run the zero-provider BUNT. Track Mix. The retired `MIX NATIVE PLAY WINDOW` marker must be absent. After the initially proven BUNT. queue entry disappears during Smart-Playlist materialization, expect exactly one `GMMP QUEUE CURRENT | track-mix path rebind ... exact=true` followed by `MIX CURRENT REBIND ... source=exact-path | verified=true`.\n- The rebind must adopt the replacement native `queue_id`, then reach `MIX PLAY VERIFIED`, `MIX CURRENT PIN`, the existing prune/Auto-DJ path, and finally `MIX AUTO-DJ` / `MIX VERIFIED`. Zero or duplicate exact path matches must not rebind.\n- Re-run Avicii `Levels (Radio Edit)` as regression. Its prepared recommendation pool must continue through the accepted controller-owned `qr` bootstrap; no exact-path rebind should be needed in the normal successful path, and the natural Track-2 decoder/audio transition must remain intact.\n- Safety invariants remain: no 4.2.1 `CLEAR_QUEUE`, no same-position playback refresh, no post-materialization Track-ID retarget, and no current selection inferred from track ID alone.\n'''
if '## r35 regression focus' not in text:
    text = text.rstrip() + r35 + '\n'
write(check, text)

print('r35 patch applied')
