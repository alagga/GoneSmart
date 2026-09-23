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
    private val eventReporter = GoneSmartRuntimeReporter()

    private data class PendingPlayback(
        val kind: Kind,
        val createdAt: Long
    )

    data class ReversedPlayback(
        val tracks: List<Any>,
        val sourceKind: String
    )

    @Volatile
    private var pendingPlayback: PendingPlayback? = null

    @Volatile
    private var nativePlaylistInterceptorReady = false

    fun setNativePlaylistInterceptorReady(ready: Boolean) {
        nativePlaylistInterceptorReady = ready
        Log.i(TAG, "FLIP PLAY HOOK STATUS | ready=$ready")
    }

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
        // Keep per-item menu internals out of production Logcat.
        // Only actual Flip actions and verified outcomes are logged.
    }

    private enum class Kind {
        QUEUE,
        PLAYLIST,
        SMART;

        fun displayName(): String = when (this) {
            QUEUE -> "queue"
            PLAYLIST -> "playlist"
            SMART -> "Smart Playlist"
        }
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
        if (!nativePlaylistInterceptorReady) {
            Log.e(TAG, "FLIP PLAY | native playlist interception unavailable")
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_FLIP,
                "Reverse playback unavailable: GMMP integration was not initialized."
            )
            toast(context, "Reverse playback is unavailable in this GMMP version.")
            return
        }
        val play = menu.findItem(nativePlayId)
        val callback = field(menu, "mCallback")
        val popup = callback?.let { field(it, "this$0") }
        val listener = popup?.let {
            field(it, "mMenuItemClickListener")
                ?: field(it, "mOnMenuItemClickListener")
        } as? PopupMenu.OnMenuItemClickListener

        if (play == null || listener == null) {
            Log.e(TAG, "FLIP PLAY | kind=$kind | native row listener unavailable")
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_FLIP,
                "Could not start ${kind.displayName()} in reverse."
            )
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
                eventReporter.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_FLIP,
                    "Could not start ${kind.displayName()} in reverse."
                )
                toast(context, "Could not start this playlist.")
            }
        } catch (failure: Throwable) {
            synchronized(this) { pendingPlayback = null }
            Log.e(TAG, "FLIP PLAY | native Play failed", failure)
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_FLIP,
                "Could not start ${kind.displayName()} in reverse."
            )
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
    ): ReversedPlayback? {
        if (!enabled) return null
        val source = tracks ?: return null
        val pending = synchronized(this) {
            val request = pendingPlayback ?: return@synchronized null
            if (SystemClock.elapsedRealtime() - request.createdAt > 30_000L) {
                pendingPlayback = null
                Log.w(TAG, "FLIP PLAY | timed out waiting for native playlist")
                eventReporter.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_FLIP,
                    "Reverse playlist playback timed out."
                )
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
        return ReversedPlayback(
            tracks = reversed,
            sourceKind = pending.kind.displayName()
        )
    }

    /**
     * Verify the newly inserted native queue AFTER GMMP's asynchronous
     * w1 -> ex3.w transaction has had time to complete. This makes one
     * combined on-device test enough to diagnose all three actions.
     */
    fun verifyNativePlaylistPlayback(
        expectedTracks: List<*>,
        sourceKind: String
    ) {
        val expectedIds = expectedTracks.filterNotNull().mapNotNull {
            runCatching { (firstSongId(it) as Number).toLong() }.getOrNull()
        }
        if (expectedIds.size != expectedTracks.size) {
            Log.w(TAG, "FLIP PLAY VERIFY | native track IDs unavailable")
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_FLIP,
                "Could not verify reverse $sourceKind playback: track IDs unavailable."
            )
            return
        }
        diagnosticsExecutor.execute {
            val queue = nativeQueue?.get()
            if (queue == null) {
                Log.w(TAG, "FLIP PLAY VERIFY | queue not captured")
                eventReporter.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_FLIP,
                    "Could not verify reverse $sourceKind playback: queue unavailable."
                )
                return@execute
            }
            repeat(12) { attempt ->
                Thread.sleep(250)
                val actualIds = runCatching {
                    val dao = field(queue, "r")!!
                    val raw = dao.javaClass.getMethod("H1")
                        .invoke(dao) as List<*>
                    raw.filterNotNull().sortedBy {
                        (field(it, "a") as Number).toInt()
                    }.map { (field(it, "b") as Number).toLong() }
                }.getOrNull()
                if (actualIds != null &&
                    actualIds.size >= expectedIds.size &&
                    actualIds.take(expectedIds.size) == expectedIds) {
                    val position = runCatching {
                        queue.javaClass.getDeclaredMethod("D")
                            .apply { isAccessible = true }
                            .invoke(queue)
                    }.getOrNull()
                    if (position == 1) {
                        Log.i(
                            TAG,
                            "FLIP PLAY VERIFIED | kind=$sourceKind | " +
                                "expected=${expectedIds.size} | " +
                                "queueSize=${actualIds.size} | " +
                                "first=${actualIds.firstOrNull()} | " +
                                "last=${actualIds[expectedIds.size - 1]} | " +
                                "currentPosition=$position | checks=${attempt + 1}"
                        )
                        eventReporter.reportEvent(
                            GoneSmartRuntimeContract.CATEGORY_FLIP,
                            "Playing $sourceKind in reverse: " +
                                "${expectedIds.size} tracks, starting with the original last."
                        )
                        return@execute
                    }
                }
            }
            Log.e(
                TAG,
                "FLIP PLAY VERIFY | $sourceKind playback did not match " +
                    "reversed playlist after 3 seconds"
            )
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_FLIP,
                "Reverse $sourceKind playback could not be verified."
            )
        }
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
                val count = performNativeQueueFlip()
                toast(
                    context,
                    if (count > 1) "Queue reversed." else "Queue is too short to reverse."
                )
            } catch (failure: Throwable) {
                Log.e(TAG, "FLIP APPLY | failed; see rollback status", failure)
                eventReporter.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_FLIP,
                    "Could not reverse the queue. See Logcat for details."
                )
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
    private fun performNativeQueueFlip(): Int {
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
            return original.size
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
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_FLIP,
                "Reversed ${original.size}-track queue. " +
                    "Current song moved from position $oldPosition to $newPosition."
            )
            return original.size
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
                    eventReporter.reportEvent(
                        GoneSmartRuntimeContract.CATEGORY_FLIP,
                        "Queue reversal failed; original order restored."
                    )
                }.onFailure {
                    Log.e(TAG, "FLIP ROLLBACK | failed", it)
                    eventReporter.reportEvent(
                        GoneSmartRuntimeContract.CATEGORY_FLIP,
                        "Queue reversal and recovery failed; check your queue."
                    )
                }
            }
            throw (failure as? InvocationTargetException)?.targetException
                ?: failure
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
