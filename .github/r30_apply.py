from pathlib import Path
import re


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one exact replacement, found {count}")
    p.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Semantic pre-Play target resolver. No obfuscated field names and no getters.
# ---------------------------------------------------------------------------
Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixPrePlayTargetResolver.kt").write_text(r'''package io.github.alagga.gonesmart

import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

internal data class TrackMixPrePlayTargetResolution(
    val track: TrackInfo,
    val score: Int,
    val depth: Int,
    val evidence: String,
    val inspectedObjects: Int
)

/**
 * Resolves the row represented by GMMP's context-menu callback graph without
 * depending on R8 field names. Only direct scalar evidence from each bounded
 * object is scored. Large framework graphs are not traversed.
 */
internal class TrackMixPrePlayTargetResolver(
    private val maxDepth: Int = 4,
    private val maxObjects: Int = 160,
    private val maxCollectionItems: Int = 24
) {
    private data class Node(val value: Any, val depth: Int)
    private data class Candidate(
        val track: TrackInfo,
        val score: Int,
        val depth: Int,
        val evidence: String
    )

    fun resolve(
        roots: List<Any?>,
        library: List<TrackInfo>
    ): TrackMixPrePlayTargetResolution? {
        if (roots.isEmpty() || library.isEmpty()) return null
        val queue = ArrayDeque<Node>()
        roots.filterNotNull().forEach { queue.add(Node(it, 0)) }
        val visited = IdentityHashMap<Any, Boolean>()
        val bestByTrack = linkedMapOf<Long, Candidate>()
        var inspected = 0

        while (queue.isNotEmpty() && inspected < maxObjects) {
            val node = queue.removeFirst()
            if (visited.put(node.value, true) != null) continue
            inspected++

            val scalars = directScalars(node.value)
            if (scalars.strings.isNotEmpty() || scalars.numbers.isNotEmpty()) {
                library.forEach { track ->
                    score(track, scalars, node.depth)?.let { candidate ->
                        val previous = bestByTrack[track.id]
                        if (previous == null || candidate.score > previous.score ||
                            (candidate.score == previous.score && candidate.depth < previous.depth)
                        ) {
                            bestByTrack[track.id] = candidate
                        }
                    }
                }
            }

            if (node.depth >= maxDepth) continue
            directChildren(node.value).take(maxCollectionItems).forEach { child ->
                if (visited[child] == null && shouldTraverse(child)) {
                    queue.add(Node(child, node.depth + 1))
                }
            }
        }

        val ranked = bestByTrack.values.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.depth }
        )
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (best.score < 80) return null
        if (second != null && second.score == best.score && second.track.id != best.track.id) {
            return null
        }
        return TrackMixPrePlayTargetResolution(
            track = best.track,
            score = best.score,
            depth = best.depth,
            evidence = best.evidence,
            inspectedObjects = inspected
        )
    }

    private data class Scalars(
        val strings: Set<String>,
        val numbers: Set<Long>
    )

    private fun directScalars(value: Any): Scalars {
        val strings = linkedSetOf<String>()
        val numbers = linkedSetOf<Long>()
        fields(value.javaClass).forEach { field ->
            val child = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull() ?: return@forEach
            when (child) {
                is CharSequence -> normalize(child.toString())
                    .takeIf { it.isNotEmpty() }?.let(strings::add)
                is Byte -> numbers += child.toLong()
                is Short -> numbers += child.toLong()
                is Int -> numbers += child.toLong()
                is Long -> numbers += child
            }
        }
        return Scalars(strings, numbers)
    }

    private fun directChildren(value: Any): Sequence<Any> = sequence {
        when (value) {
            is Iterable<*> -> {
                value.take(maxCollectionItems).filterNotNull().forEach { yield(it) }
                return@sequence
            }
            is Map<*, *> -> {
                value.entries.take(maxCollectionItems).forEach { entry ->
                    entry.key?.let { yield(it) }
                    entry.value?.let { yield(it) }
                }
                return@sequence
            }
        }
        if (value.javaClass.isArray) {
            val length = java.lang.reflect.Array.getLength(value).coerceAtMost(maxCollectionItems)
            repeat(length) { index ->
                java.lang.reflect.Array.get(value, index)?.let { yield(it) }
            }
            return@sequence
        }
        fields(value.javaClass).forEach { field ->
            val child = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull() ?: return@forEach
            if (!isScalar(child)) yield(child)
        }
    }

    private fun score(track: TrackInfo, scalars: Scalars, depth: Int): Candidate? {
        val path = normalize(track.path)
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        val albumArtist = normalize(track.albumArtist)
        val filename = path.substringAfterLast('/').takeIf { '/' in path }.orEmpty()

        val pathMatch = path.isNotEmpty() && path in scalars.strings
        val idMatch = track.id > 0L && track.id in scalars.numbers
        val titleMatch = title.isNotEmpty() && title in scalars.strings
        val artistMatch = (artist.isNotEmpty() && artist in scalars.strings) ||
            (albumArtist.isNotEmpty() && albumArtist in scalars.strings)
        val filenameMatch = filename.isNotEmpty() && filename in scalars.strings

        val baseScore = when {
            pathMatch -> 120
            idMatch && (titleMatch || artistMatch || filenameMatch) -> 105
            titleMatch && artistMatch -> 92
            else -> return null
        }
        val bonus = (if (idMatch) 5 else 0) +
            (if (titleMatch) 3 else 0) +
            (if (artistMatch) 3 else 0) +
            (if (filenameMatch) 2 else 0)
        val evidence = buildList {
            if (pathMatch) add("path")
            if (idMatch) add("id")
            if (titleMatch) add("title")
            if (artistMatch) add("artist")
            if (filenameMatch) add("filename")
        }.joinToString("+")
        return Candidate(
            track = track,
            score = baseScore + bonus - depth * 6,
            depth = depth,
            evidence = evidence
        )
    }

    private fun fields(type: Class<*>): List<java.lang.reflect.Field> {
        val result = arrayListOf<java.lang.reflect.Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach(result::add)
            current = current.superclass
        }
        return result
    }

    private fun shouldTraverse(value: Any): Boolean {
        if (isScalar(value)) return false
        val name = value.javaClass.name
        if (value is Iterable<*> || value is Map<*, *> || value.javaClass.isArray) return true
        return !(
            name.startsWith("android.") ||
                name.startsWith("androidx.") ||
                name.startsWith("com.google.android.") ||
                name.startsWith("java.lang.") ||
                name.startsWith("java.util.concurrent.") ||
                name.startsWith("kotlin.")
        )
    }

    private fun isScalar(value: Any): Boolean =
        value is CharSequence || value is Number || value is Boolean ||
            value is Char || value.javaClass.isEnum

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase().orEmpty()
}
''')

Path("app/src/test/java/io/github/alagga/gonesmart/TrackMixPrePlayTargetResolverTest.kt").write_text(r'''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixPrePlayTargetResolverTest {
    private data class NativeTrack(
        val a: Long,
        val b: String,
        val c: String,
        val d: String
    )
    private data class Listener(val selected: Any, val outer: Any? = null)
    private data class Outer(val rows: List<Any>)
    private class Cycle(var next: Any? = null, val selected: Any? = null)

    private val levels = TrackInfo(
        3737L,
        "Levels",
        "Avicii",
        "Avicii",
        "/music/Avicii/Levels.mp3"
    )
    private val fade = TrackInfo(
        3897L,
        "Fade Into Darkness",
        "Avicii",
        "Avicii",
        "/music/Avicii/Fade Into Darkness.mp3"
    )

    @Test fun resolvesCapturedSelectedModelAheadOfDeeperAdapterRows() {
        val selected = NativeTrack(3737L, "Levels", "Avicii", levels.path!!)
        val outer = Outer(listOf(NativeTrack(3897L, "Fade Into Darkness", "Avicii", fade.path!!)))
        val result = TrackMixPrePlayTargetResolver().resolve(
            listOf(Listener(selected, outer)),
            listOf(levels, fade)
        )
        assertEquals(3737L, result?.track?.id)
        assertTrue(result?.evidence?.contains("path") == true)
    }

    @Test fun bareNumericCoincidenceNeverResolves() {
        data class Bare(val value: Long)
        assertNull(
            TrackMixPrePlayTargetResolver().resolve(
                listOf(Bare(3737L)),
                listOf(levels, fade)
            )
        )
    }

    @Test fun duplicateTitleWithoutArtistRemainsAmbiguous() {
        data class TitleOnly(val value: String)
        val other = levels.copy(id = 999L, artist = "Other")
        assertNull(
            TrackMixPrePlayTargetResolver().resolve(
                listOf(TitleOnly("Levels")),
                listOf(levels, other)
            )
        )
    }

    @Test fun cyclicObjectGraphIsBoundedAndStillFindsStrongTarget() {
        val first = Cycle()
        val second = Cycle(selected = NativeTrack(3737L, "Levels", "Avicii", levels.path!!))
        first.next = second
        second.next = first
        val result = TrackMixPrePlayTargetResolver(maxObjects = 12).resolve(
            listOf(first),
            listOf(levels, fade)
        )
        assertEquals(3737L, result?.track?.id)
        assertTrue((result?.inspectedObjects ?: 99) <= 12)
    }
}
''')

# ---------------------------------------------------------------------------
# TrackMixController: prepare target and immutable recommendation IDs before
# native Play; offer them only to the first verified target refill.
# ---------------------------------------------------------------------------
controller = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt")
c = controller.read_text()
old_ctor = '''    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },
    private val prepareSmartInitialPool: (Any, Long, Int) -> TrackMixPreparedInitialPool =
        { _, _, _ -> TrackMixPreparedInitialPool.EMPTY },
    private val commitSmartInitialPool: (TrackMixPreparedInitialPool) -> Unit = {}
) {
'''
new_ctor = '''    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },
    private val resolvePrePlayTarget: (Any, List<Any>) -> TrackMixPrePlayTargetResolution? =
        { _, _ -> null },
    private val preparePrePlaySmartPool: (Any, TrackInfo, Int) -> TrackMixPreparedInitialPool =
        { _, _, _ -> TrackMixPreparedInitialPool.EMPTY }
) {
'''
if old_ctor not in c:
    raise SystemExit("TrackMixController constructor block missing")
c = c.replace(old_ctor, new_ctor, 1)

pending_old = '''        @Volatile var transitionalRefillSuppressed = false
        val refillRequestedTracks = AtomicInteger(0)
        @Volatile var stage = "WAIT_PLAY"
        @Volatile var nativeSource = ""
    }
