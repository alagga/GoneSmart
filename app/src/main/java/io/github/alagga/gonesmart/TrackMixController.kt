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
import java.util.WeakHashMap
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
        val confirmation: String,
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

    @Volatile private var enabled = true

    // Only this feature's short native Play/Clear/Auto-DJ transition may
    // silence GMMP's intermediate status toasts. Never hide unrelated
    // GMMP notifications outside that window or our own confirmation.
    @Volatile private var nativeToastSuppressionUntilMs = 0L
    private val ownToasts = WeakHashMap<Toast, Boolean>()
    private val suppressedToastCount = AtomicLong()

    fun setEnabled(value: Boolean) {
        enabled = value
        Log.i(TAG, "MIX SETTINGS | enabled=$value")
    }

    fun shouldSuppressNativeRefill(): Boolean {
        val request = pending ?: return false
        val age = SystemClock.elapsedRealtime() - request.createdAt
        val suppress = age < 20_000L &&
            (request.stage == "WAIT_PLAY" || request.stage == "CLEARING")
        if (suppress) {
            Log.i(
                TAG,
                "MIX AUTO-DJ HOLD | stage=${request.stage} | " +
                    "deferring native refill until selected seed is isolated"
            )
        }
        return suppress
    }

    private fun shouldSuppressIntermediatePopup(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now <= nativeToastSuppressionUntilMs) return true
        val request = pending ?: return false
        val age = now - request.createdAt
        // Track Mix normally finishes in a few seconds. Keep a bounded
        // guard active while its native Play/Clear/Auto-DJ transition is
        // still pending so delayed GMMP status UI (including the
        // Auto-DJ-rules-changed message) cannot become a second/third
        // user-facing popup. Never suppress unrelated notifications
        // indefinitely if a provider/refill stalls.
        return age in 0..20_000L &&
            request.stage != "DONE"
    }

    fun shouldSuppressNativeToast(candidate: Toast?): Boolean {
        if (candidate == null) return false
        synchronized(ownToasts) {
            if (ownToasts.remove(candidate) != null) return false
        }
        if (!shouldSuppressIntermediatePopup()) return false
        val count = suppressedToastCount.incrementAndGet()
        Log.i(TAG, "MIX POPUP | native GMMP toast hidden | count=$count")
        return true
    }

    fun shouldSuppressNativeSnackbar(): Boolean =
        shouldSuppressIntermediatePopup()

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
        menu.findItem(MIX_ITEM_ID)?.let {
            it.isVisible = enabled
            return
        }
        if (!enabled) return

        val playId = context.resources.getIdentifier(
            "menuContextPlay", "id", GMMP_PACKAGE
        )
        val nextId = context.resources.getIdentifier(
            "menuContextPlayNext", "id", GMMP_PACKAGE
        )
        val nativePlay = menu.findItem(playId) ?: return
        val nativeNext = menu.findItem(nextId) ?: return
        val language = context.resources.configuration.locales[0].language
        fun nativeText(resource: String): String? {
            val resourceId = context.resources.getIdentifier(
                resource, "string", GMMP_PACKAGE
            )
            if (resourceId == 0) return null
            return runCatching {
                context.resources.getString(resourceId)
            }.getOrNull()
        }
        // GMMP 4.2.0 contains native translations for both "track" and
        // "auto_dj". "Titel-Mix" stays familiar in German; other player
        // languages use the player's own localized words.
        val label = TrackMixPlan.localizedMenuLabel(
            language = language,
            nativeTrack = nativeText("track"),
            nativeAutoDj = nativeText("auto_dj")
        )
        val confirmation = TrackMixPlan.localizedStartedMessage(
            language = language,
            menuLabel = label,
            gmmpStarted = nativeText("started")
        )
        val item = menu.add(
            Menu.NONE,
            MIX_ITEM_ID,
            nativeNext.order,
            lilacSparkleTitle(context, label)
        )
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        item.contentDescription = if (language == "de") {
            "Diesen Titel abspielen und GoneSmart Auto-DJ starten"
        } else if (language == "en") {
            "Play this song and start GoneSmart Auto-DJ"
        } else {
            // Accessibility labels must not default to an unrelated
            // language when GMMP has already translated the menu noun.
            label
        }
        item.setOnMenuItemClickListener {
            onMixClicked(context, name, menu, nativePlay, confirmation)
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
        nativePlay: MenuItem,
        confirmation: String
    ) {
        if (!enabled) return
        if (pending != null) {
            // Avoid stacking toasts if the user taps while the previous
            // native playback command is still pending.
            Log.i(TAG, "MIX SKIPPED | previous Track Mix still starting")
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
            confirmation = confirmation,
            createdAt = SystemClock.elapsedRealtime()
        )
        pending = request
        nativeToastSuppressionUntilMs = request.createdAt + 8_000L
        suppressedToastCount.set(0)

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
                var isolated: Snapshot? = null
                // Native queue callbacks and an already-active Auto-DJ can
                // overlap for a short moment after selecting a row from the
                // queue. Refills are held while CLEARING; use a bounded
                // three-attempt clear rather than failing on one unlucky
                // callback ordering. Abort immediately if the selected song
                // is no longer current.
                for (attempt in 1..3) {
                    if (!isCurrent(request)) break
                    val latest = queueSnapshot()
                    if (latest?.currentId != selectedId) break

                    Log.i(
                        TAG,
                        "MIX CLEAR ATTEMPT | attempt=$attempt | " +
                            "queueSize=${latest.ids.size}"
                    )
                    sendCommand(request.context, COMMAND_CLEAR_QUEUE)
                    isolated = awaitQueue(
                        request,
                        if (attempt == 1) 2_500L else 2_000L
                    ) {
                        TrackMixPlan.isSeedIsolated(
                            selectedId, loaded.ids, loaded.currentIndex,
                            it.ids, it.currentIndex
                        )
                    }
                    if (isolated != null) break

                    // Give native observers one short settle interval before
                    // another Clear. This is still well inside the bounded
                    // Track Mix startup window and does not create popups.
                    Thread.sleep(220L)
                }
                isolated
            }
            if (cleared == null) {
                val finalSnapshot = queueSnapshot()
                Log.w(
                    TAG,
                    "MIX CLEAR DIAG | selected=$selectedId | " +
                        "initialSize=${loaded.ids.size} | " +
                        "current=${finalSnapshot?.currentId} | " +
                        "finalSize=${finalSnapshot?.ids?.size} | " +
                        "currentIndex=${finalSnapshot?.currentIndex} | " +
                        "nativePlay=${request.nativeSource} | " +
                        "refillObserved=${request.refillObserved}"
                )
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
            nativeToastSuppressionUntilMs =
                SystemClock.elapsedRealtime() + 7_000L
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
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "Track Mix started, but the initial Auto-DJ queue " +
                        "could not be verified."
                )
                toast(request.context, "Track Mix started; Auto-DJ fill is still pending.")
            } else {
                Log.i(
                    TAG,
                    "MIX VERIFIED | initial=$initial | actual=${filled.ids.size} | " +
                        "currentPreserved=true | tracksAfterSeed=${filled.ids.size - 1}"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "Track Mix started: ${filled.ids.size - 1} " +
                        "tracks queued after the selected song."
                )
                // The user sees exactly one GoneSmart confirmation after
                // actual queue verification, while GMMP's intermediate
                // Play/Clear/Auto-DJ toasts are scoped out above.
                toast(request.context, request.confirmation)
                nativeToastSuppressionUntilMs =
                    SystemClock.elapsedRealtime() + 3_000L
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
            GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
            "Track Mix: $message"
        )
        toast(context, message)
    }

    private fun toast(context: Context, message: String) {
        main.post {
            val notification = Toast.makeText(
                context, message, Toast.LENGTH_SHORT
            )
            synchronized(ownToasts) {
                ownToasts[notification] = true
            }
            notification.show()
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
            editable.add(
                TrackMixPlan.insertionIndex(anchorIndex, editable.size),
                item
            )
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
