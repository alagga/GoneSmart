from pathlib import Path
import re


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one exact replacement, found {count}")
    p.write_text(text.replace(old, new, 1))


# Playlist Link -------------------------------------------------------------
playlist = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt")
text = playlist.read_text()
text = text.replace("import android.os.Looper\n", "import android.os.Looper\nimport android.os.SystemClock\n", 1)
text = text.replace(
    "        private const val LILAC = 0xFFA39AFF.toInt()\n",
    "        private const val LILAC = 0xFFA39AFF.toInt()\n"
    "        private const val TOOLBAR_ADD_CLICK_WINDOW_MS = 1_500L\n",
    1,
)
text = text.replace(
    "    @Volatile private var linkAnchorRef: WeakReference<View>? = null\n"
    "    private val nativeSmartChooserBypass = ThreadLocal.withInitial { false }\n",
    "    @Volatile private var linkAnchorRef: WeakReference<View>? = null\n"
    "    @Volatile private var nativeToolbarLinkClickAtMs = 0L\n"
    "    private val nativeSmartChooserBypass = ThreadLocal.withInitial { false }\n",
    1,
)
text = text.replace(
    "            activityRef = null\n            linkAnchorRef = null\n",
    "            activityRef = null\n            linkAnchorRef = null\n            nativeToolbarLinkClickAtMs = 0L\n",
    1,
)
marker = "    /**\n     * GMMP 4.2.1 dispatch boundary proven from the naturally opened native\n"
if marker not in text:
    raise SystemExit("PlaylistBridgeController: callback marker missing")
observer = '''    /**
     * Observe only the exact native menuLink View captured during menu
     * inflation. The click is passive: GMMP's listener remains untouched.
     */
    fun observeNativeToolbarLinkClick(view: View?) {
        val anchor = linkAnchorRef?.get() ?: return
        if (view !== anchor) return
        nativeToolbarLinkClickAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "BRIDGE TOOLBAR LINK | native menuLink click observed")
    }

    private fun consumeRecentNativeToolbarAdd(): Boolean {
        val clickedAt = nativeToolbarLinkClickAtMs
        nativeToolbarLinkClickAtMs = 0L
        if (clickedAt <= 0L) return false
        val age = SystemClock.elapsedRealtime() - clickedAt
        return age in 0L..TOOLBAR_ADD_CLICK_WINDOW_MS
    }

'''
text = text.replace(marker, observer + marker, 1)
old = '''        // The editor's native selected-rule state is the semantic distinction
        // between add and edit. Do not infer the old boolean argument from an
        // unrelated remapped method signature.
        val edit = selectedRule(presenter) != null
        if (!edit) {
'''
new = '''        // Toolbar Link is authoritative for Add. r28 proved the selected index
        // may still point at an existing rule during this action, so selected
        // state alone cannot classify the proven 4.2.1 callback.
        val toolbarAdd = consumeRecentNativeToolbarAdd()
        val selected = selectedRule(presenter)
        val edit = PlaylistBridgeLinkDispatchPolicy.resolveEdit(
            toolbarAddClick = toolbarAdd,
            selectedRulePresent = selected != null
        )
        if (!edit) {
'''
if old not in text:
    raise SystemExit("PlaylistBridgeController: edit classifier block missing")
text = text.replace(old, new, 1)
text = text.replace(
    '                " | edit=$edit | intercepted=$intercepted"\n',
    '                " | toolbarAdd=$toolbarAdd | edit=$edit | intercepted=$intercepted"\n',
    1,
)
playlist.write_text(text)

Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeLinkDispatchPolicy.kt").write_text('''package io.github.alagga.gonesmart

internal enum class PlaylistBridgeLinkAction {
    PASS_THROUGH,
    SHOW_TYPE_MENU,
    EDIT_BRIDGE
}

internal object PlaylistBridgeLinkDispatchPolicy {
    fun resolveEdit(
        toolbarAddClick: Boolean,
        selectedRulePresent: Boolean
    ): Boolean = !toolbarAddClick && selectedRulePresent