'''
pending_new = '''        @Volatile var transitionalRefillSuppressed = false
        val refillRequestedTracks = AtomicInteger(0)
        @Volatile var stage = "WAIT_PLAY"
        @Volatile var nativeSource = ""
        @Volatile var prePlayTarget: TrackInfo? = null
        @Volatile var prePlayPrepared: TrackMixPreparedInitialPool = TrackMixPreparedInitialPool.EMPTY
        @Volatile var bootstrapAppliedTrackIds: List<Long> = emptyList()
        @Volatile var bootstrapRefillObserved = false
    }
'''
if pending_old not in c:
    raise SystemExit("TrackMixController Pending block missing")
c = c.replace(pending_old, pending_new, 1)

capture_marker = '''    fun onNativePlaybackQueueUpdated(origin: String) {
'''
bootstrap_methods = '''    /**
     * Called from the proven native qr.z refill hook before GMMP materializes
     * its Auto-DJ selection rows. Only a refill whose native Current already
     * matches the pre-Play-resolved target may consume bootstrap Track IDs.
     */
    fun bootstrapSelectionForNativeRefill(
        autoDj: Any,
        requested: Int
    ): TrackMixPreparedInitialPool? {
        if (requested <= 0) return null
        val request = pending ?: return null
        if (request.stage != "WAIT_PLAY") return null
        val target = request.prePlayTarget ?: return null
        val prepared = request.prePlayPrepared
        if (prepared.trackIds.isEmpty()) return null

        val currentId = runCatching {
            queueReader.read(
                autoDj,
                nativePositionWriterProvider(autoDj)?.read()
            )?.currentItem?.track?.id
        }.getOrNull()
        if (currentId != target.id) {
            Log.i(
                TAG,
                "MIX PREPLAY BOOTSTRAP SKIP | current=$currentId | target=${target.id} | " +
                    "requested=$requested"
            )
            return null
        }

        val alreadyApplied = request.bootstrapAppliedTrackIds.size
        val ids = prepared.trackIds.drop(alreadyApplied).take(requested)
        if (ids.isEmpty()) return null
        Log.i(
            TAG,
            "MIX PREPLAY BOOTSTRAP OFFER | target=${target.id} | requested=$requested | " +
                "smart=${ids.size} | alreadyApplied=$alreadyApplied"
        )
        return TrackMixPreparedInitialPool(prepared.sessionId, ids)
    }

    fun onBootstrapNativeRefillFinished(appliedTrackIds: List<Long>) {
        val request = pending ?: return
        if (request.stage != "WAIT_PLAY") return
        request.bootstrapRefillObserved = true
        if (appliedTrackIds.isNotEmpty()) {
            request.bootstrapAppliedTrackIds =
                (request.bootstrapAppliedTrackIds + appliedTrackIds).distinct()
        }
        Log.i(
            TAG,
            "MIX PREPLAY BOOTSTRAP RESULT | applied=${appliedTrackIds.size} | " +
                "totalSmart=${request.bootstrapAppliedTrackIds.size}"
        )
    }

'''
if capture_marker not in c:
    raise SystemExit("TrackMixController capture marker missing")
c = c.replace(capture_marker, bootstrap_methods + capture_marker, 1)

# Keep the native listener roots instead of hiding them inside only the dispatch lambda.
old_dispatch = '''        val nativePlayDispatch: () -> Boolean = runCatching {
            val callback = field(menu, "mCallback")
            val popup = callback?.let { field(it, "this$0") }
            val listener = popup?.let {
                field(it, "mMenuItemClickListener")
                    ?: field(it, "mOnMenuItemClickListener")
            }
            val click = listener?.javaClass?.methods?.firstOrNull {
                it.name == "onMenuItemClick" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(nativePlay.javaClass)
            }
            if (click != null && listener != null) {
                click.isAccessible = true
                ({ click.invoke(listener, nativePlay) as? Boolean == true })
            } else {
                ({ menu.performIdentifierAction(nativePlay.itemId, 0) })
            }
        }.getOrElse { error ->
'''
new_dispatch = '''        val callback = runCatching { field(menu, "mCallback") }.getOrNull()
        val popup = callback?.let { runCatching { field(it, "this$0") }.getOrNull() }
        val listener = popup?.let {
            runCatching {
                field(it, "mMenuItemClickListener")
                    ?: field(it, "mOnMenuItemClickListener")
            }.getOrNull()
        }
        val prePlayRoots = listOfNotNull(listener, popup, callback)
            .distinctBy { System.identityHashCode(it) }

        val nativePlayDispatch: () -> Boolean = runCatching {
            val click = listener?.javaClass?.methods?.firstOrNull {
                it.name == "onMenuItemClick" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(nativePlay.javaClass)
            }
            if (click != null && listener != null) {
                click.isAccessible = true
                ({ click.invoke(listener, nativePlay) as? Boolean == true })
            } else {
                ({ menu.performIdentifierAction(nativePlay.itemId, 0) })
            }
        }.getOrElse { error ->
'''
if old_dispatch not in c:
    raise SystemExit("TrackMixController native dispatch block missing")
c = c.replace(old_dispatch, new_dispatch, 1)

old_worker = '''        work.execute {
            val old = queueSnapshot()
            main.post {
                if (!isCurrent(request)) return@post
                request.before = old
                request.stage = "WAIT_PLAY"

                refillHold.set(true)
                nativeToastSuppressionUntilMs =
                    SystemClock.elapsedRealtime() + 8_000L
                suppressedToastCount.set(0)

                if (!enableSmartDj(context)) {
'''
new_worker = '''        work.execute {
            val old = queueSnapshot()
            val autoDj = nativeAutoDj?.get()
            val legacyQueue = autoDj?.let {
                field(it, "q")?.javaClass?.name == "ex3"
            } == true || nativeQueue?.get()?.javaClass?.name == "ex3"

            if (!legacyQueue) {
                if (autoDj == null) {
                    if (pending === request) pending = null
                    main.post { fail("Could not access Auto-DJ for this song.", context) }
                    return@execute
                }
                request.stage = "PREPLAY_RESOLVE"
                val resolution = resolvePrePlayTarget(autoDj, prePlayRoots)
                if (resolution == null) {
                    Log.w(
                        TAG,
                        "MIX PREPLAY TARGET | resolved=false | roots=${prePlayRoots.size}"
                    )
                    if (pending === request) pending = null
                    main.post { fail("Could not identify this song safely for Track Auto-DJ.", context) }
                    return@execute
                }
                request.prePlayTarget = resolution.track
                Log.i(
                    TAG,
                    "MIX PREPLAY TARGET | resolved=true | trackId=${resolution.track.id} | " +
                        "score=${resolution.score} | depth=${resolution.depth} | " +
                        "evidence=${resolution.evidence} | inspected=${resolution.inspectedObjects}"
                )

                val initial = settings.read().initialQueueSize.coerceAtLeast(1)
                val requiredUpcoming = (initial - 1).coerceAtLeast(0)
                request.stage = "PREPLAY_PREPARE"
                request.prePlayPrepared = preparePrePlaySmartPool(
                    autoDj,
                    resolution.track,
                    requiredUpcoming
                )
                Log.i(
                    TAG,
                    "MIX PREPLAY SMART POOL | target=${resolution.track.id} | " +
                        "requested=$requiredUpcoming | prepared=${request.prePlayPrepared.trackIds.size}"
                )
            }

            main.post {
                if (!isCurrent(request)) return@post
                request.before = old
                request.stage = "WAIT_PLAY"

                refillHold.set(true)
                nativeToastSuppressionUntilMs =
                    SystemClock.elapsedRealtime() + 8_000L
                suppressedToastCount.set(0)

                if (!enableSmartDj(context)) {
'''
if old_worker not in c:
    raise SystemExit("TrackMixController worker block missing")
c = c.replace(old_worker, new_worker, 1)

# Replace the r29 post-Play prepare/retarget section with verify/prune only.
start = c.index("            val requiredUpcoming = (initial - 1).coerceAtLeast(0)\n", c.index("    private fun runTrackMix"))
end_marker = "            refillHold.set(false)\n\n            if (!isCurrent(request)) return\n"
end = c.index(end_marker, start)
new_flow = '''            val requiredUpcoming = (initial - 1).coerceAtLeast(0)

            var filled: Snapshot?
            if (!legacyQueue) {
                val prePlayTarget = request.prePlayTarget
                if (prePlayTarget == null || prePlayTarget.id != selectedId) {
                    Log.e(
                        TAG,
                        "MIX PREPLAY VERIFY | selected=$selectedId | " +
                            "resolved=${prePlayTarget?.id} | mismatch=true"
                    )
                    fail("The selected song changed before Track Auto-DJ could start.", request.context)
                    return
                }

                var currentSnapshot = loaded
                var availableUpcoming =
                    (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
                if (availableUpcoming < requiredUpcoming) {
                    val deficit = requiredUpcoming - availableUpcoming
                    // This refill still runs at WAIT_PLAY, so the bootstrap
                    // selection window can supply any remaining pre-Play IDs.
                    if (!requestHeldNativeRefill(deficit)) {
                        fail("Could not prepare Auto-DJ queue slots.", request.context)
                        return
                    }
                    currentSnapshot = awaitQueue(request, 8_000L) { snapshot ->
                        snapshot.currentId == selectedId &&
                            (snapshot.ids.size - snapshot.currentIndex - 1) >= requiredUpcoming
                    } ?: run {
                        fail("Could not prepare Auto-DJ queue slots.", request.context)
                        return
                    }
                    availableUpcoming =
                        (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
                }

                val expectedSmart = request.prePlayPrepared.trackIds
                    .take(request.bootstrapAppliedTrackIds.size)
                if (request.prePlayPrepared.trackIds.isNotEmpty() && expectedSmart.isEmpty()) {
                    Log.e(
                        TAG,
                        "MIX PREPLAY VERIFY | bootstrap selection never reached native materialization"
                    )
                    fail("Could not apply the prepared Track Auto-DJ selection safely.", request.context)
                    return
                }
                expectedSmart.forEachIndexed { index, trackId ->
                    val actual = currentSnapshot.ids.getOrNull(currentSnapshot.currentIndex + 1 + index)
                    if (actual != trackId) {
                        Log.e(
                            TAG,
                            "MIX PREPLAY VERIFY | slot=${index + 1} | expected=$trackId | actual=$actual"
                        )
                        fail("Could not verify the prepared Track Auto-DJ selection.", request.context)
                        return
                    }
                }

                request.stage = "PRUNE"
                val autoDj = nativeAutoDj?.get() ?: run {
                    fail("Could not access Auto-DJ queue.", request.context)
                    return
                }
                val pruned = runCatching {
                    GmmpQueueMutationBridge(
                        autoDj = autoDj,
                        verifiedPositionWriter = nativePositionWriterProvider(autoDj)
                    ).retargetTrackMixUpcoming(
                        selectedTrackId = selectedId,
                        smartTrackIds = emptyList(),
                        targetQueueSize = initial
                    )
                }.onFailure {
                    Log.e(TAG, "MIX PREPLAY PRUNE | native 4.2.1 queue prune failed", it)
                }.getOrDefault(false)
                if (!pruned) {
                    fail("Could not finalize the Track Auto-DJ queue.", request.context)
                    return
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
                    "MIX PREPLAY QUEUE | verified=true | initial=$initial | " +
                        "smart=${request.bootstrapAppliedTrackIds.size} | " +
                        "nativeRemainder=${requiredUpcoming - request.bootstrapAppliedTrackIds.size} | " +
                        "current=$selectedId | upcoming=$availableUpcoming"
                )

                armManagedAutoDjSession()
                request.stage = "FILLING"
                request.transitionalRefillSuppressed = false
                sendCommand(request.context, COMMAND_AUTO_DJ)
                nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 7_000L
                awaitAutoDjCommandRefillBoundary(request)
                Log.i(
                    TAG,
                    "MIX AUTO-DJ | command=sent-after-preplay-bootstrap | " +
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

'''
c = c[:start] + new_flow + c[end:]
controller.write_text(c)

# ---------------------------------------------------------------------------
# GoneSmartModule: cached library semantic resolver + synthetic pre-Play pool,
# special ThreadLocal selection window around only the verified target refill.
# ---------------------------------------------------------------------------
module = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
m = module.read_text()
m = m.replace('            "gmmp421-r29"', '            "gmmp421-r30"', 1)

ctor_old = '''        nativePositionWriterProvider = { autoDj ->
            queueFlipController.verifiedPositionWriter(autoDj)
        },
        prepareSmartInitialPool = { autoDj, selectedTrackId, count ->
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
ctor_new = '''        nativePositionWriterProvider = { autoDj ->
            queueFlipController.verifiedPositionWriter(autoDj)
        },
        resolvePrePlayTarget = { autoDj, roots ->
            resolveTrackMixPrePlayTarget(autoDj, roots)
        },
        preparePrePlaySmartPool = { autoDj, track, count ->
            prepareTrackMixPrePlaySmartPool(autoDj, track, count)
        }
    )
'''
if ctor_old not in m:
    raise SystemExit("GoneSmartModule TrackMix constructor block missing")
m = m.replace(ctor_old, ctor_new, 1)

resolver_field_marker = '''    @Volatile
    private var trackMixAutoDj: WeakReference<Any>? = null

'''
resolver_fields = '''    private val trackMixPrePlayTargetResolver = TrackMixPrePlayTargetResolver()
    private val trackMixBootstrapSessionIds = AtomicLong(-1_000_000L)

    private data class TrackMixBootstrapSelectionWindow(
        val sessionId: Long,
        val trackIds: List<Long>,
        val appliedTrackIds: MutableList<Long> = arrayListOf()
    )

    private val trackMixBootstrapSelectionWindow =
        ThreadLocal<TrackMixBootstrapSelectionWindow?>()

'''
if resolver_field_marker not in m:
    raise SystemExit("GoneSmartModule trackMixAutoDj marker missing")
m = m.replace(resolver_field_marker, resolver_field_marker + resolver_fields, 1)

# Insert the special bootstrap branch before the existing TrackMix refill policy switch.
refill_marker = '''                when (trackMixController.nativeRefillAction()) {
'''
bootstrap_refill = '''                val trackMixBootstrap =
                    trackMixController.bootstrapSelectionForNativeRefill(
                        autoDj = autoDjInstance,
                        requested = requestedTracks
                    )
                if (trackMixBootstrap != null) {
                    val beforeBootstrap = readQueueContext(autoDjInstance)
                    beforeBootstrap?.let { queueSessionTracker.observe(it) }
                    val bootstrapWindow = TrackMixBootstrapSelectionWindow(
                        sessionId = trackMixBootstrap.sessionId,
                        trackIds = trackMixBootstrap.trackIds
                    )
                    trackMixBootstrapSelectionWindow.set(bootstrapWindow)
                    val bootstrapResult = try {
                        chain.proceed()
                    } finally {
                        trackMixBootstrapSelectionWindow.remove()
                    }
                    val applied = bootstrapWindow.appliedTrackIds.toList()
                    trackMixController.onBootstrapNativeRefillFinished(applied)
                    val afterBootstrap = readQueueContext(autoDjInstance)
                    if (beforeBootstrap != null && afterBootstrap != null) {
                        queueSessionTracker.commitGeneratedAdditions(
                            beforeContext = beforeBootstrap,
                            afterContext = afterBootstrap,
                            origin = if (applied.isNotEmpty()) {
                                QueueEntryOrigin.GONESMART
                            } else {
                                QueueEntryOrigin.NATIVE_AUTO_DJ
                            }
                        )
                    }
                    Log.i(
                        "GoneSmartTrackMix",
                        "MIX PREPLAY NATIVE REFILL | requested=$requestedTracks | " +
                            "smartApplied=${applied.size} | nativeRemainder=" +
                            (requestedTracks - applied.size).coerceAtLeast(0)
                    )
                    return@intercept bootstrapResult
                }

'''
if m.count(refill_marker) < 1:
    raise SystemExit("GoneSmartModule refill switch marker missing")
m = m.replace(refill_marker, bootstrap_refill + refill_marker, 1)

# Selection hook: apply immutable bootstrap IDs before normal Smart-DJ session logic.
old_selection_prefix = '''            val nativeResult =
                chain.proceed()

            val selectionContext =
                autoDjSelectionWindow.get()

            if (
                !ENABLE_SMART_SELECTION_TEST ||
                selectionContext == null
            ) {

                return@intercept nativeResult
            }

            val rows =
                nativeResult as? List<*>

            if (
                rows.isNullOrEmpty()
            ) {
'''
new_selection_prefix = '''            val nativeResult =
                chain.proceed()

            val bootstrapWindow = trackMixBootstrapSelectionWindow.get()
            if (bootstrapWindow != null) {
                val bootstrapRows = nativeResult as? List<*>
                if (bootstrapRows.isNullOrEmpty()) {
                    Log.w(
                        "GoneSmartTrackMix",
                        "SMART DJ TRACK MIX BOOTSTRAP | native selection returned no rows"
                    )
                    return@intercept nativeResult
                }
                val desired = if (requestedTracks > 0) {
                    minOf(requestedTracks, bootstrapRows.size)
                } else {
                    bootstrapRows.size
                }
                val ids = bootstrapWindow.trackIds.take(desired)
                var applied = 0
                ids.forEachIndexed { index, trackId ->
                    val row = bootstrapRows[index] ?: return@forEachIndexed
                    runCatching {
                        setAutoDjTrackId(row, trackId)
                    }.onSuccess {
                        bootstrapWindow.appliedTrackIds += trackId
                        applied++
                    }.onFailure { error ->
                        Log.e(
                            "GoneSmartTrackMix",
                            "SMART DJ TRACK MIX BOOTSTRAP | slot=${index + 1} failed",
                            error
                        )
                    }
                }
                if (applied > 0) {
                    recommendationPool.commitSelected(
                        expectedSessionId = bootstrapWindow.sessionId,
                        selectedTrackIds = bootstrapWindow.appliedTrackIds
                    )
                }
                Log.i(
                    "GoneSmartTrackMix",
                    "SMART DJ TRACK MIX BOOTSTRAP | applied=$applied/$desired | " +
                        "nativeRemainder=${(desired - applied).coerceAtLeast(0)}"
                )
                return@intercept nativeResult
            }

            val selectionContext =
                autoDjSelectionWindow.get()

            if (
                !ENABLE_SMART_SELECTION_TEST ||
                selectionContext == null
            ) {

                return@intercept nativeResult
            }

            val rows =
                nativeResult as? List<*>

            if (
                rows.isNullOrEmpty()
            ) {
'''
if old_selection_prefix not in m:
    raise SystemExit("GoneSmartModule selection prefix missing")
m = m.replace(old_selection_prefix, new_selection_prefix, 1)

# Replace post-Play preparation method with pre-Play resolver/preparation helpers.
func_pattern = r'''    private fun prepareTrackMixSmartInitialPool\(\n        autoDjInstance: Any,\n        selectedTrackId: Long,\n        requestedTracks: Int\n    \): TrackMixPreparedInitialPool \{.*?\n    \}\n\n    private fun ensureRecommendationPoolReady'''
func_repl = '''    private fun resolveTrackMixPrePlayTarget(
        autoDjInstance: Any,
        roots: List<Any>
    ): TrackMixPrePlayTargetResolution? {
        val library = gmmpLibraryReader.read(autoDjInstance)
            .map { it.track }
        return trackMixPrePlayTargetResolver.resolve(roots, library)
    }

    private fun prepareTrackMixPrePlaySmartPool(
        autoDjInstance: Any,
        selectedTrack: TrackInfo,
        requestedTracks: Int
    ): TrackMixPreparedInitialPool {
        if (requestedTracks <= 0) return TrackMixPreparedInitialPool.EMPTY
        val sessionId = trackMixBootstrapSessionIds.decrementAndGet()
        val syntheticEntryId = sessionId
        val selected = QueueItemInfo(
            orderIndex = 0,
            queuePosition = 1,
            shufflePosition = 1,
            queueEntryId = syntheticEntryId,
            track = selectedTrack,
            state = QueueItemState.CURRENT
        )
        val queueContext = QueueContext(
            currentQueuePosition = 1,
            items = listOf(selected)
        )
        val session = QueueSessionSnapshot(
            sessionId = sessionId,
            isNewSession = false,
            reason = "track-mix pre-play bootstrap",
            currentItem = selected,
            pastItems = emptyList(),
            upcomingItems = emptyList(),
            manualUpcomingItems = emptyList(),
            sessionItems = listOf(selected),
            userAnchorTracks = listOf(selectedTrack),
            recentGeneratedTracks = emptyList(),
            originsByEntryId = mapOf(syntheticEntryId to QueueEntryOrigin.USER_QUEUE)
        )
        val settings = gmmpAutoDjSettingsReader.read()
        val sizing = gmmpAutoDjSettingsReader.calculatePoolSizing(settings)
        resetRecommendationPoolForSession(session, sizing)
        val seeds = listOf(
            RecommendationSeed(
                track = selectedTrack,
                type = SeedType.CURRENT,
                recency = 0,
                weightMultiplier = 1.0
            )
        )
        Log.i(
            TAG,
            "SMART DJ TRACK MIX PREPLAY | start | session=$sessionId | " +
                "selected=${selectedTrack.id} | requested=$requestedTracks"
        )
        val future = startPoolFill(
            seeds = seeds,
            autoDjInstance = autoDjInstance,
            queueContext = queueContext,
            session = session,
            sizing = sizing,
            background = false
        )
        try {
            future.get(TRACK_MIX_SMART_REMAINDER_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            Log.w(
                TAG,
                "SMART DJ TRACK MIX PREPLAY | bounded wait timed out | " +
                    "poolFill=continuing"
            )
        } catch (failure: Throwable) {
            Log.w(TAG, "SMART DJ TRACK MIX PREPLAY | pool preparation failed", failure)
        }
        val preparedIds = recommendationPool.peek(
            expectedSessionId = sessionId,
            count = requestedTracks,
            excludedTrackIds = setOf(selectedTrack.id)
        )
        Log.i(
            TAG,
            "SMART DJ TRACK MIX PREPLAY | complete | requested=$requestedTracks | " +
                "prepared=${preparedIds.size} | " + recommendationPool.describe(sessionId)
        )
        return TrackMixPreparedInitialPool(sessionId, preparedIds)
    }

    private fun ensureRecommendationPoolReady'''
m, count = re.subn(func_pattern, func_repl, m, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f"GoneSmartModule post-play prep replacement count={count}")
module.write_text(m)

# ---------------------------------------------------------------------------
# Update standing comments/policy. r30 bootstrap precedes the existing
# WAIT_PLAY pass-through; all later unrelated refills still use that policy.
# ---------------------------------------------------------------------------
policy = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt")
p = policy.read_text()
p = p.replace(
    "internal object TrackMixInitialFillPolicy {\n",
    "internal object TrackMixInitialFillPolicy {\n",
    1
)
policy.write_text(p)

agents = Path("AGENTS.md")
a = agents.read_text()
needle = "- On GMMP 4.2.1, keep native Play/`WAIT_PLAY` continuity rows alive while the selected-current recommendation pool is prepared. If fewer than `Initial Size - 1` future native rows exist, request only that slot shortfall before provider work.\n"
replacement = "- On GMMP 4.2.1 Track Auto-DJ, resolve the clicked Track **before native Play** from bounded semantic evidence in the native context-menu callback graph and the read-only GMMP library cache. Require a unique strong match (exact path, ID plus corroborating text, or unique title+artist); never guess from an obfuscated field name or a bare numeric coincidence.\n- Prepare the selected-track recommendation IDs before native Play. Around the first native `qr.z(...)` refill whose Current independently verifies the resolved Track ID, pass GMMP through its normal refill but replace the native `kr.F1(int)` selection objects with the immutable prepared GoneSmart Track IDs **before** GMMP materializes queue rows / preloads `current+1`. Native rows remain only for a genuine prepared-pool shortfall.\n"
if needle not in a:
    raise SystemExit("AGENTS r29 Track Mix rule missing")
a = a.replace(needle, replacement, 1)
needle2 = "- Build the 4.2.1 Initial Size by retargeting already materialized contiguous upcoming native entities through verified Queue DAO `O0(List)`. Preserve queue IDs and positions, verify rewritten `current+1` while all old rows still exist, then delete surplus rows through the typed native DAO delete. Never expose a one-row gap.\n"
replacement2 = "- Do **not** post-retarget Track IDs of a materialized 4.2.1 `current+1`: r28 proved GMMP can already have an AudioSource/decoder for that old Track. After r30 bootstrap materialization, Queue DAO mutation may only prune surplus rows while preserving the already-correct first upcoming Track IDs, queue IDs and positions.\n"
if needle2 not in a:
    raise SystemExit("AGENTS r29 retarget rule missing")
a = a.replace(needle2, replacement2, 1)
agents.write_text(a)

# Append concise r30 evidence to durable docs.
for path, text in {
    "docs/GMMP_COMPATIBILITY_PLAYBOOK.md": '''\n\n### r30 pre-Play Track Mix bootstrap\n\nr29 removed the `CLEAR_QUEUE` null-source gap, but the r28 device trace also proved a deeper race: during the long selected-current recommendation calculation GMMP had already materialized, selected as `next audio source`, and opened the decoder for an unrelated native Track 2. Therefore changing that queue row's Track ID after recommendation preparation is not playback-safe even when `current+1` never disappears. r30 moves Track Mix selection before native Play. A bounded semantic resolver walks only the native context-menu callback graph and compares direct scalar evidence against the read-only GMMP library cache; it accepts only a unique strong target and otherwise fails closed before touching playback. Recommendations are then prepared from a synthetic selected-current session. For the first `qr.z(...)` refill whose native Current independently matches that resolved Track ID, GoneSmart wraps the existing refill with an immutable bootstrap selection window. The already accepted native Auto-DJ selection hook replaces `kr.F1(int)` result Track IDs before queue rows and AudioSources are materialized. Any prepared-pool shortfall remains native. Later queue mutation is prune-only and must not rewrite the first upcoming Track IDs.\n''',
    "docs/GMMP_421_COMPLETION.md": '''\n\n### r30 pending device re-acceptance\n\nr29 is superseded before device acceptance because static/log review showed its in-place post-materialization retarget could still leave GMMP's already-preloaded native Track-2 AudioSource stale. r30 resolves the clicked Track from the native context-menu object graph before Play, prepares immutable GoneSmart Track IDs first, and injects them at the proven native Auto-DJ selection boundary during the first refill that independently verifies the target as Current. No 4.2.1 `CLEAR_QUEUE`, no post-materialization Track-ID rewrite, and no same-position playback refresh are allowed. Playlist Link keeps the r29 exact-menuLink click classifier. Device acceptance remains pending.\n''',
    "docs/GMMP_421_REACCEPTANCE_CHECKLIST.md": '''\n\n## r30 regression focus\n\n- Playlist Link retains the r29 requirement: `BRIDGE TOOLBAR LINK` then `toolbarAdd=true | edit=false | intercepted=true`, followed by the two-way `Smart Playlist` / `Playlist` chooser.\n- Track Mix must log `MIX PREPLAY TARGET | resolved=true` and `MIX PREPLAY SMART POOL` **before** `MIX START | ... nativePlay=dispatched`. If semantic target resolution is ambiguous, Track Mix must fail before native Play rather than guess.\n- The first target-owned native refill must log `MIX PREPLAY BOOTSTRAP OFFER`, `SMART DJ TRACK MIX BOOTSTRAP` and `MIX PREPLAY NATIVE REFILL`. GoneSmart Track IDs must therefore be present when GMMP first materializes/preloads the upcoming rows; there must be no earlier unrelated Track-2 AudioSource/decoder that is later rewritten.\n- 4.2.1 startup must contain no `CLEAR_QUEUE`, no post-materialization Smart Track-ID retarget, and no playback-refresh workaround. Queue DAO use after bootstrap is prune-only. The first generated Track must naturally open its decoder and play audio.\n'''
}.items():
    pth = Path(path)
    pth.write_text(pth.read_text() + text)
