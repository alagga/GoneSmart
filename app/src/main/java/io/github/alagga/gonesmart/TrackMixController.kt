package io.github.alagga.gonesmart

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
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
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Adds "Titel-Mix" / "Track Mix" to *single-track* native context menus.
 * Calls the exact native Play action for the clicked row, keeps only its
 * currently playing track through GMMP's own CLEAR_QUEUE command, enables
 * GMMP's documented AUTO_DJ command, and verifies the Initial Size.
 *
 * The track menu is installed independently of the Smart DJ switch so
 * choosing Track Mix can turn Smart DJ on even when it was disabled.
 */
internal class TrackMixController(
    private val enableSmartDj: (Context) -> Boolean,
    private val requestNativeRefill: (Int) -> Boolean
) {
    companion object {
        private const val TAG = "GoneSmartTrackMix"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val COMMAND_CLEAR_QUEUE = "gonemad.gmmp.command.CLEAR_QUEUE"
        private const val COMMAND_AUTO_DJ = "gonemad.gmmp.command.AUTO_DJ"
        private const val MIX_ITEM_ID = 0x47534D01
        // These GMMP 4.2.0 menu XMLs all have native Play and Play next.
        // Contexts that represent whole albums/artists/playlists are excluded.
        private val SONG_MENUS = setOf(
            "menu_gm_context_track",
            "menu_gm_context_queue",
            "menu_gm_context_search",
            "menu_gm_context_playlist_details",
            "menu_gm_context_file_browser",
            "menu_gm_context_shared"
        )
    }

    private data class Snapshot(
        val ids: List<Long>,
        val currentIndex: Int
    ) {
        val currentId: Long? get() = ids.getOrNull(currentIndex)
    }

    private data class Pending(
        val token: Long,
        val context: Context,
        val source: String,
        val before: Snapshot?,
        val createdAt: Long
    ) {
        @Volatile var nativePlaySignal = false
        @Volatile var refillObserved = false
        @Volatile var stage = "WAIT_PLAY"
        @Volatile var nativeSource = ""
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoneSmartTrackMix").apply { isDaemon = true }
    }
    private val tokens = AtomicLong()
    private val events = GoneSmartRuntimeReporter()
    private val settings = GmmpAutoDjSettingsReader()

    @Volatile private var pending: Pending? = null
    @Volatile private var nativeQueue: WeakReference<Any>? = null
    @Volatile private var nativeAutoDj: WeakReference<Any>? = null

    fun captureNativeQueue(instance: Any?) {
        if (instance?.javaClass?.name == "ex3") {
            nativeQueue = WeakReference(instance)
        }
    }

    fun captureNativeAutoDj(instance: Any?) {
        if (instance?.javaClass?.name == "qr") {
            nativeAutoDj = WeakReference(instance)
        }
    }

    fun onNativePlaybackQueueUpdated(origin: String) {
        val request = pending ?: return
        if (request.stage == "WAIT_PLAY") {
            request.nativePlaySignal = true
            request.nativeSource = origin
            Log.i(TAG, "MIX NATIVE PLAY | source=${request.source} | via=$origin")
        }
    }

    fun onNativePlaybackMethodFinished(action: Int?) {
        if (action == 0) onNativePlaybackQueueUpdated("MusicService.w1")
    }

    fun onNativeAutoDjRefillRequested(requested: Int) {
        val request = pending ?: return
        if (request.stage == "FILLING") {
            request.refillObserved = true
            Log.i(TAG, "MIX NATIVE REFILL | requested=$requested")
        }
    }

    fun onMenuInflated(menuResId: Int, menu: Menu?, inflater: Any?) {
        if (menu == null) return
        val context = menuContext(menu, inflater) ?: return
        if (context.packageName != GMMP_PACKAGE) return
        val name = runCatching {
            context.resources.getResourceEntryName(menuResId)
        }.getOrNull() ?: return
        if (name !in SONG_MENUS) return
        if (menu.findItem(MIX_ITEM_ID) != null) return

        val playId = context.resources.getIdentifier(
            "menuContextPlay", "id", GMMP_PACKAGE
        )
        val nextId = context.resources.getIdentifier(
            "menuContextPlayNext", "id", GMMP_PACKAGE
        )
        val nativePlay = menu.findItem(playId) ?: return
        val nativeNext = menu.findItem(nextId) ?: return
        val label = if (context.resources.configuration.locales[0].language == "de") {
            "Titel-Mix"
        } else {
            "Track Mix"
        }
        val item = menu.add(
            Menu.NONE,
            MIX_ITEM_ID,
            nativeNext.order,
            lilacSparkleTitle(context, label)
        )
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        item.contentDescription = if (label == "Titel-Mix") {
            "Diesen Titel abspielen und GoneSmart Auto-DJ starten"
        } else {
            "Play this song and start GoneSmart Auto-DJ"
        }
        item.setOnMenuItemClickListener {
            onMixClicked(context, name, menu, nativePlay)
            true
        }

        val inserted = insertAfter(menu, item, nativeNext)
        Log.i(
            TAG,
            "MIX MENU | menu=$name | positionAfterPlayNext=$inserted | " +
                "nativePlay=${nativePlay.title}"
        )
    }

    private fun onMixClicked(
        context: Context,
        source: String,
        menu: Menu,
        nativePlay: MenuItem
    ) {
        if (pending != null) {
            toast(context, "A track mix is already starting.")
            return
        }
        if (!nativePlay.isEnabled) {
            toast(context, "This song cannot be played.")
            return
        }

        // GMMP Room forbids reads on the UI thread. Capture the old queue
        // before native Play so an async callback cannot mistake it for
        // the selected song's new queue.
        val old = runCatching {
            work.submit<Snapshot?> { queueSnapshot() }
                .get(350, TimeUnit.MILLISECONDS)
        }.getOrNull()

        val request = Pending(
            token = tokens.incrementAndGet(),
            context = context.applicationContext,
            source = source,
            before = old,
            createdAt = SystemClock.elapsedRealtime()
        )
        pending = request

        if (!enableSmartDj(context)) {
            pending = null
            fail("Smart DJ could not be enabled.", context)
            return
        }

        val started = runCatching {
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
            if (click != null) {
                click.isAccessible = true
                click.invoke(listener, nativePlay) as? Boolean == true
            } else {
                // AppCompat and non-PopupMenu native context menus can
                // dispatch through MenuBuilder instead.
                menu.performIdentifierAction(nativePlay.itemId, 0)
            }
        }.onFailure { Log.e(TAG, "Native track Play failed", it) }
            .getOrDefault(false)

        if (!started) {
            if (pending === request) pending = null
            fail("Could not start this song.", context)
            return
        }

        Log.i(TAG, "MIX START | menu=$source | nativePlay=dispatched")
        work.execute { runTrackMix(request) }
    }

    private fun runTrackMix(request: Pending) {
        try {
            // Native Play may replace a whole album/playlist asynchronously
            // or jump to another position in the existing queue. In both
            // cases, GMMP's own CLEAR_QUEUE retains the newly playing song.
            val loaded = waitForSelectedSong(request) ?: run {
                fail("The selected song did not start.", request.context)
                return
            }
            if (!isCurrent(request)) return
            val selectedId = loaded.currentId ?: run {
                fail("The selected song could not be identified.", request.context)
                return
            }

            request.stage = "CLEARING"
            val cleared = if (loaded.ids.size == 1) {
                loaded
            } else {
                sendCommand(request.context, COMMAND_CLEAR_QUEUE)
                val clearStarted = SystemClock.elapsedRealtime()
                awaitQueue(request, 5_000L) {
                    it.currentId == selectedId &&
                        (
                            it.ids.size == 1 ||
                                (SystemClock.elapsedRealtime() - clearStarted > 450L &&
                                    it.currentIndex == 0 &&
                                    it.ids != loaded.ids)
                            )
                }
            }
            if (cleared == null) {
                fail("Could not isolate the selected song in the queue.", request.context)
                return
            }
            Log.i(
                TAG,
                "MIX SEED | queueSize=${cleared.ids.size} | " +
                    "currentPreserved=true"
            )
            request.stage = "FILLING"
            val nativeSettings = settings.read()
            val initial = nativeSettings.initialQueueSize.coerceAtLeast(1)
            val upcoming = nativeSettings.upcomingTrackCount
            sendCommand(request.context, COMMAND_AUTO_DJ)
            Log.i(
                TAG,
                "MIX AUTO-DJ | command=sent | initialSize=$initial | " +
                    "upcoming=$upcoming | seed=$selectedId"
            )

            var filled = awaitQueue(request, 3_000L) {
                it.currentId == selectedId &&
                    TrackMixPlan.hasEnoughTracks(initial, it.ids.size)
            }
            if (filled == null && isCurrent(request) && !request.refillObserved) {
                val actual = queueSnapshot()?.ids?.size ?: 1
                val shortage = TrackMixPlan.additionalTracksNeeded(initial, actual)
                if (shortage > 0) {
                    Log.i(TAG, "MIX FILL FALLBACK | missing=$shortage")
                    requestNativeRefill(shortage)
                }
            }
            if (filled == null) {
                filled = awaitQueue(request, 65_000L) {
                    it.currentId == selectedId &&
                        TrackMixPlan.hasEnoughTracks(initial, it.ids.size)
                }
            }

            if (!isCurrent(request)) return
            if (filled == null) {
                Log.w(
                    TAG,
                    "MIX INCOMPLETE | initial=$initial | " +
                        "actual=${queueSnapshot()?.ids?.size ?: -1}"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_SMART_DJ,
                    "Track Mix started, but the initial Auto-DJ queue " +
                        "could not be verified."
                )
                toast(request.context, "Track Mix started; Auto-DJ fill is still pending.")
            } else {
                Log.i(
                    TAG,
                    "MIX VERIFIED | initial=$initial | actual=${filled.ids.size} | " +
                        "currentPreserved=true | recommendations=${filled.ids.size - 1}"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_SMART_DJ,
                    "Track Mix started: ${filled.ids.size - 1} " +
                        "Auto-DJ tracks added after the selected song."
                )
                toast(request.context, "Track Mix started.")
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Track Mix failed", failure)
            fail("Track Mix could not be completed.", request.context)
        } finally {
            request.stage = "DONE"
            if (pending === request) pending = null
        }
    }

    private fun waitForSelectedSong(request: Pending): Snapshot? {
        var stable: Snapshot? = null
        var stableAt = 0L
        val deadline = SystemClock.elapsedRealtime() + 9_000L
        while (isCurrent(request) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(160)
            val current = queueSnapshot() ?: continue
            if (current.currentId == null) continue
            val changed = request.before == null ||
                current.ids != request.before.ids ||
                current.currentIndex != request.before.currentIndex
            val nativeReady = request.nativePlaySignal || changed
            if (!nativeReady) continue
            if (stable == current) {
                val age = SystemClock.elapsedRealtime() - stableAt
                if (age >= 350 && (
                        changed ||
                            (request.source == "menu_gm_context_queue" &&
                                request.nativeSource == "ex3.b2") ||
                            SystemClock.elapsedRealtime() - request.createdAt > 4_000L
                        )
                ) return current
            } else {
                stable = current
                stableAt = SystemClock.elapsedRealtime()
            }
        }
        return null
    }

    private fun awaitQueue(
        request: Pending,
        timeoutMs: Long,
        condition: (Snapshot) -> Boolean
    ): Snapshot? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (isCurrent(request) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(175)
            val snapshot = queueSnapshot()
            if (snapshot != null && condition(snapshot)) return snapshot
        }
        return null
    }

    private fun isCurrent(request: Pending): Boolean =
        pending === request

    private fun queueSnapshot(): Snapshot? = runCatching {
        val queue = nativeQueue?.get()
            ?: nativeAutoDj?.get()?.let { field(it, "q") }
            ?: return@runCatching null
        val dao = field(queue, "r") ?: return@runCatching null
        val raw = dao.javaClass.getMethod("H1").invoke(dao) as? List<*>
            ?: return@runCatching null
        val sorted = raw.filterNotNull().sortedBy {
            (field(it, "a") as Number).toInt()
        }
        val ids = sorted.map {
            (field(it, "b") as Number).toLong()
        }
        val current = queue.javaClass.getDeclaredMethod("D")
            .apply { isAccessible = true }
            .invoke(queue) as? Int ?: return@runCatching null
        val currentIndex = sorted.indexOfFirst {
            (field(it, "a") as Number).toInt() == current
        }
        Snapshot(ids, currentIndex)
    }.onFailure {
        Log.w(TAG, "Cannot read native queue for Track Mix", it)
    }.getOrNull()

    private fun sendCommand(context: Context, action: String) {
        context.sendBroadcast(Intent(action).setPackage(GMMP_PACKAGE))
    }

    private fun fail(message: String, context: Context) {
        Log.e(TAG, "MIX FAILED | $message")
        events.reportEvent(
            GoneSmartRuntimeContract.CATEGORY_SMART_DJ,
            "Track Mix: $message"
        )
        toast(context, message)
    }

    private fun toast(context: Context, message: String) {
        main.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun lilacSparkleTitle(context: Context, label: String): CharSequence {
        val title = SpannableString("$label  \uFFFC")
        val star = PlayerAutoDjBadgeController.SparkleBadgeDrawable(
            0xFFA39AFF.toInt(), scale = 1.85f
        )
        title.setSpan(
            CenteredSparkleSpan(context, star),
            title.length - 1,
            title.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return title
    }

    private class CenteredSparkleSpan(
        context: Context,
        private val sparkle: PlayerAutoDjBadgeController.SparkleBadgeDrawable
    ) : ReplacementSpan() {
        private val density = context.resources.displayMetrics.density
        private fun size(): Int = (28f * density).roundToInt().coerceAtLeast(1)

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = size() + (3f * density).roundToInt()

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
            val px = size()
            sparkle.setBounds(0, 0, px, px)
            val save = canvas.save()
            canvas.translate(x, lineCenter - px / 2f)
            sparkle.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun insertAfter(
        menu: Menu,
        item: MenuItem,
        anchor: MenuItem
    ): Boolean {
        val items = field(menu, "mItems") as? MutableList<*> ?: return false
        @Suppress("UNCHECKED_CAST")
        val editable = items as MutableList<Any>
        return runCatching {
            val oldIndex = editable.indexOf(item)
            require(oldIndex >= 0)
            editable.removeAt(oldIndex)
            val anchorIndex = editable.indexOf(anchor)
            require(anchorIndex >= 0)
            editable.add(anchorIndex + 1, item)
            true
        }.getOrDefault(false)
    }

    private fun menuContext(menu: Menu, inflater: Any?): Context? {
        val viaMenu = runCatching {
            menu.javaClass.methods.firstOrNull {
                it.name == "getContext" && it.parameterCount == 0
            }?.invoke(menu) as? Context
        }.getOrNull()
        if (viaMenu != null) return viaMenu
        return if (inflater is MenuInflater) {
            field(inflater, "mContext") as? Context
        } else null
    }

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            val currentType = type
            val result = runCatching {
                currentType.getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(target)
            }
            if (result.isSuccess) return result.getOrNull()
            type = type.superclass
        }
        return null
    }
}
