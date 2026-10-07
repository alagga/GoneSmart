from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text()

def write(path, text):
    (ROOT / path).write_text(text)

def require(cond, message):
    if not cond:
        raise SystemExit(message)

# 1) Pure policy: normalize a sparse absolute queue-position basis while
# preserving native order and the identity of the current row.
policy_path = "app/src/main/java/io/github/alagga/gonesmart/QueuePositionNormalizationPolicy.kt"
policy = '''package io.github.alagga.gonesmart

/**
 * Pure planning for the GMMP 4.2.1 Track Auto-DJ queue-position repair.
 *
 * Track Auto-DJ can intentionally delete thousands of source rows while
 * preserving one native queue entry. 4.2.0 exposed the queue append allocator
 * directly and reset it with the seed. The equivalent 4.2.1 allocator is not
 * behaviorally proven, so the compatibility path must never guess a field.
 * Instead, after a native refill, a small Track-Auto-DJ-owned queue may be
 * rebased through the already-proven native Queue DAO update writer.
 */
internal object QueuePositionNormalizationPolicy {
    data class Row(
        val queueId: Long,
        val position: Int
    )

    data class Plan(
        val orderedQueueIds: List<Long>,
        val originalPositions: List<Int>,
        val normalizedPositions: List<Int>,
        val currentQueueId: Long,
        val currentNewPosition: Int
    )

    fun plan(rows: List<Row>, currentQueueId: Long): Plan? {
        if (rows.isEmpty()) return null
        require(rows.all { it.position > 0 }) {
            "Queue positions must be positive"
        }
        require(rows.map { it.queueId }.toSet().size == rows.size) {
            "Queue IDs must be unique"
        }
        require(rows.map { it.position }.toSet().size == rows.size) {
            "Queue positions must be unique"
        }

        val ordered = rows.sortedBy { it.position }
        val currentIndex = ordered.indexOfFirst { it.queueId == currentQueueId }
        require(currentIndex >= 0) {
            "Current queue entry must exist in the native rows"
        }

        val original = ordered.map { it.position }
        val normalized = List(ordered.size) { it + 1 }
        if (original == normalized) return null

        return Plan(
            orderedQueueIds = ordered.map { it.queueId },
            originalPositions = original,
            normalizedPositions = normalized,
            currentQueueId = currentQueueId,
            currentNewPosition = currentIndex + 1
        )
    }
}
'''
(ROOT / policy_path).write_text(policy)

test_path = "app/src/test/java/io/github/alagga/gonesmart/QueuePositionNormalizationPolicyTest.kt"
test = '''package io.github.alagga.gonesmart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QueuePositionNormalizationPolicyTest {
    @Test
    fun `sparse Track Auto-DJ positions are rebased without changing order`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(10L, 1),
                QueuePositionNormalizationPolicy.Row(11L, 5905),
                QueuePositionNormalizationPolicy.Row(12L, 5906),
                QueuePositionNormalizationPolicy.Row(13L, 5907),
                QueuePositionNormalizationPolicy.Row(14L, 5908)
            ),
            currentQueueId = 10L
        )!!

        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), plan.orderedQueueIds)
        assertEquals(listOf(1, 2, 3, 4, 5), plan.normalizedPositions)
        assertEquals(1, plan.currentNewPosition)
    }

    @Test
    fun `current row is moved to its corresponding normalized position`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(20L, 5908),
                QueuePositionNormalizationPolicy.Row(21L, 5912),
                QueuePositionNormalizationPolicy.Row(22L, 5913)
            ),
            currentQueueId = 21L
        )!!

        assertEquals(listOf(1, 2, 3), plan.normalizedPositions)
        assertEquals(2, plan.currentNewPosition)
    }

    @Test
    fun `already contiguous positions need no native mutation`() {
        assertNull(
            QueuePositionNormalizationPolicy.plan(
                rows = listOf(
                    QueuePositionNormalizationPolicy.Row(30L, 1),
                    QueuePositionNormalizationPolicy.Row(31L, 2)
                ),
                currentQueueId = 31L
            )
        )
    }

    @Test
    fun `ambiguous native identity fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            QueuePositionNormalizationPolicy.plan(
                rows = listOf(
                    QueuePositionNormalizationPolicy.Row(40L, 1),
                    QueuePositionNormalizationPolicy.Row(40L, 2)
                ),
                currentQueueId = 40L
            )
        }
    }
}
'''
(ROOT / test_path).write_text(test)

