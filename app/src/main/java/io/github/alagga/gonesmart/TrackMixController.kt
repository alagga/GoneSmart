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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

internal data class TrackMixPreparedInitialPool(
    val sessionId: Long,
    val trackIds: List<Long>
) {
    companion object {
        val EMPTY = TrackMixPreparedInitialPool(-1L, emptyList())
    }
}

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
    private val requestNativeRefill: (Any, Int) -> Boolean,
    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },
    private val nativeTrackMixPositionWriterProvider: (Any, Int) -> NativeQueuePositionWriter? =
        { _, _ -> null },
    private val resolvePrePlayTarget: (Any, List<Any>) -> TrackMixPrePlayTargetResolution? =
        { _, _ -> null },
    private val preparePrePlaySmartPool: (Any, TrackInfo, Int) -> TrackMixPreparedInitialPool =
        { _, _, _ -> TrackMixPreparedInitialPool.EMPTY },
    private val ensureZeroPoolSourceBatchHooks: (Any) -> Boolean = { false },
    private val resetRecommendationSession: (Any, Long, Long) -> Boolean =
        { _, _, _ -> true }
) {
    companion object {
        private const val TAG = "GoneSmartTrackMix"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val COMMAND_AUTO_DJ = "gonemad.gmmp.command.AUTO_DJ"
        private const val MIX_ITEM_ID = 0x47534D01
        private const val FILE_BROWSER_MENU = "menu_gm_context_file_browser"
        // These GMMP 4.2.0 menu XMLs all have native Play and Play next.
        // Contexts that represent whole albums/artists/playlists are excluded.
        private val SONG_MENUS = setOf(
            "menu_gm_context_track",
            "menu_gm_context_queue",
            "menu_gm_context_search",
            "menu_gm_context_playlist_details",
            FILE_BROWSER_MENU,
            "menu_gm_context_shared"
        )

        @Volatile
        private var activeManagedController:
            WeakReference<TrackMixController>? = null

        @Volatile
        private var activePendingController:
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
            activePendingController?.get()
                ?.prepareZeroPoolAtNaturalCurrentBoundary(
                    autoDj = autoDj,
                    currentPosition = currentPosition
                )
            activeManagedController?.get()
                ?.scheduleManagedRefillCheck(
                    autoDj = autoDj,
                    currentPosition = currentPosition
                )
        }

        fun onNaturalStatePositionCandidate(
            autoDj: Any,
            observedArgument: Int
        ) {
            activePendingController?.get()
                ?.prepareZeroPoolFromNaturalStateCandidate(
                    autoDj = autoDj,
                    observedArgument = observedArgument
                )
        }
    }

    private data class Snapshot(
        val ids: List<Long>,
        val currentIndex: Int,
        val entryIds: List<Long> = emptyList(),
        val currentResolutionSource: String? = null,
        val currentQueuePosition: Int? = null
    ) {
        val currentId: Long? get() = ids.getOrNull(currentIndex)
        val currentEntryId: Long? get() = entryIds.getOrNull(currentIndex)
    }

    private data class LoadedPlay(
        val snapshot: Snapshot,
        val selectedTrackId: Long
    )

    private data class MenuSelectionContext(
        val callback: Any?,
        val popup: Any?,
        val listener: Any?,
        val roots: List<Any>
    )

    internal data class ZeroPoolNativeRefillBoundary(
        val token: Long,
        val selectedTrackId: Long,
        val selectedEntryId: Long,
        val currentPosition: Int,
        val initialSize: Int,
        val originalRequested: Int,
        val topUpCount: Int
    )

    private data class ObservedNativePositionCommand(
        val key: String,
        val service: WeakReference<Any>,
        val method: java.lang.reflect.Method,
        val value: Int,
        val looper: Looper?,
        val observedAt: Long
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
        @Volatile var prePlayTarget: TrackInfo? = null
        @Volatile var prePlayPrepared: TrackMixPreparedInitialPool = TrackMixPreparedInitialPool.EMPTY
        @Volatile var prePlayPreparationComplete = false
        @Volatile var deferredNoMatchNotice = false
        @Volatile var noMatchNoticeShown = false
        @Volatile var prePlayInitialSize = 0
        @Volatile var verifiedCurrentQueueEntryId: Long? = null
        @Volatile var bootstrapAppliedTrackIds: List<Long> = emptyList()
        @Volatile var bootstrapRefillObserved = false
        val nativePositionCommands = CopyOnWriteArrayList<ObservedNativePositionCommand>()
        @Volatile var provisionalCurrentQueuePosition: Int? = null
        @Volatile var nextSourceBoundary: ObservedNativePositionCommand? = null
        @Volatile var nextSourceBoundaryAmbiguous = false
        @Volatile var zeroPoolBoundaryAttempted = false
        @Volatile var zeroPoolBoundaryPrepared = false
        @Volatile var zeroPoolNativePlayListNarrowed = false
        @Volatile var zeroPoolNativeFallbackTrackIds: List<Long> = emptyList()
        @Volatile var zeroPoolNextSourceGuardAttempted = false
        @Volatile var zeroPoolNextSourceGuardApplied = false
        @Volatile var zeroPoolSourceBatchGuardApplied = false
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
    private val fileBrowserMenuResolver = TrackMixFileBrowserMenuResolver()
    private val prePlayAutoDjResolver = TrackMixAutoDjResolver()
    private val nativePlayListResolver = TrackMixNativePlayListResolver()

    @Volatile private var pending: Pending? = null
    @Volatile private var nativeQueue: WeakReference<Any>? = null
    @Volatile private var nativeAutoDj: WeakReference<Any>? = null
    // 4.2.1 no longer exposes the verified 4.2.0 append-position allocator.
    // Scope the bounded native-DAO position repair only to a Track Auto-DJ
    // session we created; unrelated native list Play disarms it.
    @Volatile private var managedAutoDj: WeakReference<Any>? = null
    private val managedRefillInFlight = AtomicBoolean(false)
    private val zeroPoolBoundaryInFlight = AtomicBoolean(false)
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
        if (request?.stage == "ZERO_POOL_NATIVE_FILL") {
            return TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE
        }
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
        val autoDj = nativeAutoDj?.get() ?: return false
        val previous = nativeRefillAllowance.get() ?: 0
        nativeRefillAllowance.set(previous + 1)
        return try {
            requestNativeRefill(autoDj, count)
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

                if (!requestNativeRefill(autoDj, requested)) {
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

    /**
     * Record bounded natural MusicService Int->void calls while a zero-provider
     * Track Mix is starting. Once transient CURRENT is independently known,
     * exactly one call whose natural argument is CURRENT queue_position + 1 may
     * become the request-local next-source boundary. R50 may repair only that
     * already-existing queue slot synchronously before GMMP's original call
     * proceeds and materializes the next AudioSource.
     */
    fun observeNativePositionCommand(
        service: Any?,
        method: java.lang.reflect.Method,
        value: Int?
    ) {
        val request = pending ?: return
        if (request.stage != "WAIT_PLAY") return
        if (request.prePlayPrepared.trackIds.isNotEmpty()) return
        if (request.prePlayTarget == null || service == null || value == null) return
        if (method.parameterCount != 1 ||
            (method.parameterTypes[0] != Integer.TYPE &&
                method.parameterTypes[0] != Integer::class.java) ||
            method.returnType != Void.TYPE
        ) return
        val isMusicService = generateSequence<Class<*>>(service.javaClass) {
            it.superclass
        }.any { it.name == "gonemad.gmmp.playback.service.MusicService" }
        if (!isMusicService) return

        val key = method.declaringClass.name + "." + method.name +
            "(int)@" + System.identityHashCode(service)
        if (request.nativePositionCommands.none { it.key == key }) {
            if (request.nativePositionCommands.size >= 12) return
            method.isAccessible = true
            request.nativePositionCommands += ObservedNativePositionCommand(
                key = key,
                service = WeakReference(service),
                method = method,
                value = value,
                looper = Looper.myLooper(),
                observedAt = SystemClock.elapsedRealtime()
            )
        }

        // R64: the first natural MusicService CURRENT+1 command can beat the
        // worker that normally records provisionalCurrentQueuePosition. Prove
        // the already-position-1 selected Current synchronously from the
        // verified writer + exact unique queue row so the existing R62 guard
        // can still run before chain.proceed(). Never infer/rebase a wider
        // absolute coordinate here because the R62 fill restores position 1.
        if (request.provisionalCurrentQueuePosition == null &&
            request.nextSourceBoundary == null &&
            !request.nextSourceBoundaryAmbiguous
        ) {
            val autoDj = nativeAutoDj?.get()
            val target = request.prePlayTarget
            if (autoDj != null && target != null) {
                val located = runCatching {
                    queueReader.locateUniqueTrackRow(autoDj, target.id)
                }.getOrNull()
                if (located != null) {
                    val writer = nativePositionWriterProvider(autoDj)
                        ?: nativeTrackMixPositionWriterProvider(
                            autoDj,
                            located.queuePosition
                        )
                    val readback = writer?.let {
                        runCatching { it.read() }.getOrNull()
                    }
                    val context = readback?.let { position ->
                        runCatching {
                            queueReader.read(
                                autoDj,
                                position,
                                located.queueEntryId
                            )
                        }.getOrNull()
                    }
                    val current = context?.currentItem
                    val synchronousPosition =
                        TrackMixZeroPoolPolicy.resolveSynchronousNextSourceCurrentProof(
                            selectedTrackId = target.id,
                            currentTrackId = current?.track?.id,
                            selectedQueuePosition = located.queuePosition,
                            currentQueuePosition = context?.currentQueuePosition,
                            writerReadback = readback,
                            observedArgument = value
                        )
                    if (synchronousPosition != null &&
                        current?.queueEntryId == located.queueEntryId
                    ) {
                        request.verifiedCurrentQueueEntryId = located.queueEntryId
                        request.provisionalCurrentQueuePosition = synchronousPosition
                        Log.i(
                            TAG,
                            "MIX NEXT SOURCE CURRENT PROOF | verified=true" +
                                " | seed=${target.id}" +
                                " | entry=${located.queueEntryId}" +
                                " | currentPosition=$synchronousPosition" +
                                " | observedArg=$value" +
                                " | evidence=writer+exact-row-preworker"
                        )
                    }
                }
            }
        }

        tryPromoteNextSourceBoundary(request)
        guardZeroPoolNaturalNextSource(
            request = request,
            service = service,
            method = method,
            value = value,
            key = key
        )
    }

    private fun guardZeroPoolNaturalNextSource(
        request: Pending,
        service: Any,
        method: java.lang.reflect.Method,
        value: Int,
        key: String
    ) {
        if (request.zeroPoolNextSourceGuardAttempted) return
        if (request.prePlayPrepared.trackIds.isNotEmpty()) return
        if (request.stage != "WAIT_PLAY") return
        if (request.prePlayTarget == null) return
        val boundary = request.nextSourceBoundary ?: return
        if (request.nextSourceBoundaryAmbiguous || boundary.key != key) return
        if (boundary.service.get() !== service || boundary.method != method) return
        val autoDj = nativeAutoDj?.get() ?: return

        // R62: GMMP does not deterministically issue a natural qr.z refill
        // before its first current+1 lookup. If that unique, already-proven
        // predecoder lookup arrives first, reuse the exact R60 seed-isolation
        // and native-fill sequence with a zero original refill count. This is
        // still before the original MusicService lookup/decoder creation; no
        // playback method is replayed after materialization.
        if (!request.zeroPoolBoundaryPrepared) {
            val fallbackBoundary = prepareZeroPoolAtNativeRefill(
                autoDj = autoDj,
                requestedTracks = 0
            ) ?: run {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL NEXT SOURCE PREPARE | " +
                        "predecoder Initial Size boundary unavailable; fail closed"
                )
                return
            }
            if (!completeZeroPoolAtNativeRefill(autoDj, fallbackBoundary)) {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL NEXT SOURCE PREPARE | " +
                        "native Initial Size preparation failed; fail closed"
                )
                return
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL NEXT SOURCE PREPARE | verified=true | " +
                    "beforeOriginal=true | source=natural-current-plus-one | " +
                    "originalRequested=0 | nativeRequested=${fallbackBoundary.topUpCount}"
            )
        }

        val currentPosition = request.provisionalCurrentQueuePosition ?: return
        val plan = runCatching {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = currentPosition,
                observedArgument = value,
                nativeFallbackTrackIds = request.zeroPoolNativeFallbackTrackIds
            )
        }.getOrNull() ?: return

        request.zeroPoolNextSourceGuardAttempted = true
        val target = request.prePlayTarget ?: return
        val applied = runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = nativePositionWriterProvider(autoDj)
            ).guardTrackMixZeroPoolNextSource(
                selectedTrackId = target.id,
                nextQueuePosition = plan.nextQueuePosition,
                expectedTrackId = plan.expectedTrackId
            )
        }.onFailure {
            Log.e(
                TAG,
                "MIX ZERO-POOL NEXT GUARD | pre-source queue repair failed; fail closed",
                it
            )
        }.getOrDefault(false)
        request.zeroPoolNextSourceGuardApplied = applied
        Log.i(
            TAG,
            "MIX ZERO-POOL NEXT GUARD | applied=$applied | " +
                "position=${plan.nextQueuePosition} | track=${plan.expectedTrackId} | " +
                "command=${method.declaringClass.name}.${method.name}(int) | " +
                "beforeOriginal=true"
        )
    }

    /**
     * r45 bounded read-only mapping for GMMP 4.2.1's context-action
     * MenuItem,List -> boolean boundary. It runs only while the native Play
     * dispatched by this Track Mix request is settling. No argument is
     * modified and no host method is invoked from this observer.
     */
    fun observeNativeContextPlayBoundary(
        menuItem: MenuItem?,
        nativeItems: List<*>?
    ) {
        val request = pending ?: return
        if (request.stage != "WAIT_PLAY") return
        val item = menuItem ?: return
        val playId = request.context.resources.getIdentifier(
            "menuContextPlay", "id", GMMP_PACKAGE
        )
        if (playId == 0 || item.itemId != playId) return
        val items = nativeItems.orEmpty().filterNotNull()
        val classes = items.map { it.javaClass.name }.distinct().take(6)
        val autoDj = nativeAutoDj?.get()
        val resolution = if (autoDj != null && items.isNotEmpty()) {
            runCatching {
                resolvePrePlayTarget(autoDj, items)
            }.getOrNull()
        } else null
        val expected = request.prePlayTarget?.id
        Log.i(
            TAG,
            "MIX CONTEXT PLAY BOUNDARY | observed=true | item=${item.itemId} | " +
                "listSize=${items.size} | elementClasses=${classes.joinToString(",")} | " +
                "resolvedTrack=${resolution?.track?.id ?: -1L} | " +
                "expectedTrack=${expected ?: -1L} | readOnly=true"
        )
    }

    /**
     * GMMP 4.2.1's native song-context Play can hand MusicService.w1 the
     * entire Smart-Playlist source. In the zero-provider Track Mix case that
     * list is queued asynchronously after our verified CURRENT boundary and
     * can otherwise overwrite the already-correct five-row Auto-DJ queue.
     *
     * Narrow only the exact action=0 native Play list, only while this request
     * has zero prepared GoneSmart tracks, and only when the selected native
     * row is deterministically resolved from the pre-Play TrackInfo. Normal
     * playback and the non-empty Smart bootstrap path always return null.
     */
    fun narrowNativePlaybackListForZeroPool(
        action: Int?,
        nativeItems: List<*>?
    ): List<*>? = null

    /**
     * Called from the proven native qr.z refill hook before GMMP materializes
     * its Auto-DJ selection rows. Only a refill whose native Current already
     * matches the pre-Play-resolved target may consume bootstrap Track IDs.
     */
    fun prepareZeroPoolAtNativeRefill(
        autoDj: Any,
        requestedTracks: Int
    ): ZeroPoolNativeRefillBoundary? {
        val request = pending ?: return null
        if (!TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = request.source,
                legacyQueue = false
            )
        ) return null
        if (
            request.stage != "WAIT_PLAY" ||
            request.prePlayTarget == null ||
            request.prePlayPrepared.trackIds.isNotEmpty() ||
            request.prePlayInitialSize <= 1 ||
            request.zeroPoolBoundaryPrepared ||
            request.zeroPoolBoundaryAttempted ||
            nativeAutoDj?.get() !== autoDj
        ) return null

        val plan = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(
            initialSize = request.prePlayInitialSize,
            originalRequested = requestedTracks
        ) ?: return null
        val target = request.prePlayTarget ?: return null
        val located = queueReader.locateUniqueTrackRow(autoDj, target.id) ?: return null

        request.zeroPoolBoundaryAttempted = true
        request.verifiedCurrentQueueEntryId = located.queueEntryId
        request.provisionalCurrentQueuePosition = located.queuePosition
        request.stage = "ZERO_POOL_NATIVE_FILL"
        return try {
            val verifiedWriter = nativePositionWriterProvider(autoDj)
                ?: nativeTrackMixPositionWriterProvider(autoDj, located.queuePosition)
                ?: error("Verified GMMP Current-position writer unavailable at native refill boundary")
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = verifiedWriter,
                    currentQueueEntryIdHint = located.queueEntryId
                ).isolateTrackMixSeed(target.id)
            ) {
                "Could not isolate selected Track Auto-DJ seed before native refill"
            }
            val isolated = queueReader.read(
                autoDj,
                verifiedWriter.read(),
                located.queueEntryId
            ) ?: error("Predecoder seed verification unavailable")
            check(
                isolated.currentQueuePosition == 1 &&
                    isolated.currentItem?.queueEntryId == located.queueEntryId &&
                    isolated.currentItem?.track?.id == target.id &&
                    isolated.items.size == 1
            ) {
                "Predecoder seed did not restore the 4.2.0 position-1 invariant"
            }
            request.provisionalCurrentQueuePosition = 1
            Log.i(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | isolated=true | beforeOriginal=true" +
                    " | seed=${target.id} | entry=${located.queueEntryId}" +
                    " | oldCurrentPosition=${located.queuePosition} | currentPosition=1" +
                    " | originalRequested=$requestedTracks | topUp=${plan.topUpCount}" +
                    " | route=predecoder-4.2.0-semantics"
            )
            ZeroPoolNativeRefillBoundary(
                token = request.token,
                selectedTrackId = target.id,
                selectedEntryId = located.queueEntryId,
                currentPosition = 1,
                initialSize = request.prePlayInitialSize,
                originalRequested = requestedTracks,
                topUpCount = plan.topUpCount
            )
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            request.zeroPoolBoundaryAttempted = false
            Log.e(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | seed isolation failed; fail closed",
                failure
            )
            null
        }
    }

    fun completeZeroPoolAtNativeRefill(
        autoDj: Any,
        boundary: ZeroPoolNativeRefillBoundary
    ): Boolean {
        val request = pending ?: return false
        if (request.token != boundary.token || nativeAutoDj?.get() !== autoDj) return false
        if (request.stage != "ZERO_POOL_NATIVE_FILL") return false
        return try {
            if (boundary.topUpCount > 0) {
                check(requestHeldNativeRefill(boundary.topUpCount)) {
                    "GMMP rejected zero-pool Initial Size top-up"
                }
            }
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    currentQueueEntryIdHint = boundary.selectedEntryId
                ).compactTrackMixZeroPoolRefillAnchored(
                    selectedTrackId = boundary.selectedTrackId,
                    expectedSize = boundary.initialSize
                )
            ) {
                "Could not compact native zero-pool refill around selected seed"
            }

            val verified = queueReader.read(
                autoDj,
                currentQueueEntryIdHint = boundary.selectedEntryId
            ) ?: error("Zero-pool native refill verification unavailable")
            val ordered = verified.items.sortedBy { it.queuePosition }
            check(
                ordered.size == boundary.initialSize &&
                    ordered.firstOrNull()?.queueEntryId == boundary.selectedEntryId &&
                    ordered.firstOrNull()?.track?.id == boundary.selectedTrackId &&
                    ordered.firstOrNull()?.queuePosition == boundary.currentPosition &&
                    ordered.map { it.queuePosition } ==
                        (boundary.currentPosition until
                            boundary.currentPosition + boundary.initialSize).toList()
            ) {
                "Zero-pool native refill did not produce contiguous Initial Size"
            }

            val nativeFallbackTrackIds = ordered.drop(1).map { it.track.id }
            TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = boundary.selectedTrackId,
                nativeFallbackTrackIds = nativeFallbackTrackIds,
                expectedSize = boundary.initialSize
            )
            request.zeroPoolNativeFallbackTrackIds = nativeFallbackTrackIds
            check(ensureZeroPoolSourceBatchHooks(autoDj)) {
                "Verified native Queue DAO source-batch hooks are unavailable"
            }
            request.provisionalCurrentQueuePosition = boundary.currentPosition
            request.zeroPoolBoundaryPrepared = true
            request.stage = "WAIT_PLAY"
            val boundarySource = if (boundary.originalRequested == 0) {
                "natural-current-plus-one-fallback"
            } else {
                "natural-native-refill"
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL BOUNDARY | verified=true | beforeDecoder=true" +
                    " | source=$boundarySource | seed=${boundary.selectedTrackId}" +
                    " | initial=${boundary.initialSize}" +
                    " | nativeGenerated=${nativeFallbackTrackIds.size}" +
                    " | currentPosition=${boundary.currentPosition}" +
                    " | originalRequested=${boundary.originalRequested}" +
                    " | topUp=${boundary.topUpCount}"
            )
            true
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            Log.e(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | native fill verification failed; fail closed",
                failure
            )
            false
        }
    }

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

        val pinnedEntryId = request.verifiedCurrentQueueEntryId
        val currentId = runCatching {
            queueReader.read(
                autoDj,
                nativePositionWriterProvider(autoDj)?.read(),
                pinnedEntryId
            )?.currentItem?.track?.id
        }.getOrNull()
        if (currentId != target.id) {
            Log.i(
                TAG,
                "MIX PREPLAY BOOTSTRAP SKIP | current=$currentId | target=${target.id} | " +
                    "requested=$requested | pinnedEntry=" + (pinnedEntryId ?: -1L)
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
        if (request.stage == "PREFILL_SMART" ||
            request.stage == "PREFILL_NATIVE" ||
            request.stage == "FILLING"
        ) {
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
        val existing = menu.findItem(MIX_ITEM_ID)
        if (!enabled) {
            existing?.isVisible = false
            return
        }

        val playId = context.resources.getIdentifier(
            "menuContextPlay", "id", GMMP_PACKAGE
        )
        val nextId = context.resources.getIdentifier(
            "menuContextPlayNext", "id", GMMP_PACKAGE
        )
        val nativePlay = menu.findItem(playId) ?: return
        val nativeNext = menu.findItem(nextId) ?: return
        if (name == FILE_BROWSER_MENU) {
            val selectionContext = menuSelectionContext(menu, nativePlay)
            val browserSelection = fileBrowserMenuResolver.resolve(selectionContext.roots)
            if (browserSelection.kind != TrackMixFileBrowserSelectionKind.REGULAR_FILE) {
                existing?.isVisible = false
                Log.i(
                    TAG,
                    "MIX MENU SKIP | menu=$name" +
                        " | browserContext=${browserSelection.kind.name.lowercase()}" +
                        " | evidence=${browserSelection.evidence}" +
                        " | depth=${browserSelection.depth ?: -1}" +
                        " | roots=${selectionContext.roots.size}" +
                        " | inspected=${browserSelection.inspectedObjects}"
                )
                return
            }
        }
        if (existing != null) {
            existing.isVisible = true
            return
        }
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
        val selectionContext = menuSelectionContext(menu, nativePlay)
        val listener = selectionContext.listener
        val prePlayRoots = selectionContext.roots
        Log.i(
            TAG,
            "MIX CLICK | source=$source | roots=${prePlayRoots.size} | " +
                "autoDjCaptured=${nativeAutoDj?.get() != null}"
        )

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
            val capturedAutoDj = nativeAutoDj?.get()
            val autoDj = capturedAutoDj ?: prePlayAutoDjResolver
                .resolve(prePlayRoots)
                ?.also { recovered ->
                    captureNativeAutoDj(recovered)
                    Log.i(TAG, "MIX PREPLAY AUTO-DJ | recovered=context-graph")
                }
            val legacyQueue = autoDj?.let {
                field(it, "q")?.javaClass?.name == "ex3"
            } == true || nativeQueue?.get()?.javaClass?.name == "ex3"

            if (!legacyQueue) {
                if (autoDj == null) {
                    Log.w(TAG, "MIX PREPLAY AUTO-DJ | available=false | roots=${prePlayRoots.size}")
                    if (pending === request) pending = null
                    main.post { fail("Could not access Auto-DJ for this song.", context) }
                    return@execute
                }
                if (capturedAutoDj != null) {
                    Log.i(TAG, "MIX PREPLAY AUTO-DJ | available=true | source=captured")
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
                request.prePlayInitialSize = initial
                val usePredecoderCompatibility =
                    TrackMixInitialFillPolicy.usePredecoderCompatibility(
                        source = source,
                        legacyQueue = false
                    ) && initial > 1
                if (usePredecoderCompatibility) {
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
                            "requested=$requiredUpcoming | prepared=${request.prePlayPrepared.trackIds.size} | " +
                            "route=predecoder-4.2.0-semantics"
                    )
                    if (request.prePlayPrepared.trackIds.isEmpty()) {
                        Log.i(
                            TAG,
                            "MIX PREPLAY FALLBACK | no GoneSmart matches; " +
                                "awaiting native qr.z or current+1 predecoder boundary"
                        )
                    }
                } else {
                    request.prePlayPrepared = TrackMixPreparedInitialPool.EMPTY
                    request.stage = "PREPARED"
                    Log.i(
                        TAG,
                        "MIX PREPLAY | target=${resolution.track.id} | initial=$initial | " +
                            "strategy=native-auto-dj | route=r59"
                    )
                }
            }

            request.prePlayPreparationComplete = true
            if (request.deferredNoMatchNotice) {
                request.deferredNoMatchNotice = false
                showOwnedStatusNotice("no-matches-native-fallback")
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
                    if (pending === request) pending = null
                    refillHold.set(false)
                    fail("Smart DJ could not be enabled.", context)
                    return@post
                }

                // R56: zero-provider startup is anchored at GMMP's natural
                // qr.z refill (or the exact accepted Queue-row target), not at
                // an inferred dx3/current-state writer.

                val started = runCatching {
                    nativePlayDispatch()
                }.onFailure { Log.e(TAG, "Native track Play failed", it) }
                    .getOrDefault(false)

                if (!started) {
                    if (pending === request) pending = null
                    if (activePendingController?.get() === this@TrackMixController) {
                        activePendingController = null
                    }
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

    private fun preparePredecoderInitialQueue(
        request: Pending,
        loaded: Snapshot,
        selectedId: Long,
        selectedEntryId: Long,
        initial: Int,
        upcoming: Int
    ): Snapshot? {
        val selectedQueuePosition = loaded.currentQueuePosition ?: run {
            Log.e(TAG, "MIX PREDECODER | selected queue_position unavailable")
            fail("Could not identify the selected queue position safely.", request.context)
            return null
        }
        val requiredUpcoming = (initial - 1).coerceAtLeast(0)

        if (request.prePlayPrepared.trackIds.isEmpty()) {
            return fillZeroPoolNativeFallback(
                request = request,
                selectedId = selectedId,
                selectedEntryId = selectedEntryId,
                selectedQueuePosition = selectedQueuePosition,
                initial = initial,
                upcomingSetting = upcoming
            ) ?: run {
                fail(
                    "Could not safely materialize the native Auto-DJ queue before playback preload.",
                    request.context
                )
                null
            }
        }

        var currentSnapshot = loaded
        val preparedSeedIsolationRequired =
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = request.source,
                legacyQueue = false
            )
        if (preparedSeedIsolationRequired) {
            request.stage = "ISOLATING_PREPARED"
            val autoDj = nativeAutoDj?.get() ?: run {
                fail("Could not access Auto-DJ queue.", request.context)
                return null
            }
            val verifiedWriter = nativePositionWriterProvider(autoDj)
                ?: nativeTrackMixPositionWriterProvider(autoDj, selectedQueuePosition)
                ?: run {
                    Log.e(
                        TAG,
                        "MIX PREPLAY PREPARED SEED | verified Current-position writer unavailable"
                    )
                    fail("Could not isolate the selected queue song safely.", request.context)
                    return null
                }
            val isolated = runCatching {
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = verifiedWriter,
                    currentQueueEntryIdHint = selectedEntryId
                ).isolateTrackMixSeed(selectedId)
            }.onFailure {
                Log.e(
                    TAG,
                    "MIX PREPLAY PREPARED SEED | queue-context seed isolation failed",
                    it
                )
            }.getOrDefault(false)
            if (!isolated) {
                fail("Could not isolate the selected queue song safely.", request.context)
                return null
            }
            currentSnapshot = queueSnapshot(selectedEntryId)?.takeIf {
                it.currentId == selectedId &&
                    it.currentEntryId == selectedEntryId &&
                    it.currentQueuePosition == 1 &&
                    it.ids.size == 1
            } ?: run {
                Log.e(
                    TAG,
                    "MIX PREPLAY PREPARED SEED | isolated seed postcondition failed"
                )
                fail("Could not verify the isolated queue song.", request.context)
                return null
            }
            request.provisionalCurrentQueuePosition = 1
            request.stage = "WAIT_PLAY"
            Log.i(
                TAG,
                "MIX PREPLAY PREPARED SEED | isolated=true | source=queue-context | " +
                    "seed=$selectedId | entry=$selectedEntryId | " +
                    "oldCurrentPosition=$selectedQueuePosition | currentPosition=1 | " +
                    "removed=${(loaded.ids.size - 1).coerceAtLeast(0)}"
            )
        }
        var availableUpcoming =
            (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
        if (availableUpcoming < requiredUpcoming) {
            val deficit = requiredUpcoming - availableUpcoming
            if (!requestHeldNativeRefill(deficit)) {
                fail("Could not prepare Auto-DJ queue slots before playback preload.", request.context)
                return null
            }
            currentSnapshot = awaitQueue(
                request,
                8_000L,
                selectedEntryId
            ) { snapshot ->
                snapshot.currentId == selectedId &&
                    (snapshot.ids.size - snapshot.currentIndex - 1) >= requiredUpcoming
            } ?: run {
                fail("Could not prepare Auto-DJ queue slots before playback preload.", request.context)
                return null
            }
            availableUpcoming =
                (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
        }

        val expectedSmart = request.prePlayPrepared.trackIds
            .take(request.bootstrapAppliedTrackIds.size)
        if (expectedSmart.isEmpty()) {
            Log.e(
                TAG,
                "MIX PREDECODER VERIFY | prepared Smart pool never reached native materialization"
            )
            fail("Could not apply the prepared Track Auto-DJ selection safely.", request.context)
            return null
        }
        expectedSmart.forEachIndexed { index, trackId ->
            val actual = currentSnapshot.ids.getOrNull(currentSnapshot.currentIndex + 1 + index)
            if (actual != trackId) {
                Log.e(
                    TAG,
                    "MIX PREDECODER VERIFY | slot=${index + 1} | expected=$trackId | actual=$actual"
                )
                fail("Could not verify the prepared Track Auto-DJ selection.", request.context)
                return null
            }
        }

        val autoDj = nativeAutoDj?.get() ?: run {
            fail("Could not access Auto-DJ queue.", request.context)
            return null
        }
        val verifiedPositionWriter = nativePositionWriterProvider(autoDj)
            ?: if (preparedSeedIsolationRequired) {
                nativeTrackMixPositionWriterProvider(autoDj, 1)
            } else {
                null
            }

        // R65: R63 correctly isolates/rebases a Queue-context prepared seed to
        // position 1, but GMMP's hidden append allocator may still materialize
        // the four prepared followers at old high coordinates (for example
        // 7216..7219). The Track IDs/order are already correct at this point.
        // Compact only queue_position through the accepted native O0 writer,
        // preserve queue IDs/Track IDs/Current state, verify 1..Initial Size,
        // and only then run the unchanged prune/final verification.
        if (preparedSeedIsolationRequired) {
            request.stage = "NORMALIZE_PREPARED"
            val normalized = runCatching {
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = verifiedPositionWriter,
                    currentQueueEntryIdHint = selectedEntryId
                ).compactTrackMixPreparedRefillAnchored(
                    selectedTrackId = selectedId,
                    expectedSize = initial
                )
            }.onFailure {
                Log.e(
                    TAG,
                    "MIX PREPLAY PREPARED POSITIONS | native coordinate normalization failed",
                    it
                )
            }.getOrDefault(false)
            if (!normalized) {
                fail("Could not normalize the prepared Track Auto-DJ queue.", request.context)
                return null
            }
            currentSnapshot = queueSnapshot(selectedEntryId)?.takeIf {
                it.currentId == selectedId &&
                    it.currentEntryId == selectedEntryId &&
                    it.currentQueuePosition == 1 &&
                    it.ids.size == initial
            } ?: run {
                fail("Could not verify the normalized Track Auto-DJ queue.", request.context)
                return null
            }
            Log.i(
                TAG,
                "MIX PREPLAY PREPARED POSITIONS | verified=true | " +
                    "seed=$selectedId | entry=$selectedEntryId | " +
                    "initial=$initial | currentPosition=1 | positions=1..$initial"
            )
        }

        request.stage = "PRUNE"
        val pruned = runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = verifiedPositionWriter,
                currentQueueEntryIdHint = selectedEntryId
            ).retargetTrackMixUpcoming(
                selectedTrackId = selectedId,
                smartTrackIds = emptyList(),
                targetQueueSize = initial
            )
        }.onFailure {
            Log.e(TAG, "MIX PREDECODER PRUNE | native 4.2.1 queue prune failed", it)
        }.getOrDefault(false)
        if (!pruned) {
            fail("Could not finalize the Track Auto-DJ queue.", request.context)
            return null
        }
        val filled = queueSnapshot(selectedEntryId)?.takeIf {
            it.currentId == selectedId && it.ids.size == initial
        } ?: run {
            fail("Could not verify the Track Auto-DJ queue.", request.context)
            return null
        }

        Log.i(
            TAG,
            "MIX PREDECODER QUEUE | verified=true | initial=$initial | " +
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
            "MIX AUTO-DJ | command=sent-after-predecoder-bootstrap | " +
                "initialSize=$initial | upcoming=$upcoming | seed=$selectedId"
        )
        return filled
    }

    private fun runTrackMix(request: Pending) {
        var managedSessionReady = false
        try {
            val loadedPlay = waitForSelectedSong(request) ?: run {
                fail("The selected song did not start.", request.context)
                return
            }
            if (!isCurrent(request)) return

            val loaded = loadedPlay.snapshot
            val selectedId = loadedPlay.selectedTrackId
            val selectedEntryId = loaded.currentEntryId ?: run {
                Log.e(TAG, "MIX CURRENT PIN | selected queue_id unavailable")
                fail("Could not identify the selected queue entry safely.", request.context)
                return
            }
            request.verifiedCurrentQueueEntryId = selectedEntryId
            Log.i(
                TAG,
                "MIX CURRENT PIN | entry=$selectedEntryId | track=$selectedId | verified=true"
            )

            request.prePlayTarget?.let { target ->
                if (target.id != selectedId) {
                    Log.e(
                        TAG,
                        "MIX PREPLAY VERIFY | selected=$selectedId | resolved=${target.id} | mismatch=true"
                    )
                    fail("The selected song changed before Track Auto-DJ could start.", request.context)
                    return
                }
            }

            val nativeSettings = settings.read()
            val initial = nativeSettings.initialQueueSize.coerceAtLeast(1)
            val upcoming = nativeSettings.upcomingTrackCount
            val legacyQueue =
                nativeAutoDj?.get()?.let { autoDj ->
                    field(autoDj, "q")?.javaClass?.name == "ex3"
                } == true || nativeQueue?.get()?.javaClass?.name == "ex3"

            val usePredecoderCompatibility =
                TrackMixInitialFillPolicy.usePredecoderCompatibility(
                    source = request.source,
                    legacyQueue = legacyQueue
                ) && initial > 1
            if (usePredecoderCompatibility) {
                val filled = preparePredecoderInitialQueue(
                    request = request,
                    loaded = loaded,
                    selectedId = selectedId,
                    selectedEntryId = selectedEntryId,
                    initial = initial,
                    upcoming = upcoming
                )
                refillHold.set(false)
                if (filled == null) return

                val exactContext = nativeAutoDj?.get()?.let { autoDj ->
                    queueReader.read(
                        autoDj,
                        currentQueueEntryIdHint = selectedEntryId
                    )
                }
                val orderedPositions = exactContext?.items
                    ?.sortedBy { it.queuePosition }
                    ?.map { it.queuePosition }
                    ?: emptyList()
                if (exactContext?.currentItem?.track?.id != selectedId ||
                    !TrackMixInitialFillPolicy.isExactInitialQueue(
                        initialSize = initial,
                        currentQueuePosition = exactContext?.currentQueuePosition,
                        queuePositions = orderedPositions
                    )
                ) {
                    Log.e(
                        TAG,
                        "MIX PREDECODER COORDINATES | initial=$initial | actual=${filled.ids.size} | " +
                            "currentPosition=${exactContext?.currentQueuePosition} | " +
                            "positions=${orderedPositions.joinToString(",")} | verified=false"
                    )
                    fail("GMMP's predecoder Initial Size was not normalized correctly.", request.context)
                    return
                }

                if (managedAutoDj?.get() == null) {
                    armManagedAutoDjSession()
                }
                managedSessionReady = managedAutoDj?.get() === nativeAutoDj?.get()
                if (!managedSessionReady) {
                    fail("Could not retain the Track Auto-DJ continuation session.", request.context)
                    return
                }

                Log.i(
                    TAG,
                    "MIX VERIFIED | initial=$initial | actual=${filled.ids.size} | " +
                        "currentPreserved=true | tracksAfterSeed=${filled.ids.size - 1} | " +
                        "owner=predecoder-4.2.0-semantics"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "${request.menuLabel}: ${filled.ids.size - 1} " +
                        "tracks queued after the selected song."
                )
                toast(request.context, request.confirmation)
                nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 3_000L
                return
            }

            // This is the one queue mutation Track Mix owns: reduce the queue to
            // the exact native row GMMP has just started. Followers are NEVER
            // retargeted or pre-injected. Once this seed is isolated the hold is
            // released and GMMP owns Initial Size, order and decoder creation.
            request.stage = "ISOLATING"
            val isolated = if (legacyQueue) {
                isolateNativeSeed(request, selectedId) != null
            } else {
                val autoDj = nativeAutoDj?.get() ?: run {
                    fail("Could not access Auto-DJ queue.", request.context)
                    return
                }
                runCatching {
                    val verifiedWriter = nativePositionWriterProvider(autoDj)
                        ?: loaded.currentQueuePosition?.let { position ->
                            nativeTrackMixPositionWriterProvider(autoDj, position)
                        }
                        ?: error("Verified GMMP Current-position writer unavailable for seed isolation")
                    GmmpQueueMutationBridge(
                        autoDj = autoDj,
                        verifiedPositionWriter = verifiedWriter,
                        currentQueueEntryIdHint = selectedEntryId
                    ).isolateTrackMixSeed(selectedId)
                }.onFailure {
                    Log.e(TAG, "MIX ISOLATE | 4.2.1 native seed isolation failed", it)
                }.getOrDefault(false)
            }
            if (!isolated) {
                fail("Could not prepare the selected song for Auto-DJ.", request.context)
                return
            }

            val seed = queueSnapshot(selectedEntryId) ?: run {
                fail("Could not verify the isolated Auto-DJ seed.", request.context)
                return
            }
            if (seed.currentId != selectedId || seed.ids.size != 1) {
                Log.e(
                    TAG,
                    "MIX SEED VERIFY | selected=$selectedId | current=${seed.currentId} | size=${seed.ids.size}"
                )
                fail("Could not verify the isolated Auto-DJ seed.", request.context)
                return
            }

            val sessionAutoDj = nativeAutoDj?.get()
            if (sessionAutoDj == null ||
                !resetRecommendationSession(sessionAutoDj, selectedId, selectedEntryId)
            ) {
                fail("Could not start a fresh recommendation session for this song.", request.context)
                return
            }

            // GMMP 4.2.0 reset ex3.p together with Current before native
            // Auto-DJ refilled. 4.2.1 has no behaviorally proven direct
            // append-allocator binding, so arm only this explicit Track
            // Auto-DJ session for the scoped post-qr.z coordinate shim.
            if (!legacyQueue) {
                armManagedAutoDjSession()
                check(managedAutoDj?.get() === sessionAutoDj) {
                    "Track Mix 4.2.1 queue compatibility session was not armed"
                }
            }

            Log.i(
                TAG,
                "MIX SEED | verified=true | entry=$selectedEntryId | track=$selectedId | " +
                    "initial=$initial | followers=${(initial - 1).coerceAtLeast(0)} | " +
                    "source=GMMP-settings"
            )

            request.stage = "FILLING"
            request.refillObserved = false
            request.refillRequestedTracks.set(0)
            request.transitionalRefillSuppressed = false

            // Critical R57 ownership boundary: from here the hold is OFF before
            // AUTO_DJ is sent. GMMP's own qr.z request therefore traverses the
            // normal GoneSmart Auto-DJ refill/selection hook. Track Mix does not
            // construct follower rows or Track IDs itself.
            refillHold.set(false)
            sendCommand(request.context, COMMAND_AUTO_DJ)
            nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 7_000L
            Log.i(
                TAG,
                "MIX AUTO-DJ | command=sent-native | initialSize=$initial | " +
                    "upcoming=$upcoming | seed=$selectedId | hold=false"
            )

            var filled = awaitNativeOwnedInitialFill(
                request = request,
                selectedId = selectedId,
                selectedEntryId = selectedEntryId,
                initial = initial
            )

            if (filled == null && isCurrent(request)) {
                val current = queueSnapshot(selectedEntryId)
                if (current == null || current.currentId != selectedId) {
                    fail("The selected song changed while Auto-DJ was filling.", request.context)
                    return
                }
                val shortage = TrackMixInitialFillPolicy.nativeInitialRefillCount(
                    initialSize = initial,
                    seedQueueSize = current.ids.size
                )
                if (shortage > 0) {
                    val autoDj = nativeAutoDj?.get() ?: run {
                        fail("Could not access Auto-DJ for the Initial Size fallback.", request.context)
                        return
                    }
                    Log.i(
                        TAG,
                        "MIX INITIAL FALLBACK | actual=${current.ids.size} | initial=$initial | " +
                            "shortage=$shortage | boundary=native-qr.z"
                    )
                    if (!requestNativeRefill(autoDj, shortage)) {
                        fail("GMMP could not complete the configured Initial Size.", request.context)
                        return
                    }
                    filled = awaitQueue(request, 65_000L, selectedEntryId) { snapshot ->
                        snapshot.currentId == selectedId && snapshot.ids.size == initial
                    }
                }
            }

            if (!isCurrent(request)) return
            if (filled == null) {
                val actual = queueSnapshot(selectedEntryId)?.ids?.size ?: -1
                Log.w(TAG, "MIX INCOMPLETE | initial=$initial | actual=$actual")
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "${request.menuLabel} started, but GMMP's configured Initial Size " +
                        "could not be verified."
                )
                toast(request.context, request.confirmation)
                return
            }

            val exactContext = nativeAutoDj?.get()?.let { autoDj ->
                queueReader.read(
                    autoDj,
                    currentQueueEntryIdHint = selectedEntryId
                )
            }
            val orderedPositions = exactContext?.items
                ?.sortedBy { it.queuePosition }
                ?.map { it.queuePosition }
                ?: emptyList()
            if (exactContext?.currentItem?.track?.id != selectedId ||
                !TrackMixInitialFillPolicy.isExactInitialQueue(
                    initialSize = initial,
                    currentQueuePosition = exactContext?.currentQueuePosition,
                    queuePositions = orderedPositions
                )
            ) {
                Log.e(
                    TAG,
                    "MIX VERIFY COORDINATES | initial=$initial | actual=${filled.ids.size} | " +
                        "currentPosition=${exactContext?.currentQueuePosition} | " +
                        "positions=${orderedPositions.joinToString(",")} | verified=false"
                )
                fail("GMMP's Initial Size was not normalized correctly.", request.context)
                return
            }

            managedSessionReady =
                !legacyQueue && managedAutoDj?.get() === sessionAutoDj

            Log.i(
                TAG,
                "MIX VERIFIED | initial=$initial | actual=${filled.ids.size} | " +
                    "currentPreserved=true | tracksAfterSeed=${filled.ids.size - 1} | " +
                    "owner=native-GMMP"
            )
            events.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                "${request.menuLabel}: ${filled.ids.size - 1} " +
                    "tracks queued after the selected song."
            )
            toast(request.context, request.confirmation)
            nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 3_000L
        } catch (failure: Throwable) {
            Log.e(TAG, "Track Mix failed", failure)
            fail("Could not complete ${request.menuLabel}.", request.context)
        } finally {
            refillHold.set(false)
            request.stage = "DONE"
            if (pending === request) pending = null
            if (activePendingController?.get() === this) {
                activePendingController = null
            }
            zeroPoolBoundaryInFlight.set(false)
            if (!managedSessionReady) {
                disarmManagedAutoDjSession("track-mix-not-verified")
            } else {
                Log.i(
                    TAG,
                    "MIX CONTINUITY | retained after verified Initial Size"
                )
            }
        }
    }

    private fun awaitNativeOwnedInitialFill(
        request: Pending,
        selectedId: Long,
        selectedEntryId: Long,
        initial: Int
    ): Snapshot? {
        if (initial <= 1) {
            return queueSnapshot(selectedEntryId)?.takeIf {
                it.currentId == selectedId && it.ids.size == 1
            }
        }

        val commandDeadline = SystemClock.elapsedRealtime() + 2_500L
        val refillDeadline = SystemClock.elapsedRealtime() + 65_000L
        while (isCurrent(request) && SystemClock.elapsedRealtime() < refillDeadline) {
            val snapshot = queueSnapshot(selectedEntryId)
            if (snapshot != null) {
                if (snapshot.currentId != selectedId) return null
                if (snapshot.ids.size == initial) return snapshot
                // Any follower becoming visible proves the currently observed
                // native refill has materialized. If GMMP requested fewer than
                // Initial Size, let the caller top up only the real shortfall.
                if (snapshot.ids.size > 1) {
                    Log.i(
                        TAG,
                        "MIX NATIVE FILL PARTIAL | actual=${snapshot.ids.size} | " +
                            "initial=$initial | requested=${request.refillRequestedTracks.get()}"
                    )
                    return null
                }
            }

            if (!request.refillObserved &&
                SystemClock.elapsedRealtime() >= commandDeadline
            ) {
                Log.i(
                    TAG,
                    "MIX NATIVE FILL | command produced no refill boundary; " +
                        "checking dynamic shortfall"
                )
                return null
            }
            Thread.sleep(100L)
        }
        return null
    }

    /**
     * Legacy-only deterministic Queue isolation via GMMP 4.2.0 ex3.c(uq1),
     * xx3.O(ey3[]) and xx3.O0(ArrayList). Unlike CLEAR_QUEUE broadcast,
     * the delete and current-row update happen in one Room transaction.
     * The selected native queue_id and currently playing track both stay
     * unchanged. All operations run on Track Mix's worker (never UI).
     */
    private fun isolateNativeSeed(
        request: Pending,
        selectedTrackId: Long
    ): Snapshot? {
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

    private fun tryPromoteNextSourceBoundary(request: Pending) {
        if (request.nextSourceBoundary != null ||
            request.nextSourceBoundaryAmbiguous
        ) return
        val currentPosition = request.provisionalCurrentQueuePosition ?: return
        val now = SystemClock.elapsedRealtime()
        val selection = TrackMixZeroPoolPolicy.selectNextSourceBoundary(
            currentQueuePosition = currentPosition,
            candidates = request.nativePositionCommands.map { candidate ->
                TrackMixZeroPoolPolicy.PositionCommandCandidate(
                    key = candidate.key,
                    argument = candidate.value,
                    ageMs = now - candidate.observedAt
                )
            }
        )
        if (selection.matchCount > 1) {
            request.nextSourceBoundaryAmbiguous = true
            Log.w(
                TAG,
                "MIX NEXT SOURCE BOUNDARY | ambiguous=true" +
                    " | currentPosition=" + currentPosition +
                    " | matches=" + selection.matchCount
            )
            return
        }
        val key = selection.key ?: return
        val candidate = request.nativePositionCommands.singleOrNull {
            it.key == key
        } ?: return
        request.nextSourceBoundary = candidate
        Log.i(
            TAG,
            "MIX NEXT SOURCE BOUNDARY | captured=true" +
                " | command=" + candidate.method.declaringClass.name + "." +
                candidate.method.name + "(int)" +
                " | observedArg=" + candidate.value +
                " | transientCurrent=" + currentPosition +
                " | evidence=current-plus-one"
        )
    }


    private fun prepareZeroPoolFromNaturalStateCandidate(
        autoDj: Any,
        observedArgument: Int
    ) {
        val request = pending ?: return
        if (
            request.stage != "WAIT_PLAY" ||
            request.prePlayTarget == null ||
            request.prePlayPrepared.trackIds.isNotEmpty() ||
            request.prePlayInitialSize <= 1 ||
            request.zeroPoolBoundaryPrepared ||
            request.zeroPoolBoundaryAttempted ||
            nativeAutoDj?.get() !== autoDj
        ) return

        val target = request.prePlayTarget ?: return
        val located = queueReader.locateUniqueTrackRow(autoDj, target.id) ?: return
        val currentPosition =
            TrackMixZeroPoolPolicy.resolveNaturalCurrentBoundaryPosition(
                observedArgument = observedArgument,
                selectedQueuePosition = located.queuePosition
            ) ?: return
        val writer = nativeTrackMixPositionWriterProvider(
            autoDj,
            currentPosition
        ) ?: return
        val readback = runCatching { writer.read() }.getOrNull() ?: return
        if (readback != currentPosition) {
            Log.i(
                TAG,
                "MIX ZERO-POOL CURRENT CANDIDATE | verified=false | " +
                    "observedArg=$observedArgument | targetPosition=$currentPosition | " +
                    "readback=$readback"
            )
            return
        }

        Log.i(
            TAG,
            "MIX ZERO-POOL CURRENT CANDIDATE | verified=true | " +
                "observedArg=$observedArgument | targetPosition=$currentPosition | " +
                "targetEntry=${located.queueEntryId} | readback=$readback | " +
                "source=natural-state-writer-fallback"
        )
        prepareZeroPoolAtNaturalCurrentBoundary(
            autoDj = autoDj,
            currentPosition = currentPosition
        )
    }

    /**
     * GMMP 4.2.1 equivalent of the old 4.2.0 seed-isolation boundary.
     * QueueFlipController calls this synchronously after GMMP's naturally
     * observed CURRENT writer has returned but before that native caller
     * resumes and asks for current+1. Zero-provider Track Mix therefore
     * finishes seed isolation, native qr.z fill and position compaction
     * before the next AudioSource can be materialized.
     */
    private fun prepareZeroPoolAtNaturalCurrentBoundary(
        autoDj: Any,
        currentPosition: Int?
    ) {
        val request = pending ?: return
        val position = currentPosition ?: return
        if (
            position <= 0 ||
            request.stage != "WAIT_PLAY" ||
            request.prePlayTarget == null ||
            request.prePlayPrepared.trackIds.isNotEmpty() ||
            request.prePlayInitialSize <= 1 ||
            request.zeroPoolBoundaryPrepared ||
            request.zeroPoolBoundaryAttempted ||
            nativeAutoDj?.get() !== autoDj
        ) return

        val target = request.prePlayTarget ?: return
        val boundaryContext = runCatching {
            queueReader.read(autoDj, position)
        }.onFailure {
            Log.w(TAG, "MIX ZERO-POOL BOUNDARY | native Current read failed", it)
        }.getOrNull() ?: return
        val current = boundaryContext.currentItem ?: return

        // Natural position writers also occur for the old/current source while
        // Play is rebuilding. Only the exact pre-Play-resolved selected track
        // may acquire the mutation boundary.
        if (current.track.id != target.id) return

        val currentWriter = nativeTrackMixPositionWriterProvider(
            autoDj,
            position
        ) ?: run {
            Log.w(
                TAG,
                "MIX ZERO-POOL BOUNDARY | verified Current writer unavailable; retrying on next natural boundary"
            )
            return
        }
        if (!zeroPoolBoundaryInFlight.compareAndSet(false, true)) return

        request.zeroPoolBoundaryAttempted = true
        try {
            check(isCurrent(request) && request.stage == "WAIT_PLAY") {
                "Track Auto-DJ request changed before zero-pool boundary"
            }
            request.verifiedCurrentQueueEntryId = current.queueEntryId
            request.provisionalCurrentQueuePosition = position
            request.stage = "ZERO_POOL_BOUNDARY"

            val isolated = GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = currentWriter,
                currentQueueEntryIdHint = current.queueEntryId
            ).isolateTrackMixZeroPoolSeed(target.id)
            check(isolated) {
                "Could not isolate selected Track Auto-DJ seed at Current boundary"
            }

            val initial = request.prePlayInitialSize.coerceAtLeast(1)
            val refillCount = (initial - 1).coerceAtLeast(0)
            check(position <= Int.MAX_VALUE - refillCount) {
                "Zero-pool Current anchor cannot fit Initial Size"
            }

            // Preserve GMMP's natural absolute Current coordinate. Native
            // Auto-DJ owns the replacement identities and must append them as
            // CURRENT+1..CURRENT+N before the caller can materialize next.
            request.stage = "FILLING"
            request.transitionalRefillSuppressed = false
            check(requestHeldNativeRefill(refillCount)) {
                "GMMP rejected zero-pool native Initial Size refill"
            }

            val verified = queueReader.read(
                autoDj,
                currentWriter.read(),
                current.queueEntryId
            )
            val orderedVerified = verified?.items?.sortedBy { it.queuePosition }.orEmpty()
            val expectedPositions = (position until (position + initial)).toList()
            check(
                verified != null &&
                    verified.currentQueuePosition == position &&
                    verified.currentItem?.queueEntryId == current.queueEntryId &&
                    verified.currentItem?.track?.id == target.id &&
                    verified.items.size == initial &&
                    orderedVerified.map { it.queuePosition } == expectedPositions &&
                    orderedVerified.firstOrNull()?.queueEntryId == current.queueEntryId
            ) {
                "Native zero-pool refill did not preserve the anchored Current and contiguous Initial Size"
            }

            // Persist exactly the identities GMMP chose at the natural anchor.
            // No predecoder rebase/state write occurs. R50 protects the first
            // follower at anchor+1; R51 may carry all followers through a late
            // source rebuild. Settled cleanup may rebase to 1..N afterwards.
            val nativeFallbackTrackIds = orderedVerified.drop(1).map { it.track.id }
            val expectedTrackIds = TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = target.id,
                nativeFallbackTrackIds = nativeFallbackTrackIds,
                expectedSize = initial
            )
            check(orderedVerified.map { it.track.id } == expectedTrackIds) {
                "Zero-pool native fallback identity changed before decoder materialization"
            }
            request.zeroPoolNativeFallbackTrackIds = nativeFallbackTrackIds
            check(ensureZeroPoolSourceBatchHooks(autoDj)) {
                "Verified native Queue DAO source-batch hooks are unavailable"
            }

            request.provisionalCurrentQueuePosition = position
            request.zeroPoolBoundaryPrepared = true
            request.stage = "WAIT_PLAY"
            if (activePendingController?.get() === this) {
                activePendingController = null
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL BOUNDARY | verified=true | beforeDecoder=true | " +
                    "seed=${target.id} | initial=$initial | nativeGenerated=$refillCount | " +
                    "oldCurrentPosition=$position | currentPosition=$position | " +
                    "anchored=true | stateWrite=false"
            )
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            if (activePendingController?.get() === this) {
                activePendingController = null
            }
            Log.e(
                TAG,
                "MIX ZERO-POOL BOUNDARY | preparation failed; fail closed",
                failure
            )
        } finally {
            zeroPoolBoundaryInFlight.set(false)
        }
    }

    fun guardZeroPoolSourceBatchBeforeOriginal(
        binding: GmmpQueueSourceBatchBinding,
        method: java.lang.reflect.Method,
        payload: Any?
    ) {
        val request = pending ?: return
        if (
            request.stage != "WAIT_PLAY" ||
            !request.zeroPoolBoundaryPrepared ||
            !request.zeroPoolNextSourceGuardApplied ||
            request.prePlayPrepared.trackIds.isNotEmpty() ||
            request.zeroPoolNativeFallbackTrackIds.isEmpty()
        ) return
        val target = request.prePlayTarget ?: return
        val result = runCatching {
            binding.guardBeforeWrite(
                payload = payload,
                selectedTrackId = target.id,
                nativeFallbackTrackIds = request.zeroPoolNativeFallbackTrackIds,
                expectedSize = request.prePlayInitialSize
            )
        }.onFailure {
            Log.e(
                TAG,
                "MIX ZERO-POOL SOURCE BATCH | native pre-write guard failed closed",
                it
            )
        }.getOrNull() ?: return

        request.zeroPoolSourceBatchGuardApplied = true
        Log.i(
            TAG,
            "MIX ZERO-POOL SOURCE BATCH | applied=true | beforeOriginal=true" +
                " | writer=" + method.declaringClass.name + "." + method.name +
                "(" + method.parameterTypes.firstOrNull()?.name + ")" +
                " | size=" + result.batchSize +
                " | currentPosition=" + result.currentPosition +
                " | changed=" + result.changed +
                " | upcoming=" + result.trackIdsByPosition.entries.joinToString(",") {
                    it.key.toString() + ":" + it.value
                }
        )
    }

    private fun fillZeroPoolNativeFallback(
        request: Pending,
        selectedId: Long,
        selectedEntryId: Long,
        selectedQueuePosition: Int,
        initial: Int,
        upcomingSetting: Int
    ): Snapshot? {
        if (!request.zeroPoolBoundaryPrepared) {
            Log.e(
                TAG,
                "MIX ZERO-POOL | verified pre-decoder Current boundary was not prepared; fail closed"
            )
            return null
        }

        val expectedTrackIds = runCatching {
            TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = selectedId,
                nativeFallbackTrackIds = request.zeroPoolNativeFallbackTrackIds,
                expectedSize = initial
            )
        }.onFailure {
            Log.e(TAG, "MIX ZERO-POOL | pre-decoder native fallback identity unavailable", it)
        }.getOrNull() ?: return null

        fun exactSnapshot(entryId: Long): Snapshot? =
            queueSnapshot(entryId)?.takeIf { snapshot ->
                snapshot.currentId == selectedId &&
                    snapshot.currentIndex == 0 &&
                    snapshot.ids == expectedTrackIds
            }

        fun finalSnapshot(entryId: Long): Snapshot? =
            exactSnapshot(entryId)?.takeIf { snapshot ->
                snapshot.currentQueuePosition == 1
            }

        val observed = queueSnapshot(selectedEntryId)
        var filled = finalSnapshot(selectedEntryId)
        if (filled == null) {
            val intactAnchoredFallback = observed?.takeIf { snapshot ->
                snapshot.currentId == selectedId &&
                    snapshot.currentIndex == 0 &&
                    snapshot.ids == expectedTrackIds &&
                    snapshot.currentQueuePosition != null &&
                    snapshot.currentQueuePosition > 0
            }
            val sourceRebuild = intactAnchoredFallback == null

            // Playback evidence must be complete before any settled database
            // cleanup. A missing guard is a fail-closed no-op, not permission
            // to prune/rewrite the currently playing native source queue.
            if (!request.zeroPoolNextSourceGuardApplied) {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL RESTORE | natural CURRENT+1 guard was not verified before decoder materialization"
                )
                return null
            }
            if (sourceRebuild && !request.zeroPoolSourceBatchGuardApplied) {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL RESTORE | rebuilt native source batch was not guarded before materialization"
                )
                return null
            }

            val cleanupPosition = intactAnchoredFallback?.currentQueuePosition
                ?: selectedQueuePosition
            if (cleanupPosition == null || cleanupPosition <= 0) {
                Log.e(TAG, "MIX ZERO-POOL RESTORE | rebuilt Current position unavailable")
                return null
            }
            val autoDj = nativeAutoDj?.get() ?: run {
                Log.e(TAG, "MIX ZERO-POOL RESTORE | Auto-DJ owner unavailable")
                return null
            }
            val currentWriter = nativeTrackMixPositionWriterProvider(
                autoDj,
                cleanupPosition
            ) ?: run {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL RESTORE | verified rebuilt Current writer unavailable | " +
                        "currentPosition=$cleanupPosition"
                )
                return null
            }

            request.stage = "ZERO_POOL_RESTORE"
            val restored = runCatching {
                val bridge = GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = currentWriter,
                    currentQueueEntryIdHint = selectedEntryId
                )
                if (sourceRebuild) {
                    check(
                        bridge.retargetTrackMixUpcoming(
                            selectedTrackId = selectedId,
                            smartTrackIds = request.zeroPoolNativeFallbackTrackIds,
                            targetQueueSize = initial
                        )
                    ) {
                        "Could not restore the pre-decoder native fallback IDs"
                    }
                }
                check(
                    bridge.rebaseTrackMixZeroPoolInitialQueue(
                        selectedTrackId = selectedId,
                        expectedSize = initial
                    )
                ) {
                    "Could not compact the settled native fallback queue"
                }
                true
            }.onFailure {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL RESTORE | identity-preserving settled cleanup failed",
                    it
                )
            }.getOrDefault(false)
            if (!restored) return null

            request.verifiedCurrentQueueEntryId = selectedEntryId
            request.provisionalCurrentQueuePosition = 1
            filled = finalSnapshot(selectedEntryId)
            if (filled == null) {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL RESTORE | final queue identity/postcondition failed"
                )
                return null
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL RESTORE | verified=true | sourceRebuild=$sourceRebuild | " +
                    "queueIdentityRestored=true | oldCurrentPosition=$cleanupPosition | " +
                    "currentPosition=1 | nativeFallback=" +
                    request.zeroPoolNativeFallbackTrackIds.joinToString(",")
            )
            Log.i(
                TAG,
                "MIX ZERO-POOL RESTORE PLAYBACK | preSourceGuard=true | " +
                    "sourceBatchGuard=" + request.zeroPoolSourceBatchGuardApplied +
                    " | position=2 | track=${expectedTrackIds[1]}"
            )
        }

        // Initial fallback tracks came from GMMP's own native Auto-DJ fill at
        // the natural Current anchor. Settled cleanup above changes no Track
        // identity; it only normalizes the visible/database queue after the
        // decoder/source guards have already been proven.
        armManagedAutoDjSession()
        request.stage = "FILLING"
        request.transitionalRefillSuppressed = false
        sendCommand(request.context, COMMAND_AUTO_DJ)
        nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 7_000L
        awaitAutoDjCommandRefillBoundary(request)

        Log.i(
            TAG,
            "MIX PREPLAY QUEUE | verified=true | initial=$initial | " +
                "smart=0 | nativeGenerated=${(initial - 1).coerceAtLeast(0)} | " +
                "current=$selectedId | currentPosition=1 | boundary=natural-current-pre-decoder"
        )
        Log.i(
            TAG,
            "MIX AUTO-DJ | command=sent-zero-pool-native-fallback | " +
                "initialSize=$initial | upcoming=$upcomingSetting | seed=$selectedId"
        )
        return filled
    }

    private fun waitForSelectedSong(request: Pending): LoadedPlay? {
        var targetIdentity: TrackMixPlaybackIdentityPolicy.Identity? = null
        var detectedAt = 0L
        var detectedQueueSize = -1
        var stableSnapshot: Snapshot? = null
        var stableAt = 0L
        var pathRebindApplied = false
        var transitionIdentity: TrackMixPlaybackIdentityPolicy.Identity? = null
        var transitionDetectedAt = 0L
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
            val sampleAt = SystemClock.elapsedRealtime()
            val targetRebindActive =
                targetIdentity != null &&
                    !pathRebindApplied &&
                    detectedAt > 0L &&
                    completionGuard > 0L &&
                    sampleAt - detectedAt <= completionGuard
            val transitionRebindActive =
                targetIdentity == null &&
                    transitionIdentity != null &&
                    transitionDetectedAt > 0L &&
                    completionGuard > 0L &&
                    sampleAt - transitionDetectedAt <= completionGuard
            val rebindPathHint = if (targetRebindActive || transitionRebindActive) {
                request.prePlayTarget?.path
            } else {
                null
            }
            val currentEntryHint = targetIdentity?.queueEntryId
                ?: if (transitionRebindActive) transitionIdentity?.queueEntryId else null

            if (
                targetIdentity == null &&
                request.source == "menu_gm_context_queue" &&
                request.nativePlayAccepted
            ) {
                val autoDj = nativeAutoDj?.get()
                val target = request.prePlayTarget
                if (autoDj != null && target != null) {
                    val located = queueReader.locateUniqueTrackRow(autoDj, target.id)
                    if (located != null) {
                        val pinned = queueReader.read(
                            autoDj,
                            currentQueueEntryIdHint = located.queueEntryId
                        )
                        val pinnedIndex = pinned?.items?.indexOfFirst {
                            it.queueEntryId == located.queueEntryId &&
                                it.track.id == target.id
                        } ?: -1
                        if (pinned != null && pinnedIndex >= 0) {
                            val snapshot = Snapshot(
                                ids = pinned.items.map { it.track.id },
                                currentIndex = pinnedIndex,
                                entryIds = pinned.items.map { it.queueEntryId },
                                currentResolutionSource = "queue-context-target",
                                currentQueuePosition = located.queuePosition
                            )
                            request.provisionalCurrentQueuePosition = located.queuePosition
                            Log.i(
                                TAG,
                                "MIX PLAY VERIFIED | source=queue-context-target" +
                                    " | targetEntry=${located.queueEntryId}" +
                                    " | targetTrack=${target.id}" +
                                    " | queueSize=${snapshot.ids.size}" +
                                    " | currentPosition=${located.queuePosition}" +
                                    " | evidence=exact-native-menu-play"
                            )
                            return LoadedPlay(snapshot, target.id)
                        }
                    }
                }
            }

            val current = queueSnapshot(
                currentQueueEntryIdHint = currentEntryHint,
                currentTrackPathHint = rebindPathHint
            ) ?: continue
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
                when (
                    TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(
                        expectedTrackId = request.prePlayTarget?.id,
                        hasPlaybackEvidence = true,
                        current = identity
                    )
                ) {
                    TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TRANSITION_ONLY -> {
                        if (transitionIdentity == null &&
                            completionGuard > 0L &&
                            identity.queueEntryId != null
                        ) {
                            transitionIdentity = identity
                            transitionDetectedAt = now
                            Log.i(
                                TAG,
                                "MIX PLAY TRANSITION | candidateEntry=" +
                                    identity.queueEntryId +
                                    " | candidateTrack=" + identity.trackId +
                                    " | expectedTrack=" +
                                    (request.prePlayTarget?.id ?: -1L) +
                                    " | completionGuardMs=" + completionGuard +
                                    " | targetDeferred=true"
                            )
                        }
                        continue
                    }
                    TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TARGET -> {
                        val recoveredAfterTransition =
                            transitionIdentity != null &&
                                current.currentResolutionSource ==
                                    "track-mix-path-rebind"
                        targetIdentity = identity
                        detectedAt = now
                        detectedQueueSize = current.ids.size
                        stableSnapshot = current
                        stableAt = now
                        transitionIdentity = null
                        transitionDetectedAt = 0L
                        request.provisionalCurrentQueuePosition =
                            current.currentQueuePosition
                        tryPromoteNextSourceBoundary(request)
                        Log.i(
                            TAG,
                            "MIX PLAY DETECTED | source=" +
                                (if (recoveredAfterTransition)
                                    "preplay-exact-path-after-transition"
                                else "queue-current-change") +
                                " | entry=" + (identity.queueEntryId ?: -1L) +
                                " | track=" + identity.trackId +
                                " | queueSize=" + current.ids.size +
                                " | completionGuardMs=" + completionGuard
                        )
                        continue
                    }
                    TrackMixPlaybackIdentityPolicy.InitialCandidateRole.IGNORE -> Unit
                }
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
                ) &&
                (request.prePlayTarget == null ||
                    identity.trackId == request.prePlayTarget?.id)
            ) {
                targetIdentity = identity
                detectedAt = now
                detectedQueueSize = current.ids.size
                stableSnapshot = current
                stableAt = now
                request.provisionalCurrentQueuePosition = current.currentQueuePosition
                tryPromoteNextSourceBoundary(request)
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
            if (current.currentResolutionSource == "track-mix-path-rebind") {
                val replacementEntry = identity.queueEntryId
                if (
                    replacementEntry != null &&
                    replacementEntry != target.queueEntryId &&
                    identity.trackId == target.trackId
                ) {
                    pathRebindApplied = true
                    Log.i(
                        TAG,
                        "MIX CURRENT REBIND | oldEntry=" +
                            (target.queueEntryId ?: -1L) +
                            " | newEntry=" + replacementEntry +
                            " | track=" + identity.trackId +
                            " | source=exact-path | verified=true"
                    )
                    targetIdentity = identity
                    detectedAt = now
                    detectedQueueSize = current.ids.size
                    stableSnapshot = current
                    stableAt = now
                    continue
                }
                Log.w(
                    TAG,
                    "MIX CURRENT REBIND | rejected=true" +
                        " | oldEntry=" + (target.queueEntryId ?: -1L) +
                        " | candidateEntry=" + (replacementEntry ?: -1L) +
                        " | oldTrack=" + target.trackId +
                        " | candidateTrack=" + identity.trackId
                )
                stableSnapshot = null
                stableAt = now
                continue
            }
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
        currentQueueEntryIdHint: Long? = null,
        condition: (Snapshot) -> Boolean
    ): Snapshot? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (isCurrent(request) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(175)
            val snapshot = queueSnapshot(currentQueueEntryIdHint)
            if (snapshot != null && condition(snapshot)) return snapshot
        }
        return null
    }

    private fun isCurrent(request: Pending): Boolean =
        pending === request

    private fun queueSnapshot(
        currentQueueEntryIdHint: Long? = null,
        currentTrackPathHint: String? = null
    ): Snapshot? = runCatching {
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
                positionWriter?.read(),
                currentQueueEntryIdHint,
                currentTrackPathHint
            ) ?: return@runCatching null
            val currentIndex = context.items.indexOfFirst {
                it.state == QueueItemState.CURRENT
            }
            if (currentIndex < 0) return@runCatching null
            return@runCatching Snapshot(
                context.items.map { it.track.id },
                currentIndex,
                context.items.map { it.queueEntryId },
                context.currentResolutionSource,
                context.currentQueuePosition
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
        Snapshot(
            ids = ids,
            currentIndex = currentIndex,
            entryIds = entryIds,
            currentQueuePosition = current
        )
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

    /**
     * GoneSmart's own zero-match fallback notice can be emitted while the
     * short Track Mix native-popup suppression window is active. Route only
     * that established status notice through the same own-Toast exemption as
     * the Track Mix confirmation; native GMMP transition Toasts stay hidden.
     */
    fun showOwnedStatusNotice(noticeKey: String): Boolean {
        if (noticeKey != "no-matches-native-fallback") return false
        val request = pending
        val action = TrackMixInitialFillPolicy.noMatchNoticeAction(
            hasPendingRequest = request != null,
            stageDone = request?.stage == "DONE",
            preparationComplete = request?.prePlayPreparationComplete == true,
            targetResolved = request?.prePlayTarget != null,
            preparedTrackCount = request?.prePlayPrepared?.trackIds?.size ?: 0
        )
        when (action) {
            TrackMixInitialFillPolicy.NoMatchNoticeAction.PASS_THROUGH -> return false
            TrackMixInitialFillPolicy.NoMatchNoticeAction.DEFER -> {
                request?.deferredNoMatchNotice = true
                Log.i(TAG, "MIX NOTICE | key=$noticeKey | route=deferred-preplay")
                return true
            }
            TrackMixInitialFillPolicy.NoMatchNoticeAction.SUPPRESS_PREPARED -> {
                request?.deferredNoMatchNotice = false
                Log.i(
                    TAG,
                    "MIX NOTICE | key=$noticeKey | route=suppressed-prepared" +
                        " | prepared=${request?.prePlayPrepared?.trackIds?.size ?: 0}"
                )
                return true
            }
            TrackMixInitialFillPolicy.NoMatchNoticeAction.SHOW_OWNED -> Unit
        }
        request ?: return false
        if (request.noMatchNoticeShown) return true
        request.noMatchNoticeShown = true
        Log.i(
            TAG,
            "MIX NOTICE | key=$noticeKey | route=owned-toast | prepared=0"
        )
        toast(
            request.context,
            NativeGmmpUiText.smartDjNotice(request.context, noticeKey)
        )
        return true
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

    private fun menuSelectionContext(
        menu: Menu,
        nativePlay: MenuItem
    ): MenuSelectionContext {
        val callback = runCatching { field(menu, "mCallback") }.getOrNull()
        val popup = callback?.let {
            runCatching { field(it, "this$0") }.getOrNull()
        }
        val listener = popup?.let {
            runCatching {
                field(it, "mMenuItemClickListener")
                    ?: field(it, "mOnMenuItemClickListener")
            }.getOrNull()
        }
        return MenuSelectionContext(
            callback = callback,
            popup = popup,
            listener = listener,
            roots = listOfNotNull(
                listener, popup, callback, menu, nativePlay
            ).distinctBy { System.identityHashCode(it) }
        )
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
