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
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Adds a GMMP-localized "track + Auto-DJ" action to single-song menus.
 * Calls the exact native Play action for the clicked row, keeps only its
 * currently playing native queue entry through GMMP's own atomic DAO
 * transaction, enables Auto-DJ and verifies GMMP's Initial Size.
 *
 * The track menu is installed independently of the Smart DJ switch so
 * choosing this action can turn Smart DJ on if previously disabled.
 */
internal class TrackMixController(
    private val enableSmartDj: (Context) -> Boolean,
    private val requestNativeRefill: (Int) -> Boolean,
    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },
    private val prepareSmartInitialPool: (Any, Long, Int) -> Int = { _, _, _ -> 0 }
) {
    companion object {
        private const val TAG = "GoneSmartTrackMix"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
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

        @Volatile
        private var activeManagedController:
            WeakReference<TrackMixController>? = null

        /**
         * QueueFlipController calls this only after GMMP itself naturally
         * changes the verified CURRENT position. It is an event signal, not
         * a polling loop and never discovers or invokes an unknown writer.
         */
        fun onVerifiedNaturalQueuePosition(
            autoDj: Any,
            currentPosition: Int?
        ) {
            activeManagedController?.get()
                ?.scheduleManagedRefillCheck(
                    autoDj = autoDj,
                    currentPosition = currentPosition
                )
        }
    }

    private data class Snapshot(
        val ids: List<Long>,
        val currentIndex: Int,
        val entryIds: List<Long> = emptyList()
    ) {
        val currentId: Long? get() = ids.getOrNull(currentIndex)
        val currentEntryId: Long? get() = entryIds.getOrNull(currentIndex)
    }

    private data class LoadedPlay(
        val snapshot: Snapshot,
        val selectedTrackId: Long
    )

    private data class Pending(
        val token: Long,
        val context: Context,
        val source: String,
        @Volatile var before: Snapshot?,
        val confirmation: String,
        val menuLabel: String,
        val createdAt: Long
    ) {
        @Volatile var nativePlaySignal = false
        @Volatile var nativePlayAccepted = false
        @Volatile var refillObserved = false
        @Volatile var transitionalRefillSuppressed = false
        val refillRequestedTracks = AtomicInteger(0)
        @Volatile var stage = "WAIT_PLAY"
        @Volatile var nativeSource = ""
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoneSmartTrackMix").apply { isDaemon = true }
    }
    private val tokens = AtomicLong()
    // Mutation barrier around seed isolation -> initial native fill. It is
    // armed before native Play, but 4.2.1 WAIT_PLAY continuity refills pass
    // through natively until GoneSmart actually owns CLEARING/FILLING.
    private val refillHold = AtomicBoolean(false)
    // The one refill explicitly requested by Track Mix must cross the same
    // native qr.z(count) hook while the global hold stays armed. Scope that
    // exception to the calling worker thread so unrelated GMMP refills remain
    // suppressed until Initial Size has been verified.
    private val nativeRefillAllowance = ThreadLocal<Int>()
    private val events = GoneSmartRuntimeReporter()
    private val settings = GmmpAutoDjSettingsReader()
    private val queueReader = GmmpQueueReader()

    @Volatile private var pending: Pending? = null
    @Volatile private var nativeQueue: WeakReference<Any>? = null
    @Volatile private var nativeAutoDj: WeakReference<Any>? = null
    // 4.2.1 no longer exposes the verified 4.2.0 append-position allocator.
    // Scope the bounded native-DAO position repair only to a Track Auto-DJ
    // session we created; unrelated native list Play disarms it.
    @Volatile private var managedAutoDj: WeakReference<Any>? = null
    private val managedRefillInFlight = AtomicBoolean(false)
    @Volatile private var lastManagedObservedPosition = Int.MIN_VALUE

    @Volatile private var enabled = true

    // Only this feature's short native Play/Clear/Auto-DJ transition may
    // silence GMMP's intermediate status toasts. Never hide unrelated
    // GMMP notifications outside that window or our own confirmation.
    @Volatile private var nativeToastSuppressionUntilMs = 0L
    private val ownToasts = WeakHashMap<Toast, Boolean>()
    private val suppressedToastCount = AtomicLong()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            disarmManagedAutoDjSession("feature-disabled")
        }
        Log.i(TAG, "MIX SETTINGS | enabled=$value")
    }

    fun nativeRefillAction(): TrackMixInitialFillPolicy.NativeRefillAction {
        val request = pending
        val explicitAllowance = (nativeRefillAllowance.get() ?: 0) > 0
        val legacyQueue = nativeAutoDj?.get()?.let { autoDj ->
            field(autoDj, "q")?.javaClass?.name == "ex3"
        } == true || nativeQueue?.get()?.javaClass?.name == "ex3"
        val action = TrackMixInitialFillPolicy.nativeRefillAction(
            holdActive = refillHold.get(),
            explicitAllowance = explicitAllowance,
            stage = request?.stage,
            legacyQueue = legacyQueue
        )
        if (action == TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS) {
            if (request?.stage == "FILLING") {
                request.transitionalRefillSuppressed = true
            }
            Log.i(
                TAG,
                "MIX AUTO-DJ HOLD | stage=${request?.stage ?: "pre-play"} | " +
                    "legacyQueue=$legacyQueue | deferring native refill"
            )
        }
        return action
    }

    private fun requestHeldNativeRefill(count: Int): Boolean {
        if (count <= 0) return true
        val previous = nativeRefillAllowance.get() ?: 0
        nativeRefillAllowance.set(previous + 1)
        return try {
            requestNativeRefill(count)
        } finally {
            if (previous == 0) {
                nativeRefillAllowance.remove()
            } else {
                nativeRefillAllowance.set(previous)
            }
        }
    }

    fun isExplicitInitialRefill(): Boolean =
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

    fun rebaseManagedQueueAfterInitialFill(autoDj: Any): Boolean {
        if (managedAutoDj?.get() !== autoDj) return false
        return runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = nativePositionWriterProvider(autoDj)
            ).rebaseQueueToOneIfNeeded()
        }.onFailure {
            Log.e(
                TAG,
                "MIX QUEUE REBASE | final 4.2.1 Initial Size rebase failed",
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
            managedRefillInFlight.set(false)
            lastManagedObservedPosition = Int.MIN_VALUE
            activeManagedController = WeakReference(this)
            Log.i(TAG, "MIX CONTINUITY | managed 4.2.1 Auto-DJ session armed")
        }
    }

    private fun disarmManagedAutoDjSession(reason: String) {
        val wasActive = managedAutoDj?.get() != null
        managedAutoDj = null
        managedRefillInFlight.set(false)
        lastManagedObservedPosition = Int.MIN_VALUE
        if (activeManagedController?.get() === this) {
            activeManagedController = null
        }
        if (wasActive) {
            Log.i(TAG, "MIX CONTINUITY | disarmed | reason=$reason")
        }
    }

    private fun scheduleManagedRefillCheck(
        autoDj: Any,
        currentPosition: Int?
    ) {
        val position = currentPosition ?: return
        if (position <= 0) return
        if (managedAutoDj?.get() !== autoDj) return
        if (pending != null || refillHold.get()) return

        synchronized(this) {
            if (managedAutoDj?.get() !== autoDj) return
            if (lastManagedObservedPosition == position) return
            lastManagedObservedPosition = position
        }

        work.execute {
            if (managedAutoDj?.get() !== autoDj) return@execute
            if (pending != null || refillHold.get()) return@execute
            if (!managedRefillInFlight.compareAndSet(false, true)) return@execute

            try {
                val context = queueReader.read(autoDj, position)
                    ?: return@execute
                val ordered = context.items.sortedBy { it.queuePosition }
                val currentIndex = ordered.indexOfFirst {
                    it.state == QueueItemState.CURRENT
                }
                if (currentIndex < 0) return@execute

                val actualPositions = ordered.map { it.queuePosition }
                val expectedPositions = actualPositions.firstOrNull()?.let { first ->
                    List(ordered.size) { offset -> first + offset }
                } ?: emptyList()
                if (actualPositions != expectedPositions) {
                    Log.w(
                        TAG,
                        "MIX CONTINUITY | skip refill check; queue not normalized | " +
                            "positions=" + actualPositions.joinToString(",")
                    )
                    return@execute
                }

                val nativeSettings = settings.read()
                val requested = TrackAutoDjContinuationPolicy.refillCount(
                    queueSize = ordered.size,
                    currentIndex = currentIndex,
                    upcomingTrackCount = nativeSettings.upcomingTrackCount
                )
                if (requested <= 0) return@execute

                val remaining = ordered.size - currentIndex - 1
                Log.i(
                    TAG,
                    "MIX CONTINUITY REFILL | currentPosition=" +
                        ordered[currentIndex].queuePosition +
                        " | queueSize=" + ordered.size +
                        " | remaining=$remaining" +
                        " | upcoming=${nativeSettings.upcomingTrackCount}" +
                        " | requested=$requested" +
                        " | boundary=native-qr.z"
                )

                if (!requestNativeRefill(requested)) {
                    Log.w(
                        TAG,
                        "MIX CONTINUITY REFILL | native request rejected | " +
                            "requested=$requested"
                    )
                }
            } catch (failure: Throwable) {
                Log.e(
                    TAG,
                    "MIX CONTINUITY REFILL | managed refill check failed",
                    failure
                )
            } finally {
                managedRefillInFlight.set(false)
            }
        }
    }

    private fun awaitAutoDjCommandRefillBoundary(request: Pending) {
        val legacyQueue = nativeAutoDj?.get()?.let { autoDj ->
            field(autoDj, "q")?.javaClass?.name == "ex3"
        } == true
        val waitMs = TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(
            legacyQueue = legacyQueue
        )
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (
            isCurrent(request) &&
            !request.transitionalRefillSuppressed &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(45)
        }
        Log.i(
            TAG,
            "MIX AUTO-DJ ARM | transitionalRefillSuppressed=" +
                request.transitionalRefillSuppressed +
                " | legacyQueue=$legacyQueue | waitMs=$waitMs"
        )
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
        val request = pending
        if (request == null) {
            disarmManagedAutoDjSession("native-queue-replaced:$origin")
            return
        }
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
            val total = request.refillRequestedTracks.addAndGet(
                requested.coerceAtLeast(0)
            )
            Log.i(
                TAG,
                "MIX NATIVE REFILL | requested=$requested | " +
                    "totalRequested=$total"
            )
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
        // Use both terms from GMMP native resources in every locale.
        val label = TrackMixPlan.localizedMenuLabel(
            language = language,
            nativeTrack = nativeText("track"),
            nativeAutoDj = nativeText("auto_dj")
        )
        if (label.isBlank()) {
            Log.w(TAG, "MIX I18N | GMMP track/Auto-DJ resources unavailable; native menu untouched")
            return
        }
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
        item.contentDescription = label
        item.setOnMenuItemClickListener {
            onMixClicked(context, name, menu, nativePlay, confirmation, label)
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
        confirmation: String,
        menuLabel: String
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

        // Capture the pre-Play identity on the worker. The old implementation
        // waited up to 350 ms on the UI thread for this Room read, which made
        // the context-menu click visibly stall. Keep the ordering proof, but
        // never block GMMP's main thread for it.
        val nativePlayDispatch: () -> Boolean = runCatching {
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
            Log.e(TAG, "Could not capture native track Play dispatcher", error)
            ({ false })
        }

        val request = Pending(
            token = tokens.incrementAndGet(),
            context = context,
            source = source,
            before = null,
            confirmation = confirmation,
            menuLabel = menuLabel,
            createdAt = SystemClock.elapsedRealtime()
        )
        request.stage = "PREPARE"
        pending = request

        work.execute {
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
                    if (pending === request) pending = null
                    refillHold.set(false)
                    fail("Smart DJ could not be enabled.", context)
                    return@post
                }

                val started = runCatching {
                    nativePlayDispatch()
                }.onFailure { Log.e(TAG, "Native track Play failed", it) }
                    .getOrDefault(false)

                if (!started) {
                    if (pending === request) pending = null
                    refillHold.set(false)
                    fail("Could not start this song.", context)
                    return@post
                }

                request.nativePlayAccepted = true
                Log.i(TAG, "MIX START | menu=$source | nativePlay=dispatched")
                work.execute { runTrackMix(request) }
            }
        }
    }

    private fun runTrackMix(request: Pending) {
        var managedSessionReady = false
        try {
            // Native Play may first expose a temporary queue and then replace
            // it with the fully materialized source list. Keep the originally
            // detected selected track across that native rebuild instead of
            // retargeting Track Mix to whichever row temporarily becomes
            // CURRENT while a large Smart Playlist is populated.
            val loadedPlay = waitForSelectedSong(request) ?: run {
                fail("The selected song did not start.", request.context)
                return
            }
            if (!isCurrent(request)) return
            val loaded = loadedPlay.snapshot
            val selectedId = loadedPlay.selectedTrackId

            val nativeSettings = settings.read()
            val initial = nativeSettings.initialQueueSize.coerceAtLeast(1)
            val upcoming = nativeSettings.upcomingTrackCount
            val legacyQueue =
                nativeAutoDj?.get()?.let { autoDj ->
                    field(autoDj, "q")?.javaClass?.name == "ex3"
                } == true || nativeQueue?.get()?.javaClass?.name == "ex3"
            val plannedMissing =
                TrackMixInitialFillPolicy.nativeInitialRefillCount(
                    initialSize = initial,
                    seedQueueSize = 1
                )

            // 4.2.1: prepare recommendations for exactly the selected Current
            // track while GMMP's original source queue is still intact. The
            // provider/local-match pipeline may take seconds, but current+1
            // remains native and playable during this whole phase. No queue
            // row is consumed merely to warm the GoneSmart pool.
            var preparedSmartCount = 0
            if (!legacyQueue && plannedMissing > 0) {
                request.stage = "PREWARM"
                val autoDj = nativeAutoDj?.get()
                preparedSmartCount = if (autoDj != null) {
                    prepareSmartInitialPool(
                        autoDj,
                        selectedId,
                        plannedMissing
                    ).coerceIn(0, plannedMissing)
                } else {
                    0
                }
                Log.i(
                    TAG,
                    "MIX PRE-CLEAR SMART POOL | needed=$plannedMissing | " +
                        "prepared=$preparedSmartCount | selected=$selectedId"
                )

                // Prime GMMP's own Auto-DJ candidate machinery before the
                // destructive clear as well. This explicit qr.z(1) is forced
                // through as native-only and may append one disposable row to
                // the still-safe source queue; it does not consume GoneSmart's
                // prepared pool. Once it returns, both the native candidate DB
                // and the smart pool are ready before current+1 is removed.
                request.stage = "PRIME_NATIVE"
                val primeCount =
                    TrackMixInitialFillPolicy.preClearNativePrimeRefillCount(
                        totalMissing = plannedMissing,
                        legacyQueue = legacyQueue
                    )
                if (primeCount > 0 && !requestHeldNativeRefill(primeCount)) {
                    fail("Could not prepare Auto-DJ for this song.", request.context)
                    return
                }
                Log.i(
                    TAG,
                    "MIX PRE-CLEAR NATIVE PRIME | requested=$primeCount | " +
                        "ready=${primeCount == 0 || isCurrent(request)}"
                )
            }

            if (!isCurrent(request)) return
            request.stage = "CLEARING"
            val cleared = if (
                loaded.ids.size == 1 &&
                loaded.currentId == selectedId &&
                legacyQueue
            ) {
                loaded
            } else {
                // 4.2.1 uses GMMP's public CLEAR_QUEUE boundary; legacy 4.2.0
                // keeps its already accepted native transaction. Either way,
                // the selected playing track must remain the sole Current row.
                isolateNativeSeed(request, selectedId)
            }
            if (cleared == null) {
                val finalSnapshot = queueSnapshot()
                Log.w(
                    TAG,
                    "MIX ISOLATE DIAG | selected=$selectedId | " +
                        "initialSize=${loaded.ids.size} | " +
                        "current=${finalSnapshot?.currentId} | " +
                        "finalSize=${finalSnapshot?.ids?.size} | " +
                        "currentIndex=${finalSnapshot?.currentIndex} | " +
                        "nativePlay=${request.nativeSource} | " +
                        "refillObserved=${request.refillObserved}"
                )
                fail("Could not prepare the selected song for Auto-DJ.", request.context)
                return
            }
            Log.i(
                TAG,
                "MIX SEED | queueSize=${cleared.ids.size} | " +
                    "currentPreserved=true | preparedSmart=$preparedSmartCount"
            )
            armManagedAutoDjSession()

            request.stage = "FILLING"

            // GMMP's AUTO_DJ command owns enabling the native mode. Its normal
            // `upcoming` refill is suppressed inside this short Track-Mix-owned
            // window because it is not the Initial Size contract. The native
            // candidate machinery was primed before CLEAR_QUEUE, so after the
            // bounded command boundary the explicit refill can populate
            // current+1 immediately instead of racing the decoder.
            request.transitionalRefillSuppressed = false
            sendCommand(request.context, COMMAND_AUTO_DJ)
            nativeToastSuppressionUntilMs =
                SystemClock.elapsedRealtime() + 7_000L
            awaitAutoDjCommandRefillBoundary(request)

            val totalInitialRequest =
                TrackMixInitialFillPolicy.nativeInitialRefillCount(
                    initialSize = initial,
                    seedQueueSize = cleared.ids.size
                )
            val smartInitialRequest =
                minOf(totalInitialRequest, preparedSmartCount)
            val nativeRemainder =
                (totalInitialRequest - smartInitialRequest).coerceAtLeast(0)

            Log.i(
                TAG,
                "MIX AUTO-DJ | command=sent | initialSize=$initial | " +
                    "upcoming=$upcoming | seed=$selectedId"
            )
            Log.i(
                TAG,
                "MIX INITIAL FILL | initial=$initial | " +
                    "seedSize=${cleared.ids.size} | totalMissing=$totalInitialRequest | " +
                    "smartPrepared=$smartInitialRequest | nativeRemainder=$nativeRemainder | " +
                    "legacy=$legacyQueue"
            )

            // If at least one selected-seed match was prepared, insert those
            // rows first. Position 2 therefore uses GoneSmart ranking. Only a
            // genuine shortfall is then allowed to fall through to native
            // GMMP selection. Both requests cross the same proven qr.z writer.
            val smartAccepted =
                requestHeldNativeRefill(smartInitialRequest)
            val remainderAccepted = if (smartAccepted) {
                requestHeldNativeRefill(nativeRemainder)
            } else {
                false
            }
            val fillRequestAccepted = smartAccepted && remainderAccepted
            var filled = if (!fillRequestAccepted) {
                Log.w(TAG, "MIX INITIAL FILL | native refill request failed")
                null
            } else {
                awaitQueue(request, 65_000L) {
                    it.currentId == selectedId &&
                        it.ids.size == initial
                }
            }

            if (filled != null && !legacyQueue) {
                val autoDj = nativeAutoDj?.get()
                val rebased = autoDj != null &&
                    rebaseManagedQueueAfterInitialFill(autoDj)
                if (rebased) {
                    filled = queueSnapshot()?.takeIf { snapshot ->
                        snapshot.currentId == selectedId &&
                            snapshot.ids.size == initial
                    } ?: filled
                    Log.i(
                        TAG,
                        "MIX INITIAL REBASE | queue=1..$initial | " +
                            "currentEntryPreserved=true"
                    )
                } else {
                    Log.w(
                        TAG,
                        "MIX INITIAL REBASE | skipped/failed; " +
                            "verified queue retained without destructive fallback"
                    )
                }
            }

            // Initial Size is now verified (or the bounded fill failed). Only
            // after this point may GMMP resume its ordinary upcoming refills.
            refillHold.set(false)

            if (!isCurrent(request)) return
            if (filled == null) {
                val current = queueSnapshot()
                if (current != null &&
                    current.currentId == selectedId &&
                    current.ids.size == initial
                ) {
                    filled = current
                }
            }
            if (filled == null) {
                Log.w(
                    TAG,
                    "MIX INCOMPLETE | initial=$initial | " +
                        "actual=${queueSnapshot()?.ids?.size ?: -1}"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "${request.menuLabel} started, but the initial Auto-DJ queue " +
                        "could not be verified."
                )
                toast(request.context, request.confirmation)
            } else {
                managedSessionReady = true
                Log.i(
                    TAG,
                    "MIX VERIFIED | initial=$initial | actual=${filled.ids.size} | " +
                        "currentPreserved=true | tracksAfterSeed=${filled.ids.size - 1}"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "${request.menuLabel}: ${filled.ids.size - 1} " +
                        "tracks queued after the selected song."
                )
                toast(request.context, request.confirmation)
                nativeToastSuppressionUntilMs =
                    SystemClock.elapsedRealtime() + 3_000L
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Track Mix failed", failure)
            fail("Could not complete ${request.menuLabel}.", request.context)
        } finally {
            refillHold.set(false)
            request.stage = "DONE"
            if (pending === request) pending = null
            if (!managedSessionReady) {
                disarmManagedAutoDjSession("track-mix-not-verified")
            }
        }
    }

    /**
     * Deterministic Queue isolation via GMMP 4.2.0 native ex3.c(uq1),
     * xx3.O(ey3[]) and xx3.O0(ArrayList). Unlike CLEAR_QUEUE broadcast,
     * the delete and current-row update happen in one Room transaction.
     * The selected native queue_id and currently playing track both stay
     * unchanged. All operations run on Track Mix's worker (never UI).
     */
    private fun isolateNativeSeed(
        request: Pending,
        selectedTrackId: Long
    ): Snapshot? {
        nativeAutoDj?.get()?.let { autoDj ->
            val queue = field(autoDj, "q")
            if (queue?.javaClass?.name != "ex3") {
                val isolated = runCatching {
                    GmmpQueueMutationBridge(
                        autoDj = autoDj,
                        verifiedPositionWriter = nativePositionWriterProvider(autoDj)
                    ).isolateCurrentTrack(selectedTrackId)
                }.onFailure {
                    Log.e(
                        TAG,
                        "MIX ISOLATE | 4.2.1 native DAO bridge failed",
                        it
                    )
                }.getOrDefault(false)
                return if (isolated) {
                    queueSnapshot()?.takeIf {
                        it.ids.size == 1 &&
                            it.currentId == selectedTrackId
                    } ?: Snapshot(listOf(selectedTrackId), 0)
                } else null
            }
        }

        val queue = nativeQueue?.get()
            ?.takeIf { it.javaClass.name == "ex3" }
            ?: nativeAutoDj?.get()?.let { field(it, "q") }
                ?.takeIf { it.javaClass.name == "ex3" }
            ?: run {
                Log.e(TAG, "MIX ISOLATE | legacy ex3 queue not captured")
                return null
            }
        val dao = field(queue, "r") ?: return null
        val read = dao.javaClass.getMethod("H1")
        val position = queue.javaClass.getDeclaredMethod("D").apply {
            isAccessible = true
        }
        val positionField = queue.javaClass.getDeclaredField("p").apply {
            isAccessible = true
        }
        fun entries(): List<Any> =
            ((read.invoke(dao) as? List<*>)
                ?: error("Native queue cannot be read"))
                .filterNotNull()
                .sortedBy { (field(it, "a") as Number).toInt() }

        fun refs(rows: List<Any>): List<TrackMixPlan.NativeQueueEntry> =
            rows.map {
                TrackMixPlan.NativeQueueEntry(
                    queueId = (field(it, "d") as Number).toLong(),
                    trackId = (field(it, "b") as Number).toLong(),
                    position = (field(it, "a") as Number).toInt()
                )
            }

        val originals = entries()
        val before = refs(originals)
        val originalPosition = position.invoke(queue) as? Int ?: return null
        val plan = TrackMixPlan.planNativeIsolation(
            before, originalPosition, selectedTrackId
        )
        if (plan.removeEntryIds.isEmpty()) {
            Log.i(TAG, "MIX ISOLATE | selected queue entry already alone")
            return Snapshot(
                listOf(selectedTrackId),
                0,
                listOf(plan.selectedEntryId)
            )
        }

        val entryType = originals.first().javaClass
        val stale = originals.filter {
            (field(it, "d") as Number).toLong() != plan.selectedEntryId
        }
        val staleArray = java.lang.reflect.Array.newInstance(
            entryType, stale.size
        )
        stale.forEachIndexed { index, row ->
            java.lang.reflect.Array.set(staleArray, index, row)
        }

        val nativeInterface = queue.javaClass.classLoader!!.loadClass("uq1")
        val nativeUnitClass = queue.javaClass.classLoader!!.loadClass("uf5")
        val nativeUnit = nativeUnitClass.getDeclaredField("a").apply {
            isAccessible = true
        }.get(null)
        var mutationError: Throwable? = null
        var mutationPerformed = false

        val callback = Proxy.newProxyInstance(
            queue.javaClass.classLoader,
            arrayOf(nativeInterface)
        ) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    try {
                        // A competing Play/Flip/refill between our first
                        // snapshot and transaction must not be overwritten.
                        check(isCurrent(request)) {
                            "Track Auto-DJ action was superseded"
                        }
                        check(refs(entries()) == before &&
                            (position.invoke(queue) as? Int) ==
                                originalPosition) {
                            "Native queue changed before isolation"
                        }
                        dao.javaClass.getMethod(
                            "O", Array<Any>::class.java
                        ).invoke(dao, staleArray)
                        val selected = originals.first {
                            (field(it, "d") as Number).toLong() ==
                                plan.selectedEntryId
                        }
                        selected.javaClass.getDeclaredField("a").apply {
                            isAccessible = true
                        }.setInt(selected, 1)
                        dao.javaClass.getMethod(
                            "O0", List::class.java
                        ).invoke(dao, arrayListOf(selected))
                        mutationPerformed = true
                    } catch (error: Throwable) {
                        mutationError = error
                        throw error
                    }
                    nativeUnit
                }
                "toString" -> "GoneSmart native queue isolation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }

        // ex3.c invokes the callback inside its native Room transaction;
        // GMMP catches callback exceptions internally, so track failure
        // explicitly instead of accidentally treating it as success.
        queue.javaClass.getDeclaredMethod(
            "c", nativeInterface
        ).apply { isAccessible = true }.invoke(queue, callback)
        mutationError?.let { throw it }
        check(mutationPerformed) {
            "Native queue transaction did not execute"
        }

        val result = entries()
        val after = refs(result)
        check(after.size == 1 &&
            after[0].queueId == plan.selectedEntryId &&
            after[0].trackId == selectedTrackId &&
            after[0].position == 1) {
            "Native queue isolation did not preserve the selected entry"
        }
        // ex3.p is GMMP's queue position allocator used by ex3.t/w.
        // Keep it in sync before re-enabling Auto-DJ, so newly generated
        // tracks begin at position 2 rather than leaving position gaps.
        positionField.setInt(queue, 1)
        queue.javaClass.getDeclaredMethod(
            "b2", Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(queue, 1)
        check((position.invoke(queue) as? Int) == 1) {
            "Native playback pointer did not move with the selected song"
        }
        Log.i(
            TAG,
            "MIX ISOLATED | method=native-transaction | " +
                "removed=${plan.removeEntryIds.size} | " +
                "selectedEntryId=${plan.selectedEntryId} | " +
                "oldPosition=$originalPosition | newPosition=1 | " +
                "verified=true"
        )
        return Snapshot(
            listOf(selectedTrackId),
            0,
            listOf(plan.selectedEntryId)
        )
    }

    private fun waitForSelectedSong(request: Pending): LoadedPlay? {
        var targetIdentity: TrackMixPlaybackIdentityPolicy.Identity? = null
        var detectedAt = 0L
        var detectedQueueSize = -1
        var stableSnapshot: Snapshot? = null
        var stableAt = 0L
        val deadline = SystemClock.elapsedRealtime() +
            TrackMixQueueSettlingPolicy.WAIT_TIMEOUT_MS
        val completionGuard =
            TrackMixQueueSettlingPolicy.requiredCompletionGuardMs(
                request.source
            )

        fun sameQueue(previous: Snapshot, current: Snapshot): Boolean =
            TrackMixQueueSettlingPolicy.sameQueue(
                previousTrackIds = previous.ids,
                previousEntryIds = previous.entryIds,
                previousCurrentIndex = previous.currentIndex,
                currentTrackIds = current.ids,
                currentEntryIds = current.entryIds,
                currentCurrentIndex = current.currentIndex
            )

        while (isCurrent(request) && SystemClock.elapsedRealtime() < deadline) {
            // Before selection is proven, sample quickly enough to catch the
            // native Play transition. Afterwards the passively verified
            // position writer owns CURRENT, so a slower structural cadence is
            // sufficient and avoids repeatedly materializing a large queue.
            Thread.sleep(if (targetIdentity == null) 160L else 400L)
            val current = queueSnapshot() ?: continue
            val currentTrack = current.currentId ?: continue
            val identity = TrackMixPlaybackIdentityPolicy.Identity(
                queueEntryId = current.currentEntryId,
                trackId = currentTrack,
                currentIndex = current.currentIndex
            )
            val before = request.before?.currentId?.let { beforeTrack ->
                TrackMixPlaybackIdentityPolicy.Identity(
                    queueEntryId = request.before?.currentEntryId,
                    trackId = beforeTrack,
                    currentIndex = request.before?.currentIndex ?: -1
                )
            }
            val changed =
                TrackMixPlaybackIdentityPolicy.changed(before, identity)
            val playbackChanged =
                TrackMixPlaybackIdentityPolicy.playbackChanged(
                    before,
                    identity
                )
            val now = SystemClock.elapsedRealtime()

            if (targetIdentity == null && playbackChanged) {
                targetIdentity = identity
                detectedAt = now
                detectedQueueSize = current.ids.size
                stableSnapshot = current
                stableAt = now
                Log.i(
                    TAG,
                    "MIX PLAY DETECTED | source=queue-current-change" +
                        " | entry=" + (identity.queueEntryId ?: -1L) +
                        " | track=" + identity.trackId +
                        " | queueSize=" + current.ids.size +
                        " | completionGuardMs=" + completionGuard
                )
                continue
            }

            if (targetIdentity == null &&
                TrackMixPlaybackIdentityPolicy.acceptSameCurrentQueuePlay(
                    source = request.source,
                    nativePlayAccepted = request.nativePlayAccepted,
                    nativePlaySignal = request.nativePlaySignal,
                    before = before,
                    current = identity,
                    stableMs = now - request.createdAt,
                    actionAgeMs = now - request.createdAt
                )
            ) {
                targetIdentity = identity
                detectedAt = now
                detectedQueueSize = current.ids.size
                stableSnapshot = current
                stableAt = now
                Log.i(
                    TAG,
                    "MIX PLAY DETECTED | source=current-queue-replay" +
                        " | entry=" + (identity.queueEntryId ?: -1L) +
                        " | track=" + identity.trackId +
                        " | queueSize=" + current.ids.size +
                        " | completionGuardMs=" + completionGuard
                )
                continue
            }

            val target = targetIdentity ?: continue
            val currentStillSelected =
                TrackMixPlaybackIdentityPolicy.sameCurrent(target, identity)
            val selectedOccurrences = current.ids.count {
                it == target.trackId
            }
            val targetUsable =
                TrackMixQueueSettlingPolicy.selectedTargetStillUsable(
                    selectedTrackOccurrences = selectedOccurrences,
                    currentStillSelected = currentStillSelected,
                    detectedQueueSize = detectedQueueSize,
                    currentQueueSize = current.ids.size
                )

            if (!targetUsable) {
                val sinceDetection = now - detectedAt
                val mayRetarget =
                    TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                        source = request.source,
                        sinceDetectionMs = sinceDetection,
                        requiredGuardMs = completionGuard,
                        selectedTrackOccurrences = selectedOccurrences,
                        currentStillSelected = currentStillSelected,
                        playbackChangedFromBefore = playbackChanged
                    )
                if (mayRetarget) {
                    Log.i(
                        TAG,
                        "MIX PLAY RETARGETED | reason=transient-current-disappeared" +
                            " | oldEntry=" + (target.queueEntryId ?: -1L) +
                            " | oldTrack=" + target.trackId +
                            " | newEntry=" + (identity.queueEntryId ?: -1L) +
                            " | newTrack=" + identity.trackId +
                            " | sinceDetectionMs=" + sinceDetection
                    )
                    targetIdentity = identity
                    detectedAt = now
                    detectedQueueSize = current.ids.size
                    stableSnapshot = current
                    stableAt = now
                    continue
                }

                // Outside the guarded native Play settling window, a current
                // row change is treated as a competing/manual playback change.
                // Never retarget there and never isolate an unrelated row.
                stableSnapshot = null
                stableAt = now
                continue
            }

            val previous = stableSnapshot
            if (previous == null || !sameQueue(previous, current)) {
                stableSnapshot = current
                stableAt = now
                continue
            }

            val stableFor = now - stableAt
            val sinceDetection = now - detectedAt
            if (TrackMixQueueSettlingPolicy.isSettled(
                    sameQueue = true,
                    stableForMs = stableFor,
                    sinceDetectionMs = sinceDetection,
                    requiredGuardMs = completionGuard
                )
            ) {
                Log.i(
                    TAG,
                    "MIX PLAY VERIFIED | source=queue-settled" +
                        " | targetEntry=" + (target.queueEntryId ?: -1L) +
                        " | targetTrack=" + target.trackId +
                        " | currentNow=" + currentTrack +
                        " | queueSize=" + current.ids.size +
                        " | stableMs=" + stableFor +
                        " | sinceDetectionMs=" + sinceDetection +
                        " | rebuilt=" +
                        (current.ids.size != detectedQueueSize) +
                        " | nativeSignal=" + request.nativePlaySignal +
                        " | changed=" + changed
                )
                return LoadedPlay(
                    snapshot = current,
                    selectedTrackId = target.trackId
                )
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
        nativeAutoDj?.get()?.let { autoDj ->
            // Once Queue Flip has passively proved GMMP's real writable
            // queue-position boundary, reuse that exact native readback here.
            // This is stronger and much cheaper than repeatedly rediscovering
            // CURRENT from every integer-looking getter while native Play is
            // rebuilding a list. A missing proof remains a retryable miss and
            // never falls through to the 4.2.0 ex3 shape.
            val positionWriter = nativePositionWriterProvider(autoDj)
            val context = queueReader.read(
                autoDj,
                positionWriter?.read()
            ) ?: return@runCatching null
            val currentIndex = context.items.indexOfFirst {
                it.state == QueueItemState.CURRENT
            }
            if (currentIndex < 0) return@runCatching null
            return@runCatching Snapshot(
                context.items.map { it.track.id },
                currentIndex,
                context.items.map { it.queueEntryId }
            )
        }

        // Verified 4.2.0 fallback only. Never reinterpret a 4.2.1 Auto-DJ
        // field (for example q, which may be byte[]) as this legacy wrapper.
        val queue = nativeQueue?.get()
            ?.takeIf { it.javaClass.name == "ex3" }
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
        val entryIds = sorted.map {
            (field(it, "d") as Number).toLong()
        }
        val current = queue.javaClass.getDeclaredMethod("D")
            .apply { isAccessible = true }
            .invoke(queue) as? Int ?: return@runCatching null
        val currentIndex = sorted.indexOfFirst {
            (field(it, "a") as Number).toInt() == current
        }
        Snapshot(ids, currentIndex, entryIds)
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
            "${pending?.menuLabel ?: "Auto-DJ"}: $message"
        )
        toast(
            context,
            NativeGmmpUiText.error(
                context,
                pending?.menuLabel ?: NativeGmmpUiText.string(context, "auto_dj")
            )
        )
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