# 2) Native Queue DAO repair. Never guess the 4.2.1 append allocator; use the
# already-proven native Queue update writer and independent Cursor postcondition.
bridge_path = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt"
bridge = read(bridge_path)
require("fun normalizeQueuePositionsIfNeeded()" not in bridge, "r42 bridge patch already applied")
anchor = "\n    private fun resolve(\n"
require(anchor in bridge, "GmmpQueueMutationBridge resolve anchor missing")
method = r'''

    /**
     * Rebase a Track-Auto-DJ-owned sparse queue to 1..N after GMMP's native
     * refill. GMMP 4.2.0 exposed a verified append allocator; 4.2.1 does not.
     * Do not guess its obfuscated replacement. Instead update only the proven
     * native Queue entities, move the already-passively-verified playback
     * pointer if required, and require an independent Cursor postcondition.
     */
    fun normalizeQueuePositionsIfNeeded(): Boolean {
        val resolved = resolve(
            requireDelete = false,
            requireStatePosition = false
        )
        val ordered = resolved.rows.sortedBy {
            number(resolved.position, it).toInt()
        }
        if (ordered.isEmpty()) return false

        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("GMMP current queue entry unavailable for normalization")
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = ordered.map {
                QueuePositionNormalizationPolicy.Row(
                    queueId = number(resolved.queueId, it).toLong(),
                    position = number(resolved.position, it).toInt()
                )
            },
            currentQueueId = current.queueEntryId
        ) ?: return false

        val oldPositions = ordered.map {
            number(resolved.position, it).toInt()
        }
        val oldCurrentPosition = resolved.context.currentQueuePosition
        val statePosition = if (
            oldCurrentPosition == plan.currentNewPosition
        ) {
            null
        } else {
            resolveStatePosition(oldCurrentPosition)
        }
        val oldState = statePosition?.read()

        try {
            ordered.forEachIndexed { index, row ->
                setInt(resolved.position, row, index + 1)
            }
            resolved.update.invoke(
                resolved.dao,
                ArrayList(ordered)
            )
            statePosition?.write(plan.currentNewPosition)

            val verified = GmmpQueueReader().read(
                autoDj,
                statePosition?.read() ?: plan.currentNewPosition
            ) ?: error("Queue normalization verification unavailable")
            val verifiedOrdered = verified.items.sortedBy { it.queuePosition }
            val verifiedIds = verifiedOrdered.map { it.queueEntryId }
            val verifiedPositions = verifiedOrdered.map { it.queuePosition }
            val verifiedCurrent = verified.items.singleOrNull {
                it.state == QueueItemState.CURRENT
            }?.queueEntryId
            require(
                verifiedIds == plan.orderedQueueIds &&
                    verifiedPositions == plan.normalizedPositions &&
                    verifiedCurrent == plan.currentQueueId
            ) {
                "GMMP Track Auto-DJ queue normalization failed postcondition"
            }

            Log.i(
                TAG,
                "QUEUE POSITION NORMALIZE | old=" +
                    plan.originalPositions.joinToString(",") +
                    " | new=1.." + plan.normalizedPositions.size +
                    " | currentId=" + plan.currentQueueId +
                    " | currentPosition=" + plan.currentNewPosition +
                    " | verified=true"
            )
            return true
        } catch (failure: Throwable) {
            runCatching {
                ordered.forEachIndexed { index, row ->
                    setInt(resolved.position, row, oldPositions[index])
                }
                resolved.update.invoke(
                    resolved.dao,
                    ArrayList(ordered)
                )
                if (statePosition != null && oldState != null) {
                    statePosition.write(oldState)
                }
            }.onFailure { rollback ->
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }
'''
bridge = bridge.replace(anchor, method + anchor, 1)
write(bridge_path, bridge)

