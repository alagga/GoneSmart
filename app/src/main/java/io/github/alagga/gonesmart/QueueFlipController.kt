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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Opt-in full queue reversal and reverse playback for GMMP.
 *
 * Existing queue: update native queue entities transactionally using
 * GMMP's own Room DAO and move its playback pointer with the SAME entry.
 * Playlist / smart playlist: forward the selected row's original Play
 * command, then reverse the NEW list just before MusicService builds the
 * playback queue. Neither path edits any playlist on disk.
 */
internal class QueueFlipController {
    companion object {
        private const val TAG = "GoneSmartFlip"
        private const val FLIP_ACTION_ID = 0x47534601
        private const val QUEUE_MENU = "menu_gm_queue"
        private const val PLAYLIST_MENU = "menu_gm_context_playlist_list"
        private const val PLAYLIST_DETAIL_MENU = "menu_gm_context_playlist"
        private const val SMART_MENU = "menu_gm_context_smart"
    }

    private val diagnosticsExecutor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "GoneSmartFlipDiagnostics").apply { isDaemon = true }
        }

    private val mainThread = Handler(Looper.getMainLooper())
    private val eventReporter = GoneSmartRuntimeReporter()
    private val positionWriterObserver = NativeQueuePositionWriterObserver()
    private val playbackTransitionObserver =
        NativeQueuePlaybackTransitionObserver()
    private val observedPositionCommands =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val autoDjAccessorFailureLogged =
        java.util.concurrent.atomic.AtomicBoolean(false)

    private data class PendingPlayback(
        val kind: Kind,
        val createdAt: Long
    )

    data class ReversedPlayback(
        val tracks: List<Any>,
        val sourceKind: String
    )

    @Volatile private var pendingPlayback: PendingPlayback? = null
    @Volatile private var nativePlaylistInterceptorReady = false
    @Volatile private var queueFlipInProgress = false
    @Volatile private var enabled = false
    @Volatile private var nativeQueue: WeakReference<Any>? = null
    @Volatile private var nativeAutoDj: WeakReference<Any>? = null
    @Volatile private var nativeMusicService: WeakReference<Any>? = null

    fun setNativePlaylistInterceptorReady(ready: Boolean) {
        nativePlaylistInterceptorReady = ready
        Log.i(TAG, "FLIP PLAY HOOK STATUS | ready=$ready")
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) pendingPlayback = null
        Log.i(TAG, "FLIP SETTINGS | enabled=$value | phase=native-flip")
    }

    fun captureNativeQueue(candidate: Any?) {
        if (candidate?.javaClass?.name != "ex3") return
        nativeQueue = WeakReference(candidate)
    }

    fun captureNativeAutoDj(candidate: Any?) {
        if (candidate != null) nativeAutoDj = WeakReference(candidate)
    }

    fun verifiedPositionWriter(autoDj: Any): NativeQueuePositionWriter? =
        positionWriterObserver.binding(autoDj)

    private fun notifyTrackMixNaturalPosition(autoDj: Any?) {
        val instance = autoDj ?: return
        val position = runCatching {
            verifiedPositionWriter(instance)?.read()
        }.getOrNull()
        TrackMixController.onVerifiedNaturalQueuePosition(
            autoDj = instance,
            currentPosition = position
        )
    }

    /**
     * Called by passive MusicService hooks. The original GMMP method always
     * runs exactly once. Observation merely compares the independent native
     * current-position signal before/after normal GMMP behavior.
     */
    fun aroundNativePositionCommand(
        service: Any?,
        method: Method,
        value: Int?,
        proceed: () -> Any?
    ): Any? {
        val autoDj = nativeAutoDj?.get()
            ?: service?.let(::resolveNativeAutoDjFromService)
        if (service != null) nativeMusicService = WeakReference(service)
        if (service != null && value != null) {
            val key = method.declaringClass.name + "." + method.name + "(int)"
            if (observedPositionCommands.add(key)) {
                Log.i(
                    TAG,
                    "QUEUE POSITION COMMAND OBSERVED | command=$key | arg=$value | " +
                        "autoDj=" + if (autoDj != null) "ready" else "unresolved"
                )
            }
        }
        val result = positionWriterObserver.aroundNaturalInvocation(
            service = service,
            method = method,
            argument = value,
            autoDj = autoDj,
            proceed = proceed
        )
        notifyTrackMixNaturalPosition(autoDj)
        return result
    }

    fun aroundNativePlaybackTransition(
        service: Any?,
        method: Method,
        args: List<Any?>,
        proceed: () -> Any?
    ): Any? {
        val autoDj = nativeAutoDj?.get()
            ?: service?.let(::resolveNativeAutoDjFromService)
        if (service != null) nativeMusicService = WeakReference(service)
        return playbackTransitionObserver.aroundNaturalInvocation(
            service = service,
            method = method,
            args = args,
            autoDj = autoDj,
            proceed = proceed
        )
    }

    fun aroundNativeStatePositionCommand(
        receiver: Any?,
        method: Method,
        value: Int?,
        proceed: () -> Any?
    ): Any? {
        val autoDj = nativeAutoDj?.get()
        if (receiver != null && value != null) {
            val key = method.declaringClass.name + "." + method.name + "(int)"
            if (observedPositionCommands.add(key)) {
                Log.i(
                    TAG,
                    "QUEUE STATE COMMAND OBSERVED | command=$key | arg=$value | " +
                        "autoDj=" + if (autoDj != null) "ready" else "unresolved"
                )
            }
        }
        val result = positionWriterObserver.aroundNaturalInvocation(
            service = receiver,
            method = method,
            argument = value,
            autoDj = autoDj,
            proceed = proceed
        )
        notifyTrackMixNaturalPosition(autoDj)
        return result
    }

    private fun resolveNativeAutoDjFromService(service: Any): Any? {
        val resolution = NativeAutoDjAccessorResolver.resolve(service)
        if (resolution != null) {
            captureNativeAutoDj(resolution.instance)
            Log.i(
                TAG,
                "QUEUE AUTO DJ CAPTURE | source=service-accessor | accessor=" +
                    resolution.accessor.declaringClass.name + "." +
                    resolution.accessor.name + "():" +
                    resolution.accessor.returnType.name
            )
            return resolution.instance
        }
        if (autoDjAccessorFailureLogged.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "QUEUE AUTO DJ ACCESSOR UNRESOLVED | candidates=" +
                    NativeAutoDjAccessorResolver.diagnosticShape(service.javaClass)
            )
        }
        return null
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
        val nativePlay = if (nativePlayId != 0) menu.findItem(nativePlayId) else null

        val baseLabel = when (kind) {
            Kind.QUEUE -> nativeString(context, "queue")
            Kind.PLAYLIST, Kind.SMART ->
                nativePlay?.title?.toString() ?: nativeString(context, "play")
        } ?: return
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
        } else false

        Log.i(
            TAG,
            "FLIP MENU | name=$menuName | kind=$kind | " +
                "anchorFound=${anchor != null} | positioned=$placed | " +
                "playFound=${nativePlay != null} | menuClass=${menu.javaClass.name}"
        )
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
            "FLIP CLICK | menu=$menuName | kind=$kind | nativePlay=" +
                if (nativePlayId != 0) menu.findItem(nativePlayId)?.title else null
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
            toast(
                context,
                NativeGmmpUiText.error(context, nativeString(context, "playlists"))
            )
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
            toast(
                context,
                NativeGmmpUiText.error(context, nativeString(context, "playlists"))
            )
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
                toast(
                    context,
                    NativeGmmpUiText.error(context, nativeString(context, "playlists"))
                )
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
            val nativeClass = first.javaClass
            val structurallyNative = source.all {
                it != null && nativeClass.isInstance(it) &&
                    runCatching { firstSongId(it) is Number }.getOrDefault(false)
            }
            if (!structurallyNative) {
                Log.w(
                    TAG,
                    "FLIP PLAY | unsupported native playback list | model=" +
                        nativeClass.name
                )
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
            "FLIP PLAY APPLIED | kind=${pending.kind} | size=${reversed.size} | " +
                "originalFirstId=" +
                runCatching { firstSongId(source.first()!!) }.getOrNull() +
                " | newFirstId=" +
                runCatching { firstSongId(reversed.first()) }.getOrNull() +
                " | action=0 | nativeQueueWriter=structural-MusicService-playback"
        )
        return ReversedPlayback(
            tracks = reversed,
            sourceKind = pending.kind.displayName()
        )
    }

    fun verifyNativePlaylistPlayback(
        expectedTracks: List<*>,
        sourceKind: String
    ) {
        val expectedIds = expectedTracks.filterNotNull().mapNotNull {
            runCatching { (firstSongId(it) as Number).toLong() }.getOrNull()
        }
        if (expectedIds.size != expectedTracks.size) return
        diagnosticsExecutor.execute {
            // The 4.2.1 native interception is device-accepted. Verification
            // is therefore a bounded postcondition check, not a polling loop:
            // repeated GmmpQueueReader reads can themselves produce the same
            // queue SQL storm that compatibility discovery is meant to avoid.
            Thread.sleep(300)
            val cursor = nativeAutoDj?.get()?.let {
                GmmpQueueReader().read(it)
            }
            if (cursor != null) {
                val ordered = cursor.items.sortedBy { it.queuePosition }
                val ids = ordered.map { it.track.id }
                val current = cursor.items.singleOrNull {
                    it.state == QueueItemState.CURRENT
                }
                if (ids.size >= expectedIds.size &&
                    ids.take(expectedIds.size) == expectedIds &&
                    current?.queueEntryId == ordered.firstOrNull()?.queueEntryId
                ) {
                    Log.i(
                        TAG,
                        "FLIP PLAY VERIFIED | kind=$sourceKind | source=cursor | " +
                            "expected=${expectedIds.size} | queueSize=${ids.size} | " +
                            "currentPosition=${current?.queuePosition ?: -1} | checks=1"
                    )
                    eventReporter.reportEvent(
                        GoneSmartRuntimeContract.CATEGORY_FLIP,
                        "Playing $sourceKind in reverse: ${expectedIds.size} tracks."
                    )
                    return@execute
                }
            }

            // Retain one legacy structural read only as a compatibility
            // fallback. Never retry it in a timer loop.
            val queue = nativeQueue?.get()
            val legacy = queue?.let {
                runCatching {
                    val dao = field(it, "r")!!
                    val raw = dao.javaClass.getMethod("H1")
                        .invoke(dao) as List<*>
                    val ids = raw.filterNotNull().sortedBy { row ->
                        (field(row, "a") as Number).toInt()
                    }.map { row ->
                        (field(row, "b") as Number).toLong()
                    }
                    val position = it.javaClass.getDeclaredMethod("D")
                        .apply { isAccessible = true }.invoke(it)
                    ids.size >= expectedIds.size &&
                        ids.take(expectedIds.size) == expectedIds &&
                        position == 1
                }.getOrDefault(false)
            } == true
            if (legacy) {
                Log.i(
                    TAG,
                    "FLIP PLAY VERIFIED | kind=$sourceKind | source=legacy | checks=1"
                )
                return@execute
            }

            Log.w(
                TAG,
                "FLIP PLAY VERIFY | one bounded postcondition check was inconclusive | " +
                    "kind=$sourceKind | expected=${expectedIds.size}"
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
                toast(
                    context,
                    NativeGmmpUiText.error(context, nativeString(context, "queue"))
                )
                return
            }
            queueFlipInProgress = true
        }
        diagnosticsExecutor.execute {
            try {
                val count = performNativeQueueFlip()
                toast(
                    context,
                    if (count > 1) {
                        (nativeString(context, "queue") ?: "").trim()
                            .takeIf(String::isNotBlank)?.plus(" ✓") ?: "✓"
                    } else {
                        NativeGmmpUiText.error(context, nativeString(context, "queue"))
                    }
                )
            } catch (failure: Throwable) {
                Log.e(TAG, "FLIP APPLY | failed; see rollback status", failure)
                eventReporter.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_FLIP,
                    "Could not reverse the queue. See Logcat for details."
                )
                toast(
                    context,
                    NativeGmmpUiText.error(context, nativeString(context, "queue"))
                )
            } finally {
                queueFlipInProgress = false
            }
        }
    }

    private fun performNativeQueueFlip(): Int {
        val legacyQueue = nativeQueue?.get()
        if (legacyQueue == null) {
            val autoDj = nativeAutoDj?.get()
                ?: error("GMMP native Auto-DJ/queue not captured")
            // First let the mutation bridge resolve a writable boundary on
            // the same native state host that already proves the read signal.
            // A passively observed MusicService writer is an additional safe
            // fallback, not a prerequisite for Queue Flip to run.
            val snapshot = NativeQueuePlaybackDiagnostics.snapshot(
                nativeMusicService?.get(),
                autoDj
            )
            Log.i(
                TAG,
                "QUEUE PLAYBACK SNAPSHOT | " +
                    snapshot.values.entries.joinToString(",") {
                        it.key + "=" + it.value
                    }.ifBlank { "none" }
            )
            val positionWriter = positionWriterObserver.binding(autoDj)
            return GmmpQueueMutationBridge(
                autoDj,
                positionWriter
            ).reverseQueue()
        }
        val queue = legacyQueue
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

        val latest = snapshot()
        require(
            latest.map { (field(it, "d") as Number).toLong() } == ids &&
                (currentMethod.invoke(queue) as? Int) == oldPosition
        ) {
            "Native queue changed before flip; aborting"
        }

        var wrote = false
        try {
            plan.entries.forEachIndexed { index, entry ->
                positionField.setInt(entry, oldPositions[index])
            }
            writer.invoke(dao, ArrayList(plan.entries))
            wrote = true
            pointer.invoke(queue, newPosition)
            val verify = snapshot().map {
                (field(it, "d") as Number).toLong()
            }
            require(
                verify == ids.reversed() &&
                    (currentMethod.invoke(queue) as? Int) == newPosition
            ) {
                "Native queue verification failed"
            }
            Log.i(
                TAG,
                "FLIP APPLIED | size=${original.size} | oldPosition=$oldPosition | " +
                    "newPosition=$newPosition | currentEntryId=$oldCurrentEntryId | " +
                    "verified=true | writer=xx3.O0"
            )
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_FLIP,
                "Reversed ${original.size}-track queue. Current song moved " +
                    "from position $oldPosition to $newPosition."
            )
            return original.size
        } catch (failure: Throwable) {
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
            throw (failure as? InvocationTargetException)?.targetException ?: failure
        }
    }

    private fun brandedMenuTitle(context: Context, label: String): CharSequence {
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

    private class BoldReverseArrowsSpan(context: Context) : ReplacementSpan() {
        private val density = context.resources.displayMetrics.density
        private val glyph = "⇵"

        private fun gap(paint: Paint): Float =
            (paint.textSize * 0.16f).coerceIn(2f * density, 3.4f * density)

        private fun arrowPaint(textPaint: Paint): Paint {
            val stem = Path()
            textPaint.getTextPath("l", 0, 1, 0f, 0f, stem)
            val bounds = RectF()
            stem.computeBounds(bounds, true)
            val lWidth = bounds.width().takeIf { it > 0f }
                ?: (textPaint.textSize * 0.075f)

            return Paint(textPaint).apply {
                style = Paint.Style.FILL_AND_STROKE
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
            val glyphBaseline =
                lineCenter - (glyphBounds.top + glyphBounds.bottom) / 2f
            val drawX = x + density
            val splitX = drawX + (glyphBounds.left + glyphBounds.right) / 2f
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
            canvas.drawText(glyph, drawX + extra, glyphBaseline, arrowInk)
            canvas.restoreToCount(rightSave)
        }
    }

    private class BaselineCenteredSparkleSpan(
        context: Context,
        private val drawable: PlayerAutoDjBadgeController.SparkleBadgeDrawable
    ) : ReplacementSpan() {
        private val density = context.resources.displayMetrics.density
        private val sizePx = (28f * density).roundToInt().coerceAtLeast(1)

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = sizePx

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
            val save = canvas.save()
            canvas.translate(x, lineCenter - sizePx / 2f)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun nativeString(context: Context, name: String): String? {
        val res = context.resources.getIdentifier(name, "string", context.packageName)
        return if (res != 0) runCatching { context.getString(res) }.getOrNull()
        else null
    }

    private fun menuContext(menu: Menu, inflater: Any?): Context? {
        val fromMenu = runCatching {
            menu.javaClass.methods.firstOrNull {
                it.name == "getContext" && it.parameterCount == 0
            }?.invoke(menu) as? Context
        }.getOrNull()
        if (fromMenu != null) return fromMenu

        if (inflater is MenuInflater) return field(inflater, "mContext") as? Context
        return null
    }

    private fun field(instance: Any, name: String): Any? {
        var cls: Class<*>? = instance.javaClass
        while (cls != null && cls != Any::class.java) {
            val current = cls
            val resolved = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            if (resolved.isSuccess) return resolved.getOrNull()
            cls = cls.superclass
        }
        return null
    }

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
            editable.add(if (before) anchorIndex else anchorIndex + 1, inserted)
            true
        }.getOrDefault(false)
    }
}
