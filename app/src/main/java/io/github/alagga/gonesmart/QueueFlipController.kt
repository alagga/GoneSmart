package io.github.alagga.gonesmart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.widget.PopupMenu
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.lang.reflect.InvocationTargetException
import kotlin.math.roundToInt

/**
 * Opt-in full queue reversal and reverse playback for GMMP 4.2.0.
 *
 * Existing queue: update native queue entities transactionally using
 * GMMP's own Room DAO and move its playback pointer with the SAME entry.
 * Playlist / smart playlist: forward the selected row's original Play
 * command, then reverse the NEW list just before MusicService.w1(action=0)
 * builds the playback queue. Neither path edits any playlist on disk.
 */
internal class QueueFlipController {
    companion object {
        private const val TAG = "GoneSmartFlip"

        // Our own non-resource ID; GMMP resource IDs begin with 0x7f.
        private const val FLIP_ACTION_ID = 0x47534601

        private const val QUEUE_MENU = "menu_gm_queue"
        private const val PLAYLIST_MENU = "menu_gm_context_playlist_list"
        private const val PLAYLIST_DETAIL_MENU = "menu_gm_context_playlist"
        private const val SMART_MENU = "menu_gm_context_smart"
    }

    // GMMP's Room queue DAO explicitly rejects database reads on
    // Android's main thread. Keep all diagnostic snapshots off the UI
    // thread without altering any native playback state.
    private val diagnosticsExecutor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "GoneSmartFlipDiagnostics").apply {
                isDaemon = true
            }
        }

    private val mainThread = Handler(Looper.getMainLooper())

    private data class PendingPlayback(
        val kind: Kind,
        val createdAt: Long
    )

    @Volatile
    private var pendingPlayback: PendingPlayback? = null

    @Volatile
    private var queueFlipInProgress = false

    @Volatile
    private var enabled = false

    @Volatile
    private var nativeQueue: WeakReference<Any>? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) pendingPlayback = null
        Log.i(TAG, "FLIP SETTINGS | enabled=$value | phase=native-flip")
    }

    fun captureNativeQueue(candidate: Any?) {
        if (candidate?.javaClass?.name != "ex3") return
        nativeQueue = WeakReference(candidate)
    }

    fun onMenuInflated(
        menuResId: Int,
        menu: Menu?,
        inflater: Any?
    ) {
        if (menu == null) return
        val context = menuContext(menu, inflater) ?: return
        if (context.packageName != "gonemad.gmmp") return

        val menuName = runCatching {
            context.resources.getResourceEntryName(menuResId)
        }.getOrNull() ?: return

        val kind = when (menuName) {
            QUEUE_MENU -> Kind.QUEUE
            PLAYLIST_MENU, PLAYLIST_DETAIL_MENU -> Kind.PLAYLIST
            SMART_MENU -> Kind.SMART
            else -> return
        }

        val existing = menu.findItem(FLIP_ACTION_ID)
        if (!enabled) {
            existing?.isVisible = false
            return
        }
        if (existing != null) {
            existing.isVisible = true
            return
        }

        val anchorName = when (kind) {
            Kind.QUEUE -> "menuRemoveDuplicates"
            Kind.PLAYLIST, Kind.SMART -> "menuContextShuffle"
        }
        val anchorId = context.resources.getIdentifier(
            anchorName,
            "id",
            context.packageName
        )
        val anchor = if (anchorId != 0) menu.findItem(anchorId) else null
        val nativePlayId = context.resources.getIdentifier(
            "menuContextPlay",
            "id",
            context.packageName
        )
        val nativePlay = if (nativePlayId != 0) {
            menu.findItem(nativePlayId)
        } else {
            null
        }

        // GMMP supplies its own localized Play/Queue label. Two custom
        // bold counter-directional arrows replace the very thin ⇵ glyph.
        // The usual full-size GoneSmart two-star lilac badge follows.
        val baseLabel = when (kind) {
            Kind.QUEUE -> nativeString(context, "queue") ?: "Queue"
            Kind.PLAYLIST, Kind.SMART ->
                nativePlay?.title?.toString()
                    ?: nativeString(context, "play")
                    ?: "Play"
        }
        val title = brandedMenuTitle(context, baseLabel)

        val item = menu.add(
            Menu.NONE,
            FLIP_ACTION_ID,
            anchor?.order ?: Menu.NONE,
            title
        )
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        item.setOnMenuItemClickListener {
            onFlipPressed(kind, context, menu, menuName, nativePlayId)
            true
        }

        val placed = if (anchor != null) {
            moveRelativeToAnchor(
                menu = menu,
                inserted = item,
                anchor = anchor,
                before = kind == Kind.QUEUE
            )
        } else {
            false
        }

        Log.i(
            TAG,
            "FLIP MENU | name=$menuName | " +
                "kind=$kind | anchorFound=${anchor != null} | " +
                "positioned=$placed | playFound=${nativePlay != null} | " +
                "menuClass=${menu.javaClass.name}"
        )
        Log.i(
            TAG,
            "FLIP ITEMS | " +
                (0 until menu.size()).joinToString(" | ") { index ->
                    val child = menu.getItem(index)
                    "$index:${child.title}#${child.itemId}"
                }
        )
    }

    private enum class Kind {
        QUEUE,
        PLAYLIST,
        SMART
    }

    private fun onFlipPressed(
        kind: Kind,
        context: Context,
        menu: Menu,
        menuName: String,
        nativePlayId: Int
    ) {
        Log.i(
            TAG,
            "FLIP CLICK | menu=$menuName | kind=$kind | " +
                "nativePlay=${if (nativePlayId != 0)
                    menu.findItem(nativePlayId)?.title else null}"
        )
        if (!enabled) return
        when (kind) {
            Kind.QUEUE -> flipCurrentQueue(context)
            Kind.PLAYLIST, Kind.SMART -> playPlaylistFlipped(
                kind, context, menu, nativePlayId
            )
        }
    }

    private fun toast(context: Context, message: String) {
        mainThread.post {
            Toast.makeText(
                context.applicationContext ?: context,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Use the exact original PopupMenu listener that GMMP installed for
     * THIS row. xn0 captures the selected playlist/smart playlist through
     * zn0, which prevents accidentally starting a different playlist.
     */
    private fun playPlaylistFlipped(
        kind: Kind,
        context: Context,
        menu: Menu,
        nativePlayId: Int
    ) {
        val play = menu.findItem(nativePlayId)
        val callback = field(menu, "mCallback")
        val popup = callback?.let { field(it, "this$0") }
        val listener = popup?.let {
            field(it, "mMenuItemClickListener")
                ?: field(it, "mOnMenuItemClickListener")
        } as? PopupMenu.OnMenuItemClickListener

        if (play == null || listener == null) {
            Log.e(TAG, "FLIP PLAY | kind=$kind | native row listener unavailable")
            toast(context, "Unable to start this playlist in reverse.")
            return
        }
        synchronized(this) {
            if (pendingPlayback != null) {
                Log.w(TAG, "FLIP PLAY | replacing previously pending request")
            }
            pendingPlayback = PendingPlayback(kind, SystemClock.elapsedRealtime())
        }
        try {
            Log.i(TAG, "FLIP PLAY | kind=$kind | native play dispatched")
            val started = listener.onMenuItemClick(play)
            if (!started) {
                synchronized(this) { pendingPlayback = null }
                Log.w(TAG, "FLIP PLAY | kind=$kind | native listener declined")
                toast(context, "Could not start this playlist.")
            }
        } catch (failure: Throwable) {
            synchronized(this) { pendingPlayback = null }
            Log.e(TAG, "FLIP PLAY | native Play failed", failure)
            toast(context, "Could not start this playlist.")
        }
    }

    /**
     * Called BEFORE GMMP executes MusicService.w1.
     * Its action=0 path resets the queue and inserts this List<rm3> of
     * resolved songs. Intercept only that path, after the exact menu
     * listener was activated, and reverse the list before any native
     * playback begins. No delayed queue-flip race or transient first song.
     *
     * The pending request expires rather than affecting later normal
     * playback if an empty/broken playlist never reaches MusicService.
     */
    fun consumeReversePlaylistForNativePlay(
        action: Int?,
        tracks: List<*>?
    ): List<*>? {
        if (!enabled) return null
        val source = tracks ?: return null
        val pending = synchronized(this) {
            val request = pendingPlayback ?: return@synchronized null
            if (SystemClock.elapsedRealtime() - request.createdAt > 30_000L) {
                pendingPlayback = null
                Log.w(TAG, "FLIP PLAY | timed out waiting for native playlist")
                return@synchronized null
            }
            if (action != 0 || source.isEmpty()) return@synchronized null
            val first = source.firstOrNull() ?: return@synchronized null
            val nativeSong = runCatching {
                first.javaClass.classLoader
                    ?.loadClass("rm3")
                    ?.isInstance(first) == true
            }.getOrDefault(false)
            if (!nativeSong || source.any { it == null }) {
                Log.w(TAG, "FLIP PLAY | unsupported native playback list")
                return@synchronized null
            }
            pendingPlayback = null
            request
        } ?: return null

        val reversed = QueueFlipPlanner.reverseForNewPlayback(
            source.filterNotNull()
        ).entries
        Log.i(
            TAG,
            "FLIP PLAY APPLIED | kind=${pending.kind} | " +
                "size=${reversed.size} | originalFirstId=" +
                runCatching { firstSongId(source.first()!!) }.getOrNull() +
                " | newFirstId=" +
                runCatching { firstSongId(reversed.first()) }.getOrNull() +
                " | action=0 | nativeQueueWriter=MusicService.w1"
        )
        return reversed
    }

    private fun firstSongId(track: Any): Any? =
        track.javaClass.methods.firstOrNull {
            it.name == "getId" && it.parameterCount == 0
        }?.invoke(track)

    private fun flipCurrentQueue(context: Context) {
        synchronized(this) {
            if (queueFlipInProgress) {
                toast(context, "Queue reversal is already running.")
                return
            }
            queueFlipInProgress = true
        }
        diagnosticsExecutor.execute {
            try {
                performNativeQueueFlip()
                toast(context, "Queue reversed.")
            } catch (failure: Throwable) {
                Log.e(TAG, "FLIP APPLY | failed; see rollback status", failure)
                toast(context, "Could not reverse queue; see GoneSmart log.")
            } finally {
                queueFlipInProgress = false
            }
        }
    }

    /**
     * On the worker thread (Room rejects main-thread queries), update
     * every native ey3 by its unique queue_id in ONE native DAO transaction.
     * No direct SQL: xx3.O0(List) uses GMMP's own UPDATE OR ABORT adapter.
     * queue_position has NO unique index in GMMP 4.2.0, allowing a single
     * batch reverse while keeping duplicate song IDs distinguishable.
     */
    private fun performNativeQueueFlip() {
        val queue = nativeQueue?.get()
            ?: error("GMMP native queue not captured")
        val dao = field(queue, "r")
            ?: error("GMMP queue DAO unavailable")
        val read = dao.javaClass.getMethod("H1")
        val writer = dao.javaClass.getMethod("O0", List::class.java)
        val pointer = queue.javaClass.getDeclaredMethod(
            "b2", Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val currentMethod = queue.javaClass.getDeclaredMethod("D")
            .apply { isAccessible = true }

        fun snapshot() = (read.invoke(dao) as? List<*>)
            ?.filterNotNull()
            ?.sortedBy { (field(it, "a") as Number).toInt() }
            ?: error("Cannot read GMMP queue")

        val original = snapshot()
        if (original.size < 2) {
            Log.i(TAG, "FLIP APPLY | queue too short; no changes")
            return
        }
        val oldPositions = original.map {
            (field(it, "a") as? Number)?.toInt()
                ?: error("Entry position unavailable")
        }
        val ids = original.map {
            (field(it, "d") as? Number)?.toLong()
                ?: error("Entry ID unavailable")
        }
        require(ids.toSet().size == ids.size) {
            "Non-unique native queue IDs; aborting"
        }
        require(oldPositions.zipWithNext().all { (a, b) -> b == a + 1 }) {
            "Non-contiguous native queue positions; aborting"
        }
        val oldPosition = currentMethod.invoke(queue) as? Int
            ?: error("Playback index unavailable")
        val oldIndex = oldPositions.indexOf(oldPosition)
        require(oldIndex >= 0) {
            "Playback entry missing from queue; aborting"
        }
        val oldCurrentEntryId = ids[oldIndex]
        val plan = QueueFlipPlanner.reverseAll(original, oldIndex)
        val newPosition = oldPositions[plan.newCurrentIndex]
        val positionField = original[0].javaClass.getDeclaredField("a")
            .apply { isAccessible = true }

        // Re-read just before committing; do not overwrite an unrelated
        // queue if GMMP changed its contents while the worker was waiting.
        val latest = snapshot()
        require(latest.map { (field(it, "d") as Number).toLong() } == ids &&
            (currentMethod.invoke(queue) as? Int) == oldPosition) {
            "Native queue changed before flip; aborting"
        }

        var wrote = false
        try {
            // The planned entry at each index receives that index's old
            // 1-based queue position; its unique queue ID never changes.
            plan.entries.forEachIndexed { index, entry ->
                positionField.setInt(entry, oldPositions[index])
            }
            writer.invoke(dao, ArrayList(plan.entries))
            wrote = true
            pointer.invoke(queue, newPosition)
            val verify = snapshot().map {
                (field(it, "d") as Number).toLong()
            }
            require(verify == ids.reversed() &&
                (currentMethod.invoke(queue) as? Int) == newPosition) {
                "Native queue verification failed"
            }
            Log.i(
                TAG,
                "FLIP APPLIED | size=${original.size} | " +
                    "oldPosition=$oldPosition | newPosition=$newPosition | " +
                    "currentEntryId=$oldCurrentEntryId | " +
                    "verified=true | writer=xx3.O0"
            )
        } catch (failure: Throwable) {
            // Restore the original queue if the write succeeded but the
            // native playback pointer/verification failed.
            if (wrote) {
                runCatching {
                    original.forEachIndexed { index, entry ->
                        positionField.setInt(entry, oldPositions[index])
                    }
                    writer.invoke(dao, ArrayList(original))
                    pointer.invoke(queue, oldPosition)
                    Log.w(TAG, "FLIP ROLLBACK | previous queue restored")
                }.onFailure {
                    Log.e(TAG, "FLIP ROLLBACK | failed", it)
                }
            }
            throw (failure as? InvocationTargetException)?.targetException
                ?: failure
        }
    }

    /**
     * Non-destructive discovery of the PLAYLIST / SMART source.
     * Menus are built from different Android implementations in GMMP:
     * platform MenuBuilder for playlist lists and AppCompat for Queue.
     * The native Play item may use an item-click listener, a MenuBuilder
     * callback or activity/fragment dispatch. Log class and captured
     * field TYPES only; never call Play or touch the old queue here.
     */
    private fun logNativePlaylistTarget(
        kind: Kind,
        menu: Menu,
        nativePlayId: Int
    ) {
        val play = if (nativePlayId != 0) menu.findItem(nativePlayId) else null
        val clickListener = play?.let { field(it, "mClickListener") }
        val menuCallback = field(menu, "mCallback")
        val menuInfo = play?.menuInfo
        // Both list menus use android.widget.PopupMenu$1 as a generic
        // wrapper. Its this$0 field holds the actual PopupMenu, which in
        // turn retains GMMP's original OnMenuItemClickListener and anchor.
        // Their types and captured field types identify the originating
        // playlist selection without starting playback or logging titles.
        val popup = menuCallback?.let { field(it, "this$0") }
        val originalListener = popup?.let {
            field(it, "mMenuItemClickListener")
                ?: field(it, "mOnMenuItemClickListener")
        }
        val anchor = popup?.let { field(it, "mAnchor") as? View }
        val anchorTag = anchor?.tag

        fun capturedTypes(target: Any?): String {
            if (target == null) return "none"
            return target.javaClass.declaredFields
                .asSequence()
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .take(8)
                .joinToString(",") { item ->
                    item.name + ":" + item.type.simpleName
                }
                .ifEmpty { "no instance fields" }
        }

        Log.i(
            TAG,
            "FLIP TARGET | kind=$kind | phase=SOURCE_DISCOVERY | " +
                "playItem=${play?.javaClass?.name ?: "none"} | " +
                "clickListener=${clickListener?.javaClass?.name ?: "none"} | " +
                "menuCallback=${menuCallback?.javaClass?.name ?: "none"} | " +
                "menuInfo=${menuInfo?.javaClass?.name ?: "none"}"
        )
        Log.i(
            TAG,
            "FLIP TARGET TYPES | kind=$kind | " +
                "clickCaptured=${capturedTypes(clickListener)} | " +
                "callbackCaptured=${capturedTypes(menuCallback)} | " +
                "menuInfoCaptured=${capturedTypes(menuInfo)}"
        )
        Log.i(
            TAG,
            "FLIP POPUP SOURCE | kind=$kind | " +
                "popup=${popup?.javaClass?.name ?: "none"} | " +
                "gmmpListener=${originalListener?.javaClass?.name ?: "none"} | " +
                "anchor=${anchor?.javaClass?.name ?: "none"} | " +
                "anchorId=${anchor?.id ?: -1} | " +
                "anchorParent=${anchor?.parent?.javaClass?.name ?: "none"} | " +
                "anchorTag=${anchorTag?.javaClass?.name ?: "none"}"
        )
        Log.i(
            TAG,
            "FLIP POPUP TYPES | kind=$kind | " +
                "listenerCaptured=${capturedTypes(originalListener)} | " +
                "anchorTagCaptured=${capturedTypes(anchorTag)}"
        )
        Log.i(
            TAG,
            "FLIP PLAY PLAN | kind=$kind | " +
                "reverse=ALL | startAt=ORIGINAL_LAST | " +
                "status=AWAITING_NATIVE_PLAYLIST_LOAD | writes=0"
        )
    }

    /**
     * GMMP's native queue is ex3 and its DAO is ex3.r (tx3/xx3).
     * tx3.H1() returns ey3 entries; ey3.a is the visible position,
     * ey3.b is song ID and ey3.d is the unique queue entry ID.
     * We only READ and log a few entries, even for very long queues.
     */
    /**
     * Passive observer for GMMP's own queue reorder calls. A user can
     * manually drag a single queue item in a five-track test queue to
     * identify the argument direction of ex3.K(int, int). This observer
     * never invokes K and cannot alter the native reorder operation.
     */
    fun onNativeQueueMoveObserved(arg0: Int?, arg1: Int?) {
        if (!enabled) return
        Log.i(
            TAG,
            "FLIP NATIVE MOVE | arg0=$arg0 | arg1=$arg1 | " +
                "origin=GMMP | GoneSmartWrites=0"
        )
    }

    private fun logNativeQueueSnapshot() {
        val queue = nativeQueue?.get()
        if (queue == null) {
            Log.w(TAG, "FLIP QUEUE | native ex3 not captured yet")
            return
        }

        runCatching {
            val current = queue.javaClass
                .getDeclaredMethod("D").apply { isAccessible = true }
                .invoke(queue) as? Int
            val dao = field(queue, "r")
            val raw = dao?.javaClass
                ?.getMethod("H1")
                ?.invoke(dao) as? List<*>

            if (raw == null) {
                Log.w(TAG, "FLIP QUEUE | native DAO snapshot unavailable")
                return@runCatching
            }

            // Only examine candidate native mutation signatures here.
            // Actual native writes stay disabled until we confirm their
            // event ordering and playback-state behavior on a small queue.
            val queueWriters = queue.javaClass.declaredMethods
                .filter { it.name == "K" || it.name == "b2" }
                .joinToString("; ") { method ->
                    "${method.name}(" +
                        method.parameterTypes.joinToString(",") { it.simpleName } +
                        "):${method.returnType.simpleName}"
                }.ifEmpty { "none" }
            val daoWriters = dao.javaClass.methods
                .filter { it.name == "O0" }
                .joinToString("; ") { method ->
                    "${method.name}(" +
                        method.parameterTypes.joinToString(",") { it.simpleName } +
                        "):${method.returnType.simpleName}"
                }.ifEmpty { "none" }
            Log.i(
                TAG,
                "FLIP NATIVE API | queue=${queue.javaClass.name} | " +
                    "queueCandidates=$queueWriters | " +
                    "dao=${dao.javaClass.name} | daoCandidates=$daoWriters"
            )

            val sorted = raw.filterNotNull().sortedBy {
                (field(it, "a") as? Number)?.toInt()
                    ?: Int.MAX_VALUE
            }
            fun describe(entry: Any): String {
                return "position=${field(entry, "a")} " +
                    "trackId=${field(entry, "b")} " +
                    "entryId=${field(entry, "d")} " +
                    "shuffle=${field(entry, "c")}"
            }

            Log.i(
                TAG,
                "FLIP QUEUE | size=${sorted.size} | " +
                    "currentPosition=$current | " +
                    "queueClass=${queue.javaClass.name}"
            )
            sorted.take(3).forEachIndexed { i, entry ->
                Log.i(TAG, "FLIP FIRST[$i] | ${describe(entry)}")
            }
            sorted.takeLast(3).forEachIndexed { i, entry ->
                Log.i(TAG, "FLIP LAST[$i] | ${describe(entry)}")
            }

            // Full reversal: ABCDE -> EDCBA. The current SONG stays
            // active whether playing or paused, but follows its entry to
            // the NEW queue index (n - 1 - oldIndex). This phase is
            // read-only until GMMP's native reorder writer is validated.
            if (sorted.size < 2) {
                Log.i(TAG, "FLIP PLAN | queue too short; no-op")
                return@runCatching
            }
            val currentIndex = sorted.indexOfFirst {
                (field(it, "a") as? Number)?.toInt() == current
            }
            if (current == null || currentIndex < 0) {
                Log.w(
                    TAG,
                    "FLIP PLAN | current entry not found; refusing to " +
                        "build a reversal without playback continuity"
                )
                return@runCatching
            }

            val currentEntry = sorted[currentIndex]
            val plan = QueueFlipPlanner.reverseAll(
                items = sorted,
                currentIndex = currentIndex
            )
            val planned = plan.entries
            val plannedMovement = planned.indices.count { index ->
                planned[index] !== sorted[index]
            }
            val currentEntryId = field(currentEntry, "d")
            Log.i(
                TAG,
                "FLIP PLAN | mode=DRY_RUN | size=${sorted.size} | " +
                    "oldCurrentIndex=$currentIndex | " +
                    "newCurrentIndex=${plan.newCurrentIndex} | " +
                    "currentEntryId=$currentEntryId | " +
                    "currentPreserved=" +
                    (planned[plan.newCurrentIndex] === currentEntry) + " | " +
                    "wouldMove=$plannedMovement | writes=0"
            )
            planned.take(3).forEachIndexed { i, entry ->
                Log.i(
                    TAG,
                    "FLIP PLAN FIRST[$i] | targetPosition=" +
                        field(sorted[i], "a") + " | " + describe(entry)
                )
            }
            planned.takeLast(3).forEachIndexed { i, entry ->
                val safeIndex = planned.size - planned.takeLast(3).size + i
                Log.i(
                    TAG,
                    "FLIP PLAN LAST[$safeIndex] | targetPosition=" +
                        field(sorted[safeIndex], "a") + " | " + describe(entry)
                )
            }
        }.onFailure {
            Log.e(TAG, "FLIP QUEUE | snapshot failed", it)
        }
    }

    private fun brandedMenuTitle(context: Context, label: String): CharSequence {
        // Text first, then the original typographic two-way arrow with
        // a slight native-font-weight adjustment, then our unchanged
        // 28 dp two-star lilac sparkle. ReplacementSpans do not increase
        // native GMMP popup row height.
        val badge = PlayerAutoDjBadgeController.SparkleBadgeDrawable(
            0xFFA39AFF.toInt(),
            scale = 1.85f
        )
        val text = SpannableString("$label  \uFFFC  \uFFFC")
        val arrowsIndex = text.indexOf('\uFFFC')
        val badgeIndex = text.lastIndexOf('\uFFFC')
        text.setSpan(
            BoldReverseArrowsSpan(context),
            arrowsIndex,
            arrowsIndex + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            BaselineCenteredSparkleSpan(context, badge),
            badgeIndex,
            badgeIndex + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    /**
     * Keep the original typographic ⇵ with its subtly strengthened
     * font-matched stroke. Separate its two arrow halves by a small
     * text-proportional tracking gap instead of changing their shapes.
     *
     * Clipping and translating only the right half preserves the exact
     * arrowheads, glyph height and weight the user approved. The span
     * does not change line metrics or the neighboring sparkle.
     */
    private class BoldReverseArrowsSpan(context: Context) : ReplacementSpan() {
        private val density = context.resources.displayMetrics.density
        private val glyph = "⇵"

        // Roughly the tracking between ordinary adjacent glyphs at the
        // same text size; deliberately subtler than inserting a space.
        private fun gap(paint: Paint): Float =
            (paint.textSize * 0.16f).coerceIn(2f * density, 3.4f * density)

        private fun arrowPaint(textPaint: Paint): Paint {
            // Measure the glyph outline instead of guessing an Android
            // dp width. Font, typeface and accessibility scale all come
            // directly from the native GMMP menu's TextView.
            val stem = Path()
            textPaint.getTextPath("l", 0, 1, 0f, 0f, stem)
            val bounds = RectF()
            stem.computeBounds(bounds, true)
            val lWidth = bounds.width().takeIf { it > 0f }
                ?: (textPaint.textSize * 0.075f)

            return Paint(textPaint).apply {
                style = Paint.Style.FILL_AND_STROKE
                // The original ⇵ glyph remains the basis. Increase its
                // original line width only slightly (~20% of a native
                // lowercase l stem), never use the old 2.65/3.8 dp shafts.
                strokeWidth = (lWidth * 0.20f).coerceIn(
                    0.18f * density,
                    0.55f * density
                )
                strokeJoin = Paint.Join.ROUND
            }
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            // Do not modify fm: the menu row stays exactly as high as
            // its native neighbors despite the arrow and sparkle.
            return (paint.measureText(glyph) + gap(paint) + 2f * density)
                .roundToInt()
                .coerceAtLeast(1)
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val metrics = paint.fontMetricsInt
            val lineCenter = y + (metrics.ascent + metrics.descent) / 2f
            val glyphBounds = Rect()
            paint.getTextBounds(glyph, 0, glyph.length, glyphBounds)
            // Optical center of the actual fallback-font arrow glyph,
            // not the font's generic text bounding box.
            val glyphBaseline =
                lineCenter - (glyphBounds.top + glyphBounds.bottom) / 2f

            // Unicode ⇵ is a single glyph, so normal letterSpacing
            // cannot affect its two arrows. Draw its left/right halves
            // independently, shifting only the right half horizontally.
            // Both draws use the same native font and stroke measurement.
            val drawX = x + density
            val splitX =
                drawX + (glyphBounds.left + glyphBounds.right) / 2f
            val extra = gap(paint)
            val arrowInk = arrowPaint(paint)
            val clipTop = top.toFloat() - 8f * density
            val clipBottom = bottom.toFloat() + 8f * density
            val sidePadding = 3f * density

            val leftSave = canvas.save()
            canvas.clipRect(
                drawX + glyphBounds.left - sidePadding,
                clipTop,
                splitX,
                clipBottom
            )
            canvas.drawText(glyph, drawX, glyphBaseline, arrowInk)
            canvas.restoreToCount(leftSave)

            val rightSave = canvas.save()
            canvas.clipRect(
                splitX + extra,
                clipTop,
                drawX + glyphBounds.right + extra + sidePadding,
                clipBottom
            )
            canvas.drawText(
                glyph,
                drawX + extra,
                glyphBaseline,
                arrowInk
            )
            canvas.restoreToCount(rightSave)
        }
    }

    /**
     * Full-size 28 dp GoneSmart two-star badge, centered in the native
     * menu row without altering TextView font metrics.
     */
    private class BaselineCenteredSparkleSpan(
        context: Context,
        private val badge: PlayerAutoDjBadgeController.SparkleBadgeDrawable
    ) : ReplacementSpan() {
        private val density = context.resources.displayMetrics.density

        private fun badgeSize(): Int =
            (28f * density).roundToInt().coerceAtLeast(1)

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = badgeSize() + (3f * density).roundToInt()

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val size = badgeSize()
            val metrics = paint.fontMetricsInt
            val fontCenter = y + (metrics.ascent + metrics.descent) / 2f
            badge.setBounds(0, 0, size, size)
            val saveCount = canvas.save()
            canvas.translate(x, fontCenter - size / 2f)
            badge.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }

    private fun nativeString(context: Context, name: String): String? {
        val res = context.resources.getIdentifier(
            name,
            "string",
            context.packageName
        )
        return if (res != 0) {
            runCatching { context.getString(res) }.getOrNull()
        } else {
            null
        }
    }

    private fun menuContext(menu: Menu, inflater: Any?): Context? {
        val fromMenu = runCatching {
            menu.javaClass.methods.firstOrNull {
                it.name == "getContext" && it.parameterCount == 0
            }?.invoke(menu) as? Context
        }.getOrNull()
        if (fromMenu != null) return fromMenu

        if (inflater is MenuInflater) {
            return field(inflater, "mContext") as? Context
        }
        return null
    }

    private fun field(instance: Any, name: String): Any? {
        var cls: Class<*>? = instance.javaClass
        while (cls != null && cls != Any::class.java) {
            val current = cls
            val resolved = runCatching {
                current.getDeclaredField(name).apply {
                    isAccessible = true
                }.get(instance)
            }
            if (resolved.isSuccess) return resolved.getOrNull()
            cls = cls.superclass
        }
        return null
    }

    /**
     * GMMP's AppCompat MenuBuilder retains item order in mItems.
     * When available, place the new entry exactly after Shuffle or
     * immediately before Remove duplicates. Native items are otherwise
     * unchanged and Menu.add's normal order is left as a fallback.
     */
    private fun moveRelativeToAnchor(
        menu: Menu,
        inserted: MenuItem,
        anchor: MenuItem,
        before: Boolean
    ): Boolean {
        val items = field(menu, "mItems") as? MutableList<*> ?: return false
        @Suppress("UNCHECKED_CAST")
        val editable = items as MutableList<Any>
        val originalIndex = editable.indexOf(inserted)
        if (originalIndex < 0 || editable.indexOf(anchor) < 0) return false
        return runCatching {
            editable.removeAt(originalIndex)
            val anchorIndex = editable.indexOf(anchor)
            editable.add(
                if (before) anchorIndex else anchorIndex + 1,
                inserted
            )
            true
        }.getOrDefault(false)
    }
}