# 3) Track Mix owns the compatibility normalization only for the Auto-DJ
# session it created. Unrelated native list Play disarms it.
track_path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
track = read(track_path)
require("managedAutoDj" not in track, "r42 TrackMix patch already applied")
old = """    @Volatile private var nativeQueue: WeakReference<Any>? = null\n    @Volatile private var nativeAutoDj: WeakReference<Any>? = null\n\n    @Volatile private var enabled = true\n"""
new = """    @Volatile private var nativeQueue: WeakReference<Any>? = null\n    @Volatile private var nativeAutoDj: WeakReference<Any>? = null\n    // 4.2.1 no longer exposes the verified 4.2.0 append-position allocator.\n    // Scope the bounded native-DAO position repair only to a Track Auto-DJ\n    // session we created; unrelated native list Play disarms it.\n    @Volatile private var managedAutoDj: WeakReference<Any>? = null\n\n    @Volatile private var enabled = true\n"""
require(old in track, "TrackMix native field anchor missing")
track = track.replace(old, new, 1)

old = """    fun onNativePlaybackQueueUpdated(origin: String) {\n        val request = pending ?: return\n        if (request.stage == \"WAIT_PLAY\") {\n"""
new = """    fun onNativePlaybackQueueUpdated(origin: String) {\n        val request = pending\n        if (request == null) {\n            managedAutoDj = null\n            return\n        }\n        if (request.stage == \"WAIT_PLAY\") {\n"""
require(old in track, "TrackMix native playback observer anchor missing")
track = track.replace(old, new, 1)

anchor = """    private fun awaitAutoDjCommandRefillBoundary(request: Pending) {\n"""
require(anchor in track, "TrackMix refill-boundary anchor missing")
helpers = '''    fun isExplicitInitialRefill(): Boolean =
        pending?.stage == "FILLING" &&
            (nativeRefillAllowance.get() ?: 0) > 0

    fun normalizeManagedQueueAfterNativeRefill(autoDj: Any): Boolean {
        if (managedAutoDj?.get() !== autoDj) return false
        return runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = nativePositionWriterProvider(autoDj)
            ).normalizeQueuePositionsIfNeeded()
        }.onFailure {
            Log.e(
                TAG,
                "MIX QUEUE NORMALIZE | native 4.2.1 position repair failed",
                it
            )
        }.getOrDefault(false)
    }

    private fun armManagedAutoDjSession() {
        val autoDj = nativeAutoDj?.get() ?: return
        val legacyQueue = field(autoDj, "q")
            ?.takeIf { it.javaClass.name == "ex3" }
        if (legacyQueue == null) {
            managedAutoDj = WeakReference(autoDj)
        }
    }

'''
track = track.replace(anchor, helpers + anchor, 1)

old = """            Log.i(\n                TAG,\n                \"MIX SEED | queueSize=${cleared.ids.size} | \" +\n                    \"currentPreserved=true\"\n            )\n\n            request.stage = \"FILLING\"\n"""
new = """            Log.i(\n                TAG,\n                \"MIX SEED | queueSize=${cleared.ids.size} | \" +\n                    \"currentPreserved=true\"\n            )\n            armManagedAutoDjSession()\n\n            request.stage = \"FILLING\"\n"""
require(old in track, "TrackMix seed-arm anchor missing")
track = track.replace(old, new, 1)
write(track_path, track)

# 4) Smart DJ: all queue snapshots reuse the accepted native Current hint,
# and every native refill returns through the scoped Track-Mix repair.
module_path = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
module = read(module_path)
require("TRACK_MIX_INITIAL_SMART_WAIT_MS" not in module, "r42 module patch already applied")
old = """        private const val SMART_PREPARE_TIMEOUT_SECONDS =\n            60L\n"""
new = """        private const val SMART_PREPARE_TIMEOUT_SECONDS =\n            60L\n\n        // Track Auto-DJ must never strand playback on the isolated seed while\n        // the network recommendation pipeline takes many seconds. Give the\n        // smart pool a short head start, then let native GMMP fill Initial\n        // Size immediately while the same pool preparation keeps running.\n        private const val TRACK_MIX_INITIAL_SMART_WAIT_MS =\n            1_500L\n"""
require(old in module, "Smart prepare constant anchor missing")
module = module.replace(old, new, 1)