    fun action(
        enabled: Boolean,
        edit: Boolean,
        selectedIsBridge: Boolean
    ): PlaylistBridgeLinkAction {
        if (!enabled) return PlaylistBridgeLinkAction.PASS_THROUGH
        if (!edit) return PlaylistBridgeLinkAction.SHOW_TYPE_MENU
        return if (selectedIsBridge) {
            PlaylistBridgeLinkAction.EDIT_BRIDGE
        } else {
            PlaylistBridgeLinkAction.PASS_THROUGH
        }
    }
}
''')

test = Path("app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeLinkDispatchPolicyTest.kt")
t = test.read_text()
insert = '''    @Test
    fun exactToolbarClickWinsOverStaleSelectedRule() {
        assertEquals(
            false,
            PlaylistBridgeLinkDispatchPolicy.resolveEdit(
                toolbarAddClick = true,
                selectedRulePresent = true
            )
        )
    }

    @Test
    fun selectedRuleWithoutToolbarClickRemainsEdit() {
        assertEquals(
            true,
            PlaylistBridgeLinkDispatchPolicy.resolveEdit(
                toolbarAddClick = false,
                selectedRulePresent = true
            )
        )
    }

'''
needle = "    @Test\n    fun nativeAddLinkActionShowsTypeMenu"
if t.count(needle) != 1:
    raise SystemExit("PlaylistBridgeLinkDispatchPolicyTest: marker missing")
test.write_text(t.replace(needle, insert + needle, 1))

# Track Mix plan ------------------------------------------------------------
plan = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixPlan.kt")
p = plan.read_text()
plan_marker = '''    /**
     * A new native track menu must always put the action immediately
'''
if plan_marker not in p:
    raise SystemExit("TrackMixPlan: marker missing")
plan_block = '''    data class NativeRetargetPlan(
        val currentEntryId: Long,
        val keptUpcomingEntryIds: List<Long>,
        val rewriteTrackIds: Map<Long, Long>,
        val removeEntryIds: List<Long>,
        val expectedTrackIds: List<Long>
    )

    fun planNativeRetarget(
        entries: List<NativeQueueEntry>,
        currentPosition: Int,
        selectedTrackId: Long,
        targetQueueSize: Int,
        smartTrackIds: List<Long>
    ): NativeRetargetPlan {
        require(targetQueueSize > 0) { "Target queue size must be positive" }
        require(entries.isNotEmpty()) { "Native queue is empty" }
        require(entries.map { it.queueId }.distinct().size == entries.size) {
            "Native queue IDs are not unique"
        }
        require(entries.map { it.position }.distinct().size == entries.size) {
            "Native queue positions are not unique"
        }
        require(smartTrackIds.size <= targetQueueSize - 1) {
            "Too many Smart tracks for target queue"
        }
        val current = entries.singleOrNull { it.position == currentPosition }
            ?: error("Native current queue entry unavailable")
        require(current.trackId == selectedTrackId) {
            "The current native song changed before retarget"
        }
        val neededUpcoming = targetQueueSize - 1
        val upcoming = entries
            .filter { it.position > currentPosition }
            .sortedBy { it.position }
        require(upcoming.size >= neededUpcoming) {
            "Not enough native upcoming slots for Track Mix"
        }
        val kept = upcoming.take(neededUpcoming)
        kept.forEachIndexed { index, row ->
            require(row.position == currentPosition + index + 1) {
                "Native upcoming queue positions are not contiguous"
            }
        }
        val rewrites = linkedMapOf<Long, Long>()
        smartTrackIds.forEachIndexed { index, trackId ->
            require(trackId > 0L) { "Smart Track ID must be positive" }
            rewrites[kept[index].queueId] = trackId
        }
        val keepIds = buildSet {
            add(current.queueId)
            addAll(kept.map { it.queueId })
        }
        val expectedUpcoming = kept.mapIndexed { index, row ->
            smartTrackIds.getOrNull(index) ?: row.trackId
        }
        return NativeRetargetPlan(
            currentEntryId = current.queueId,
            keptUpcomingEntryIds = kept.map { it.queueId },
            rewriteTrackIds = rewrites,
            removeEntryIds = entries.filter { it.queueId !in keepIds }.map { it.queueId },
            expectedTrackIds = listOf(selectedTrackId) + expectedUpcoming
        )
    }

'''
plan.write_text(p.replace(plan_marker, plan_block + plan_marker, 1))

plan_test = Path("app/src/test/java/io/github/alagga/gonesmart/TrackMixPlanTest.kt")
pt = plan_test.read_text()
marker = "    @Test fun alwaysInsertsAsThirdMenuItemAfterPlayNext() {\n"
if marker not in pt:
    raise SystemExit("TrackMixPlanTest: marker missing")
tests = '''    @Test fun nativeRetargetKeepsCurrentAndContiguousFutureSlots() {
        val entries = listOf(
            TrackMixPlan.NativeQueueEntry(10, 100, 1),
            TrackMixPlan.NativeQueueEntry(11, 101, 2),
            TrackMixPlan.NativeQueueEntry(12, 102, 3),
            TrackMixPlan.NativeQueueEntry(13, 103, 4),
            TrackMixPlan.NativeQueueEntry(14, 104, 5),
            TrackMixPlan.NativeQueueEntry(15, 105, 6)
        )
        val plan = TrackMixPlan.planNativeRetarget(
            entries, 2, 101, 4, listOf(901, 902)
        )
        assertEquals(11L, plan.currentEntryId)
        assertEquals(listOf(12L, 13L, 14L), plan.keptUpcomingEntryIds)
        assertEquals(mapOf(12L to 901L, 13L to 902L), plan.rewriteTrackIds)
        assertEquals(listOf(10L, 15L), plan.removeEntryIds)
        assertEquals(listOf(101L, 901L, 902L, 104L), plan.expectedTrackIds)
    }

    @Test fun nativeRetargetRejectsMissingCurrentPlusOneGap() {
        val entries = listOf(
            TrackMixPlan.NativeQueueEntry(11, 101, 2),
            TrackMixPlan.NativeQueueEntry(12, 102, 4),
            TrackMixPlan.NativeQueueEntry(13, 103, 5)
        )
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.planNativeRetarget(entries, 2, 101, 3, listOf(901))
        }
    }

'''
plan_test.write_text(pt.replace(marker, tests + marker, 1))

# Queue mutation bridge -----------------------------------------------------
bridge = Path("app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt")
b = bridge.read_text()
b = b.replace("import android.content.Context\nimport android.content.Intent\nimport android.os.SystemClock\n", "", 1)
b = b.replace(
    '        private const val GMMP_PACKAGE = "gonemad.gmmp"\n'
    '        private const val COMMAND_CLEAR_QUEUE = "gonemad.gmmp.command.CLEAR_QUEUE"\n'
    '        private const val NATIVE_CLEAR_TIMEOUT_MS = 3_000L\n',
    '',
    1,
)
old_doc = ''' * Track Mix is deliberately different from Queue Flip: after native Play,
 * GMMP 4.2.1 may already have prepared the next AudioSource. Deleting or
 * renumbering Room rows behind that playback state can make the Queue UI and
 * the actually decoded next track disagree. Track Mix therefore clears its
 * upcoming queue through GMMP's own public CLEAR_QUEUE command and only reads
 * the database back as a postcondition. Direct Room mutation remains reserved
 * for boundaries such as Queue Flip where the complete native writer contract
 * has already been accepted.
'''
new_doc = ''' * Track Mix 4.2.1 preserves GMMP's already materialized current+1 row. The
 * accepted queue DAO update/delete writers retarget existing upcoming entities
 * in place and remove surplus rows only after current+1 has been verified.
 * CLEAR_QUEUE is intentionally not used: r28 proved it can make MusicService
 * cache a null next AudioSource before any post-clear refill runs.
'''
if old_doc not in b:
    raise SystemExit("GmmpQueueMutationBridge: class doc missing")
b = b.replace(old_doc, new_doc, 1)
pattern = r'''\n    /\*\*\n     \* Track Mix seed isolation on 4\.2\.1 must go through GMMP itself\..*?\n    fun isolateCurrentTrack\(selectedTrackId: Long\): Boolean \{.*?\n    \}\n\n    /\*\*\n     \* Track Mix no longer rewrites queue positions'''
replacement = '''
    /**
     * Retarget verified existing 4.2.1 upcoming rows without ever removing
     * current+1. O0(List) updates the native entities first; typed native DAO
     * deletion runs only after the rewritten future slots verify.
     */
    fun retargetTrackMixUpcoming(
        selectedTrackId: Long,
        smartTrackIds: List<Long>,
        targetQueueSize: Int
    ): Boolean {
        val resolved = resolve(requireDelete = true, requireStatePosition = false)
        val entries = resolved.context.items.map {
            TrackMixPlan.NativeQueueEntry(
                queueId = it.queueEntryId,
                trackId = it.track.id,
                position = it.queuePosition
            )
        }
        val plan = TrackMixPlan.planNativeRetarget(
            entries = entries,
            currentPosition = resolved.context.currentQueuePosition,
            selectedTrackId = selectedTrackId,
            targetQueueSize = targetQueueSize,
            smartTrackIds = smartTrackIds
        )
        val rowById = resolved.rows.associateBy {
            number(resolved.queueId, it).toLong()
        }
        val changed = plan.rewriteTrackIds.map { (queueId, trackId) ->
            val row = rowById[queueId]
                ?: error("Verified native Track Mix row disappeared")
            setLong(resolved.trackId, row, trackId)
            row
        }
        if (changed.isNotEmpty()) {
            resolved.update.invoke(resolved.dao, ArrayList(changed))
        }

        val interim = GmmpQueueReader().read(
            autoDj,
            verifiedPositionWriter?.read()
        ) ?: error("Track Mix retarget interim verification unavailable")
        val interimById = interim.items.associateBy { it.queueEntryId }
        check(interim.items.singleOrNull { it.state == QueueItemState.CURRENT }
            ?.track?.id == selectedTrackId) {
            "Track Mix Current changed during retarget"
        }
        plan.rewriteTrackIds.forEach { (queueId, trackId) ->
            check(interimById[queueId]?.track?.id == trackId) {
                "Track Mix native update did not retarget queue entry $queueId"
            }
        }

        val removeRows = plan.removeEntryIds.map { queueId ->
            rowById[queueId] ?: error("Verified surplus Track Mix row disappeared")
        }
        if (removeRows.isNotEmpty()) {
            val delete = resolved.delete ?: error("Verified GMMP Queue DAO delete unavailable")
            delete.invoke(
                resolved.dao,
                typedEntityArray(resolved.rows.first().javaClass, removeRows)
            )
        }

        val verified = GmmpQueueReader().read(
            autoDj,
            verifiedPositionWriter?.read()
        ) ?: error("Track Mix retarget final verification unavailable")
        val ordered = verified.items.sortedBy { it.queuePosition }
        val current = verified.items.singleOrNull { it.state == QueueItemState.CURRENT }
        check(current?.track?.id == selectedTrackId) {
            "Track Mix Current changed after retarget"
        }
        check(ordered.size == targetQueueSize) {
            "Track Mix retarget size mismatch: ${ordered.size}/$targetQueueSize"
        }
        check(ordered.map { it.track.id } == plan.expectedTrackIds) {
            "Track Mix retarget order/Track IDs failed verification"
        }
        Log.i(
            TAG,
            "TRACK MIX RETARGET | verified=true | currentEntry=" + plan.currentEntryId +
                " | smart=" + smartTrackIds.size +
                " | nativeRemainder=" + (targetQueueSize - 1 - smartTrackIds.size) +
                " | deleted=" + removeRows.size +
                " | currentPosition=" + verified.currentQueuePosition
        )
        return true
    }

    /**
     * Track Mix no longer rewrites queue positions'''
b, count = re.subn(pattern, replacement, b, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f"GmmpQueueMutationBridge: clear replacement count={count}")
b = re.sub(
    r'''\n    private fun currentApplicationContext\(\): Context\? = runCatching \{.*?\n    \}\.getOrNull\(\)\n''',
    '\n',
    b,
    count=1,
    flags=re.S,
)
set_int = "    private fun setInt(field: Field, target: Any, value: Int) {\n"
if set_int not in b:
    raise SystemExit("GmmpQueueMutationBridge: setInt marker missing")
set_long = '''    private fun setLong(field: Field, target: Any, value: Long) {
        when (field.type) {
            java.lang.Long.TYPE -> field.setLong(target, value)
            java.lang.Long::class.java -> field.set(target, value)
            Integer.TYPE, Integer::class.java -> {
                require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                if (field.type == Integer.TYPE) field.setInt(target, value.toInt())
                else field.set(target, value.toInt())
            }
            else -> error("GMMP queue Track-ID field is not numeric")
        }
    }

'''
b = b.replace(set_int, set_long + set_int, 1)
bridge.write_text(b)

# Initial-fill policy -------------------------------------------------------
Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt").write_text('''package io.github.alagga.gonesmart

internal object TrackMixInitialFillPolicy {
    enum class NativeRefillAction { NORMAL, PASS_NATIVE, SUPPRESS }

    private const val LEGACY_COMMAND_REFILL_WAIT_MS = 1_800L
    private const val GMMP_421_COMMAND_PREPARE_WAIT_MS = 800L

    fun autoDjCommandBoundaryWaitMs(legacyQueue: Boolean): Long =
        if (legacyQueue) LEGACY_COMMAND_REFILL_WAIT_MS
        else GMMP_421_COMMAND_PREPARE_WAIT_MS

    fun nativeRefillAction(
        holdActive: Boolean,
        explicitAllowance: Boolean,
        stage: String?,
        legacyQueue: Boolean
    ): NativeRefillAction {
        if (!legacyQueue && stage == "PRIME_NATIVE" && explicitAllowance) {
            return NativeRefillAction.PASS_NATIVE
        }
        if (!holdActive || explicitAllowance) return NativeRefillAction.NORMAL
        if (legacyQueue) return NativeRefillAction.SUPPRESS
        return when (stage) {
            "WAIT_PLAY" -> NativeRefillAction.PASS_NATIVE
            "PREWARM", "PRIME_NATIVE", "RETARGETING", "FILLING" ->
                NativeRefillAction.SUPPRESS
            else -> NativeRefillAction.NORMAL
        }
    }

    fun nativeInitialRefillCount(initialSize: Int, seedQueueSize: Int): Int =
        (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0)).coerceAtLeast(0)

    fun nativeSlotRefillCount(
        requiredUpcoming: Int,
        availableUpcoming: Int,
        legacyQueue: Boolean
    ): Int {
        if (legacyQueue) return 0
        return (requiredUpcoming.coerceAtLeast(0) -
            availableUpcoming.coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun shouldWaitForSmartPoolAfterSeedIsolation(
        explicitInitialRefill: Boolean
    ): Boolean = !explicitInitialRefill
}
''')

Path("app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt").write_text('''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixInitialFillPolicyTest {
    @Test fun requestsInitialSizeMinusNativeSeedExactlyOnce() {
        assertEquals(4, TrackMixInitialFillPolicy.nativeInitialRefillCount(5, 1))
    }

    @Test fun commandBoundaryRemainsBounded() {
        assertEquals(1_800L, TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(true))
        assertEquals(800L, TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(false))
    }

    @Test fun gmmp421WaitPlayPassesButRetargetWindowHolds() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE,
            TrackMixInitialFillPolicy.nativeRefillAction(true, false, "WAIT_PLAY", false)
        )
        listOf("PREWARM", "PRIME_NATIVE", "RETARGETING", "FILLING").forEach { stage ->
            assertEquals(
                TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
                TrackMixInitialFillPolicy.nativeRefillAction(true, false, stage, false)
            )
        }
    }

    @Test fun onlyOwnedSlotShortfallCrossesPrimeStage() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE,
            TrackMixInitialFillPolicy.nativeRefillAction(true, true, "PRIME_NATIVE", false)
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(true, false, "PRIME_NATIVE", false)
        )
    }

    @Test fun slotRefillRequestsOnlyRealShortfall() {
        assertEquals(0, TrackMixInitialFillPolicy.nativeSlotRefillCount(4, 4, false))
        assertEquals(2, TrackMixInitialFillPolicy.nativeSlotRefillCount(4, 2, false))
        assertEquals(0, TrackMixInitialFillPolicy.nativeSlotRefillCount(4, 2, true))
    }

    @Test fun legacySeedOnlyRefillPolicyRemainsAvailable() {
        assertFalse(TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(true))
        assertTrue(TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(false))
    }
}
''')

# TrackMixController --------------------------------------------------------
controller = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt")
c = controller.read_text()
class_marker = "/**\n * Adds a GMMP-localized \"track + Auto-DJ\" action to single-song menus.\n"
prepared_type = '''internal data class TrackMixPreparedInitialPool(
    val sessionId: Long,
    val trackIds: List<Long>
) {
    companion object {
        val EMPTY = TrackMixPreparedInitialPool(-1L, emptyList())
    }
}

'''
if class_marker not in c:
    raise SystemExit("TrackMixController: class marker missing")
c = c.replace(class_marker, prepared_type + class_marker, 1)
old_ctor = "    private val prepareSmartInitialPool: (Any, Long, Int) -> Int = { _, _, _ -> 0 }\n"
new_ctor = '''    private val prepareSmartInitialPool: (Any, Long, Int) -> TrackMixPreparedInitialPool =
        { _, _, _ -> TrackMixPreparedInitialPool.EMPTY },
    private val commitSmartInitialPool: (TrackMixPreparedInitialPool) -> Unit = {}
'''
if old_ctor not in c:
    raise SystemExit("TrackMixController: constructor callback missing")
c = c.replace(old_ctor, new_ctor, 1)
old_explicit = '''    fun isExplicitInitialRefill(): Boolean {
        val stage = pending?.stage
        return (stage == "PREFILL_SMART" || stage == "FILLING") &&
            (nativeRefillAllowance.get() ?: 0) > 0
    }
'''
new_explicit = '''    fun isExplicitInitialRefill(): Boolean =
        pending?.stage == "FILLING" &&
            (nativeRefillAllowance.get() ?: 0) > 0
'''
if old_explicit not in c:
    raise SystemExit("TrackMixController: explicit refill block missing")
c = c.replace(old_explicit, new_explicit, 1)

start = c.index("            val plannedMissing =\n")
end_marker = "            if (!isCurrent(request)) return\n            if (filled == null) {\n"
end = c.index(end_marker, start)
new_flow = '''            val requiredUpcoming = (initial - 1).coerceAtLeast(0)

            if (!legacyQueue && requiredUpcoming > 0) {
                val availableUpcoming =
                    (loaded.ids.size - loaded.currentIndex - 1).coerceAtLeast(0)
                val slotDeficit = TrackMixInitialFillPolicy.nativeSlotRefillCount(
                    requiredUpcoming,
                    availableUpcoming,
                    legacyQueue = false
                )
                if (slotDeficit > 0) {
                    request.stage = "PRIME_NATIVE"
                    if (!requestHeldNativeRefill(slotDeficit)) {
                        fail("Could not prepare Auto-DJ queue slots.", request.context)
                        return
                    }
                    val slotted = awaitQueue(request, 8_000L) { snapshot ->
                        snapshot.currentId == selectedId &&
                            (snapshot.ids.size - snapshot.currentIndex - 1) >= requiredUpcoming
                    }
                    if (slotted == null) {
                        fail("Could not prepare Auto-DJ queue slots.", request.context)
                        return
                    }
                    Log.i(
                        TAG,
                        "MIX NATIVE SLOTS | requested=$slotDeficit | " +
                            "requiredUpcoming=$requiredUpcoming | verified=true"
                    )
                }
            }

            var preparedSmart = TrackMixPreparedInitialPool.EMPTY
            if (!legacyQueue && requiredUpcoming > 0) {
                request.stage = "PREWARM"
                val autoDj = nativeAutoDj?.get()
                preparedSmart = if (autoDj != null) {
                    prepareSmartInitialPool(autoDj, selectedId, requiredUpcoming)
                } else {
                    TrackMixPreparedInitialPool.EMPTY
                }
                Log.i(
                    TAG,
                    "MIX PRE-RETARGET SMART POOL | needed=$requiredUpcoming | " +
                        "prepared=${preparedSmart.trackIds.size} | selected=$selectedId"
                )
            }

            var filled: Snapshot?
            if (!legacyQueue) {
                if (!isCurrent(request)) return
                request.stage = "RETARGETING"
                val autoDj = nativeAutoDj?.get() ?: run {
                    fail("Could not access Auto-DJ queue.", request.context)
                    return
                }
                val smartIds = preparedSmart.trackIds.take(requiredUpcoming)
                val retargeted = runCatching {
                    GmmpQueueMutationBridge(
                        autoDj = autoDj,
                        verifiedPositionWriter = nativePositionWriterProvider(autoDj)
                    ).retargetTrackMixUpcoming(
                        selectedTrackId = selectedId,
                        smartTrackIds = smartIds,
                        targetQueueSize = initial
                    )
                }.onFailure {
                    Log.e(TAG, "MIX RETARGET | native 4.2.1 queue update failed", it)
                }.getOrDefault(false)
                if (!retargeted) {
                    fail("Could not prepare the selected song for Auto-DJ.", request.context)
                    return
                }
                if (smartIds.isNotEmpty()) {
                    commitSmartInitialPool(preparedSmart.copy(trackIds = smartIds))
                }
                filled = queueSnapshot()?.takeIf {
                    it.currentId == selectedId && it.ids.size == initial
                }
                if (filled == null) {
                    fail("Could not verify the Track Auto-DJ queue.", request.context)
                    return
                }
                Log.i(
                    TAG,
                    "MIX INITIAL RETARGET | verified=true | initial=$initial | " +
                        "smart=${smartIds.size} | " +
                        "nativeRemainder=${requiredUpcoming - smartIds.size} | current=$selectedId"
                )

                armManagedAutoDjSession()
                request.stage = "FILLING"
                request.transitionalRefillSuppressed = false
                sendCommand(request.context, COMMAND_AUTO_DJ)
                nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 7_000L
                awaitAutoDjCommandRefillBoundary(request)
                Log.i(
                    TAG,
                    "MIX AUTO-DJ | command=sent-after-in-place-retarget | " +
                        "initialSize=$initial | upcoming=$upcoming | seed=$selectedId"
                )
            } else {
                request.stage = "CLEARING"
                val cleared = if (
                    loaded.ids.size == 1 && loaded.currentId == selectedId
                ) loaded else isolateNativeSeed(request, selectedId)
                if (cleared == null) {
                    fail("Could not prepare the selected song for Auto-DJ.", request.context)
                    return
                }
                Log.i(TAG, "MIX SEED | queueSize=${cleared.ids.size} | legacy=true")
                val totalInitialRequest =
                    TrackMixInitialFillPolicy.nativeInitialRefillCount(
                        initialSize = initial,
                        seedQueueSize = cleared.ids.size
                    )
                request.stage = "FILLING"
                request.transitionalRefillSuppressed = false
                sendCommand(request.context, COMMAND_AUTO_DJ)
                nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 7_000L
                awaitAutoDjCommandRefillBoundary(request)
                val accepted = requestHeldNativeRefill(totalInitialRequest)
                filled = if (!accepted) null else awaitQueue(request, 65_000L) {
                    it.currentId == selectedId && it.ids.size == initial
                }
            }

            refillHold.set(false)

'''
c = c[:start] + new_flow + c[end:]
nonlegacy_pattern = r'''        nativeAutoDj\?\.get\(\)\?\.let \{ autoDj ->\n            val queue = field\(autoDj, "q"\)\n            if \(queue\?\.javaClass\?\.name != "ex3"\) \{.*?\n            \}\n        \}\n\n'''
c, count = re.subn(nonlegacy_pattern, '', c, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f"TrackMixController: nonlegacy isolate removal count={count}")
c = c.replace(
    "     * Deterministic Queue isolation via GMMP 4.2.0 native ex3.c(uq1),\n",
    "     * Legacy-only deterministic Queue isolation via GMMP 4.2.0 ex3.c(uq1),\n",
    1,
)
controller.write_text(c)

# GoneSmartModule -----------------------------------------------------------
module = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
m = module.read_text()
m = m.replace('            "gmmp421-r28"', '            "gmmp421-r29"', 1)
ctor_old = '''        prepareSmartInitialPool = { autoDj, selectedTrackId, count ->
            prepareTrackMixSmartInitialPool(autoDj, selectedTrackId, count)
        }
    )
'''
ctor_new = '''        prepareSmartInitialPool = { autoDj, selectedTrackId, count ->
            prepareTrackMixSmartInitialPool(autoDj, selectedTrackId, count)
        },
        commitSmartInitialPool = { prepared ->
            recommendationPool.commitSelected(
                expectedSessionId = prepared.sessionId,
                selectedTrackIds = prepared.trackIds
            )
        }
    )
'''
if ctor_old not in m:
    raise SystemExit("GoneSmartModule: TrackMix constructor block missing")
m = m.replace(ctor_old, ctor_new, 1)
hook_marker = "        targets.presenterLinkSmartPlaylist?.let { method ->\n"
if hook_marker not in m:
    raise SystemExit("GoneSmartModule: bridge hook marker missing")
click_hook = '''        runCatching {
            val performClick = android.view.View::class.java
                .getDeclaredMethod("performClick")
                .apply { isAccessible = true }
            hook(performClick).intercept { chain ->
                playlistBridgeController.observeNativeToolbarLinkClick(
                    chain.getThisObject() as? android.view.View
                )
                chain.proceed()
            }
            installed++
        }.onFailure {
            playlistBridgeWarn("Smart editor menuLink click observer unavailable", it)
        }

'''
m = m.replace(hook_marker, click_hook + hook_marker, 1)
func_pattern = r'''    private fun prepareTrackMixSmartInitialPool\(\n        autoDjInstance: Any,\n        selectedTrackId: Long,\n        requestedTracks: Int\n    \): Int \{.*?\n        return prepared\n    \}\n'''
func_repl = '''    private fun prepareTrackMixSmartInitialPool(
        autoDjInstance: Any,
        selectedTrackId: Long,
        requestedTracks: Int
    ): TrackMixPreparedInitialPool {
        if (requestedTracks <= 0) return TrackMixPreparedInitialPool.EMPTY
        val liveContext = readQueueContext(autoDjInstance)
            ?: return TrackMixPreparedInitialPool.EMPTY
        val selected = liveContext.currentItem
            ?.takeIf { it.track.id == selectedTrackId }
            ?: return TrackMixPreparedInitialPool.EMPTY
        val liveSession = queueSessionTracker.observe(liveContext)
        val nativeSettings = gmmpAutoDjSettingsReader.read()
        val sizing = gmmpAutoDjSettingsReader.calculatePoolSizing(nativeSettings)
        resetRecommendationPoolForSession(liveSession, sizing)

        val isolatedContext = QueueContext(
            currentQueuePosition = selected.queuePosition,
            items = listOf(selected.copy(orderIndex = 0, state = QueueItemState.CURRENT))
        )
        val isolatedSession = QueueSessionSnapshot(
            sessionId = liveSession.sessionId,
            isNewSession = false,
            reason = "track-mix selected-seed pre-retarget",
            currentItem = selected,
            pastItems = emptyList(),
            upcomingItems = emptyList(),
            manualUpcomingItems = emptyList(),
            sessionItems = listOf(selected),
            userAnchorTracks = listOf(selected.track),
            recentGeneratedTracks = emptyList(),
            originsByEntryId = mapOf(selected.queueEntryId to QueueEntryOrigin.USER_QUEUE)
        )
        val seeds = listOf(
            RecommendationSeed(
                track = selected.track,
                type = SeedType.CURRENT,
                recency = 0,
                weightMultiplier = 1.0
            )
        )
        Log.i(
            TAG,
            "SMART DJ TRACK MIX PRE-RETARGET | start | session=${liveSession.sessionId} | " +
                "selected=$selectedTrackId | requested=$requestedTracks"
        )
        val future = startPoolFill(
            seeds = seeds,
            autoDjInstance = autoDjInstance,
            queueContext = isolatedContext,
            session = isolatedSession,
            sizing = sizing,
            background = false
        )
        try {
            future.get(TRACK_MIX_SMART_REMAINDER_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            Log.w(TAG, "SMART DJ TRACK MIX PRE-RETARGET | bounded wait timed out")
        } catch (failure: Throwable) {
            Log.w(TAG, "SMART DJ TRACK MIX PRE-RETARGET | pool preparation failed", failure)
        }
        val preparedIds = recommendationPool.peek(
            expectedSessionId = liveSession.sessionId,
            count = requestedTracks,
            excludedTrackIds = setOf(selectedTrackId)
        )
        Log.i(
            TAG,
            "SMART DJ TRACK MIX PRE-RETARGET | complete | requested=$requestedTracks | " +
                "prepared=${preparedIds.size} | " + recommendationPool.describe(liveSession.sessionId)
        )
        return TrackMixPreparedInitialPool(liveSession.sessionId, preparedIds)
    }
'''
m, count = re.subn(func_pattern, func_repl, m, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f"GoneSmartModule: prepare function replacement count={count}")
module.write_text(m)

# AGENTS standing rule ------------------------------------------------------
agents = Path("AGENTS.md")
a = agents.read_text()
start_text = "- Isolate the exact native Queue entity through proven GMMP writers and verify that the seed remains Current.\n"
end_text = "- Track-Auto-DJ-owned normalized queues use the accepted event-driven continuation repair: on a verified natural CURRENT transition, read remaining rows once and invoke `qr.z(deficit)` only when GMMP's configured upcoming count is not satisfied. No polling and no guessed append allocator.\n"
s = a.index(start_text)
e = a.index(end_text, s) + len(end_text)
rules = '''- Legacy 4.2.0 may isolate the seed through its accepted native `ex3` transaction. **GMMP 4.2.1 Track Auto-DJ must not use `CLEAR_QUEUE` for startup.** r28 device evidence proved the command itself removes `current+1` early enough for MusicService to cache a null next AudioSource before any post-clear refill can run.
- On GMMP 4.2.1, keep native Play/`WAIT_PLAY` continuity rows alive while the selected-current recommendation pool is prepared. If fewer than `Initial Size - 1` future native rows exist, request only that slot shortfall before provider work.
- Build the 4.2.1 Initial Size by retargeting already materialized contiguous upcoming native entities through verified Queue DAO `O0(List)`. Preserve queue IDs and positions, verify rewritten `current+1` while all old rows still exist, then delete surplus rows through the typed native DAO delete. Never expose a one-row gap.
- Prepared GoneSmart Track IDs occupy the first upcoming slots; existing native Track IDs remain only for a genuine prepared-pool shortfall. Do not perform a second Initial-Size refill after recommendations are ready.
- Send native `AUTO_DJ` only after the in-place retarget is independently verified. Hold its delayed natural refill through the bounded command window, then resume event-driven continuation refill on verified natural CURRENT transitions.
- A same-position `dx3.c2(int)` refresh is not a playback repair; r27 proved it can verify while Track 2 still has no decoder.
'''
agents.write_text(a[:s] + rules + a[e:])