# Replace all five live Smart-DJ queue reads (startup + refill before/after).
module, count = re.subn(
    r"queueReader\.read\(\s*autoDjInstance\s*\)",
    "readQueueContext(autoDjInstance)",
    module
)
require(count == 5, f"Expected 5 Smart-DJ queue reads, replaced {count}")

refill_anchor = """    private fun installAutoDjRefillHook(\n"""
require(refill_anchor in module, "Auto-DJ refill hook anchor missing")
queue_helpers = '''    private fun readQueueContext(autoDjInstance: Any): QueueContext? {
        val verifiedPosition = queueFlipController
            .verifiedPositionWriter(autoDjInstance)
            ?.read()
        return queueReader.read(autoDjInstance, verifiedPosition)
    }

    private fun proceedNativeRefill(
        autoDjInstance: Any,
        proceed: () -> Any?
    ): Any? {
        val result = proceed()
        trackMixController.normalizeManagedQueueAfterNativeRefill(autoDjInstance)
        return result
    }

'''
module = module.replace(refill_anchor, queue_helpers + refill_anchor, 1)

start = module.index(refill_anchor)
next_anchor = "    private fun installAutoDjSelectionHook("
end = module.index(next_anchor, start)
refill_segment = module[start:end]
proceed_count = refill_segment.count("chain.proceed()")
require(proceed_count >= 3, f"Expected >=3 native refill proceed paths, found {proceed_count}")
refill_segment = refill_segment.replace(
    "chain.proceed()",
    "proceedNativeRefill(autoDjInstance) { chain.proceed() }"
)
module = module[:start] + refill_segment + module[end:]

# Track-Mix initial fill: short wait, but do NOT cancel the in-flight pool fill.
ensure_anchor = "    private fun ensureRecommendationPoolReady("
start = module.index(ensure_anchor)
start_pool = module.index("    private fun startPoolFill(", start)
ensure = module[start:start_pool]
pattern = re.compile(
    r"return try \{\s*future\.get\(\s*SMART_PREPARE_TIMEOUT_SECONDS,\s*TimeUnit\.SECONDS\s*\)",
    re.S
)
replacement = '''val fastInitialWait = trackMixController.isExplicitInitialRefill()
        val waitTimeoutMs = if (fastInitialWait) {
            TRACK_MIX_INITIAL_SMART_WAIT_MS
        } else {
            TimeUnit.SECONDS.toMillis(SMART_PREPARE_TIMEOUT_SECONDS)
        }

        return try {
            future.get(
                waitTimeoutMs,
                TimeUnit.MILLISECONDS
            )'''
ensure, timeout_count = pattern.subn(replacement, ensure, count=1)
require(timeout_count == 1, "Could not replace recommendation wait timeout")
catch_old = """        } catch (timeoutException: TimeoutException) {\n\n            pipelineGeneration\n"""
catch_new = """        } catch (timeoutException: TimeoutException) {\n\n            if (fastInitialWait) {\n                // Do not cancel or invalidate this fill: it becomes the warm\n                // pool for ordinary upcoming refills after native GMMP has\n                // made Next available immediately.\n                Log.i(\n                    TAG,\n                    \"SMART DJ INITIAL FAST FALLBACK | waitedMs=\" +\n                        TRACK_MIX_INITIAL_SMART_WAIT_MS +\n                        \" | poolFill=continuing\"\n                )\n                return false\n            }\n\n            pipelineGeneration\n"""
require(catch_old in ensure, "Recommendation timeout catch anchor missing")
ensure = ensure.replace(catch_old, catch_new, 1)
module = module[:start] + ensure + module[start_pool:]
write(module_path, module)

# 5) Persist the evidence and invariants.
agents_path = "AGENTS.md"
agents = read(agents_path)
marker = "## GMMP 4.2.1 r42 queue continuity"
require(marker not in agents, "r42 AGENTS note already present")
agents += '''

## GMMP 4.2.1 r42 queue continuity

- Track Auto-DJ seed isolation changes the logical queue root. GMMP 4.2.0 had a separately verified native append-position allocator (`ex3.p`) that had to be reset together with the playback pointer. If a future/obfuscated GMMP build does not expose that allocator with behavioral proof, **never guess a mutable field by value or shape**. Keep mutation native-first: use the proven Queue DAO writer, preserve row identity/order, rebase only the Track-Auto-DJ-owned small queue to contiguous `1..N`, and require the independent Cursor + verified playback-position postcondition. Retire this compatibility repair once the real allocator is passively proven.
- Once a live GMMP current-position writer/readback has been passively verified, every Smart-DJ queue snapshot must reuse that exact read hint. A generic structural `CURRENT` miss after playback/refill is not a reason to discard generated-origin metadata or start a new queue session.
- Track Auto-DJ must not leave the user with a one-row queue while network recommendation preparation runs for many seconds. Give the smart pool only a short bounded head start for the explicit Initial-Size refill; if it is not ready, let native GMMP fill immediately **without cancelling the same pool fill**, so Next remains available and Smart DJ can take over subsequent refills.
'''
write(agents_path, agents)

playbook_path = "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
playbook = read(playbook_path)
require("### Track Auto-DJ queue-root continuity" not in playbook, "r42 playbook note already present")
playbook += '''

### Track Auto-DJ queue-root continuity

Track Auto-DJ is a special queue-root mutation: it preserves one native row and removes the previous source list. Treat the native playback pointer and the native append-position basis as separate state. On GMMP 4.2.0 both were behaviorally known; on 4.2.1 only the current-position writer/readback is currently proven. Do not infer the append allocator from an obfuscated integer field. Until that native boundary is proven, the accepted compatibility fallback is scoped to the Track-Auto-DJ-owned queue: after native refill, rebase sparse Queue DAO row positions to contiguous `1..N`, move only the passively verified playback pointer when required, and independently Cursor-verify row order/current identity.

Smart-DJ session tracking must consume the same passively verified current-position hint as queue mutation. If a refill read silently falls back to structural CURRENT discovery, generated rows can be misclassified as user anchors, which resets the recommendation pool on every skip. The correct steady state is one queue session plus pool hits/background top-up, not repeated `QUEUE SESSION ... new=true` cycles.

For the explicit Track-Auto-DJ Initial-Size refill, network recommendation preparation is opportunistic rather than a playback gate. Use a short bounded smart wait, keep the fill future alive on timeout, and immediately pass the refill to native GMMP so the Next action exists while the recommendation pool continues warming.
'''
write(playbook_path, playbook)

r42_path = "docs/GMMP_421_QUEUE_R42.md"
r42 = '''# GMMP 4.2.1 Queue compatibility — r42 queue continuity

## Device evidence

The 2026-10-07 device pass confirms the preceding Track Auto-DJ CURRENT fix and the navigation performance fix: the maintainer reports the localized Track Auto-DJ action working again and tab swiping smooth.

The same pass exposes one older 4.2.0 invariant that was lost in the 4.2.1 port:

- native Play materializes a 5904-row source queue;
- Track Auto-DJ correctly deletes 5903 stale rows and preserves exactly one seed;
- after refill, GMMP continues using absolute `queue_position` values around 5905+ instead of a fresh `1..N` basis;
- GMMP later asks for positions around 5912–5918 while only a handful of rows are live;
- the Queue UI consequently reports an impossible current index/count and can omit the actual current row.

The accepted 4.2.0 implementation explicitly reset both the playback pointer and `ex3.p`, GMMP's then-proven next-position allocator. The 4.2.1 port remapped the playback pointer but did not establish a behaviorally proven replacement for that separate allocator.

The log also shows why Smart DJ remains slow: post-refill queue reads repeatedly lose CURRENT, generated rows are not retained as generated session context, and `QueueSessionTracker` opens a new session/pool on later skips. There are no steady-state pool hits. The initial Track Auto-DJ refill can additionally wait more than ten seconds for provider/local matching while the queue contains only the seed, so Next is unavailable during that interval.

## r42 correction

1. Every Smart-DJ queue read now reuses the already passively verified native current-position readback when one exists. Generic structural CURRENT discovery remains only the compatibility fallback before such proof exists.
2. Track Auto-DJ owns a narrow 4.2.1 queue-continuity repair for the session it created. After native refill, if Queue DAO positions are sparse, the already-proven native Queue update writer rebases them to `1..N` while preserving queue IDs/order. If CURRENT must move numerically, only the already passively verified current-position writer is allowed. The independent Cursor must then prove contiguous positions and the same current queue entry. Any ambiguity fails closed and rollback uses the same native writers.
3. The unknown 4.2.1 append allocator is **not guessed**. The rebase is a compatibility fallback to retire when the real native allocator is passively identified.
4. The explicit Track-Auto-DJ Initial-Size refill waits only 1500 ms for the smart recommendation pool. If the pool is not ready, it is left running in the background and native GMMP fills Initial Size immediately, restoring a usable Next action without throwing away the smart work already in progress.
5. A native full-list Play outside a pending Track Auto-DJ action disarms the scoped rebase so ordinary GMMP queues are never normalized globally.

## Automated gate

`QueuePositionNormalizationPolicyTest` proves sparse 5900-range positions become `1..N` without changing queue order, that CURRENT maps to the corresponding new position, that an already-contiguous queue is a no-op, and ambiguous native queue identity fails closed.

The normal branch build remains the final exact-head gate after integration.

## One bundled device pass

One Track Auto-DJ start from the same large source is sufficient. Then skip several titles quickly and open Queue once.

Expected evidence:

- `QUEUE POSITION NORMALIZE ... verified=true` after a native refill when GMMP appended with a stale absolute basis;
- sane Queue position/count and a visible current row;
- Next becomes available after the short initial smart wait rather than after a multi-second provider pipeline;
- later refills remain on the same queue session and begin producing pool hits/background top-up instead of repeatedly resetting `SMART DJ POOL`.
'''
(ROOT / r42_path).write_text(r42)

completion_path = "docs/GMMP_421_COMPLETION.md"
completion = read(completion_path)
if "## r42 queue-continuity follow-up" not in completion:
    completion += '''

## r42 queue-continuity follow-up

Maintainer device feedback on 2026-10-07 accepts the preceding navigation hot-path fix (tab swiping is smooth) and confirms the localized Track Auto-DJ action works again. The same log reopened Track Auto-DJ queue continuity: after seed isolation, 4.2.1 retained a ~5900 absolute append-position basis, Smart-DJ refill reads lost CURRENT/session provenance, and the Initial-Size smart wait could strand playback on the seed for more than ten seconds. r42 carries the bundled source/CI correction: verified-current hints for every Smart-DJ queue read, scoped native-DAO position rebasing after Track-Auto-DJ refills, and a 1500 ms non-cancelling initial smart wait. Device acceptance remains pending one bundled Track Auto-DJ/rapid-skip/Queue-view pass.
'''
write(completion_path, completion)

# Invariants for the workflow guard.
for path, needle in [
    (bridge_path, "QUEUE POSITION NORMALIZE"),
    (track_path, "normalizeManagedQueueAfterNativeRefill"),
    (module_path, "SMART DJ INITIAL FAST FALLBACK"),
    (module_path, "readQueueContext(autoDjInstance)"),
    (agents_path, marker),
    (playbook_path, "### Track Auto-DJ queue-root continuity"),
    (r42_path, "# GMMP 4.2.1 Queue compatibility — r42 queue continuity"),
]:
    require(needle in read(path), f"Missing invariant {needle} in {path}")

print("r42 patch applied")
