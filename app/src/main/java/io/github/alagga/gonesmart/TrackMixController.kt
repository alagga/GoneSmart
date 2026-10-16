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

    fun shouldSuppressNativeSnackbar(): Boolean = shouldSuppressIntermediatePopup()

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

    fun observeNativePositionCommand(
        service: Any?,
        method: java.lang.reflect.Method,
        value: Int?
    ) {
        val request = pending ?: return
        if (request.stage != "WAIT_PLAY" &&
            request.stage != "ISOLATING" &&
            request.stage != "FILLING"
        ) return
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
        val observed = ObservedNativePositionCommand(
            key = key,
            service = WeakReference(service),
            method = method.apply { isAccessible = true },
            value = value,
            looper = Looper.myLooper(),
            observedAt = SystemClock.elapsedRealtime()
        )
        val existingIndex = request.nativePositionCommands.indexOfFirst {
            it.key == key
        }
        if (existingIndex >= 0) {
            request.nativePositionCommands[existingIndex] = observed
        } else {
            if (request.nativePositionCommands.size >= 12) return
            request.nativePositionCommands += observed
        }

        prepareInitialQueueBeforeNaturalNextSource(
            request = request,
            observedArgument = value
        )
        tryPromoteNextSourceBoundary(request)
        guardZeroPoolNaturalNextSource(
            request = request,
            service = service,
            method = method,
            value = value,
            key = key
        )
    }

    /**
     * 4.2.1 eagerly materializes CURRENT+1 during native Play. For non-Queue
     * context Track Mix, use that already-proven natural boundary only as a
     * synchronous barrier: establish the exact 4.2.0 seed/Initial-Size state
     * before GMMP's original call proceeds. Never replay the playback method.
     */
    private fun prepareInitialQueueBeforeNaturalNextSource(
        request: Pending,
        observedArgument: Int
    ) {
        if (request.source == "menu_gm_context_queue") return
        if (request.stage != "WAIT_PLAY") return
        if (request.prePlayInitialSize <= 1) return
        if (request.zeroPoolBoundaryPrepared || request.zeroPoolBoundaryAttempted) return
        val target = request.prePlayTarget ?: return
        val autoDj = nativeAutoDj?.get() ?: return
        val located = queueReader.locateUniqueTrackRow(autoDj, target.id) ?: return
        if (located.queuePosition != 1 || observedArgument != 2) return
        val currentWriter = nativeTrackMixPositionWriterProvider(autoDj, 1) ?: return
        if (runCatching { currentWriter.read() }.getOrNull() != 1) return
        if (!zeroPoolBoundaryInFlight.compareAndSet(false, true)) return

        request.zeroPoolBoundaryAttempted = true
        try {
            check(isCurrent(request) && request.stage == "WAIT_PLAY") {
                "Track Auto-DJ request changed before natural CURRENT+1 boundary"
            }
            request.verifiedCurrentQueueEntryId = located.queueEntryId
            request.provisionalCurrentQueuePosition = 1
            request.stage = "ISOLATING"
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = currentWriter,
                    currentQueueEntryIdHint = located.queueEntryId
                ).isolateTrackMixSeed(target.id)
            ) {
                "Could not isolate selected Track Auto-DJ seed before CURRENT+1"
            }

            val isolated = queueReader.read(
                autoDj,
                currentWriter.read(),
                located.queueEntryId
            ) ?: error("Predecoder seed verification unavailable")
            check(
                isolated.items.size == 1 &&
                    isolated.currentQueuePosition == 1 &&
                    isolated.currentItem?.queueEntryId == located.queueEntryId &&
                    isolated.currentItem?.track?.id == target.id
            ) {
                "Predecoder seed isolation did not preserve the selected Current"
            }
            check(resetRecommendationSession(autoDj, target.id, located.queueEntryId)) {
                "Could not reset recommendation session before predecoder fill"
            }

            val initial = request.prePlayInitialSize.coerceAtLeast(1)
            val refillCount = (initial - 1).coerceAtLeast(0)
            request.refillObserved = false
            request.refillRequestedTracks.set(0)
            request.transitionalRefillSuppressed = false
            request.stage = if (request.prePlayPrepared.trackIds.isEmpty()) {
                "FILLING"
            } else {
                "WAIT_PLAY"
            }
            check(requestHeldNativeRefill(refillCount)) {
                "GMMP rejected predecoder Initial Size refill"
            }

            val finalWriter = nativeTrackMixPositionWriterProvider(autoDj, 1)
                ?: nativePositionWriterProvider(autoDj)
                ?: currentWriter
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = finalWriter,
                    currentQueueEntryIdHint = located.queueEntryId
                ).rebaseQueueToOneIfNeeded()
            ) {
                "Could not normalize predecoder Initial Size"
            }
            val verified = queueReader.read(
                autoDj,
                finalWriter.read(),
                located.queueEntryId
            ) ?: error("Predecoder Initial Size verification unavailable")
            val ordered = verified.items.sortedBy { it.queuePosition }
            check(
                verified.currentQueuePosition == 1 &&
                    verified.currentItem?.queueEntryId == located.queueEntryId &&
                    verified.currentItem?.track?.id == target.id &&
                    ordered.size == initial &&
                    ordered.map { it.queuePosition } == (1..initial).toList()
            ) {
                "Predecoder Initial Size is not an exact contiguous native queue"
            }

            val expectedSmart = request.prePlayPrepared.trackIds
                .take(request.bootstrapAppliedTrackIds.size)
            if (request.prePlayPrepared.trackIds.isNotEmpty()) {
                check(expectedSmart.isNotEmpty()) {
                    "Prepared Track Auto-DJ selection never reached native refill"
                }
                expectedSmart.forEachIndexed { index, trackId ->
                    check(ordered.getOrNull(index + 1)?.track?.id == trackId) {
                        "Prepared Track Auto-DJ identity changed before decoder"
                    }
                }
            }

            request.zeroPoolNativeFallbackTrackIds = ordered.drop(1).map { it.track.id }
            TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = target.id,
                nativeFallbackTrackIds = request.zeroPoolNativeFallbackTrackIds,
                expectedSize = initial
            )
            check(ensureZeroPoolSourceBatchHooks(autoDj)) {
                "Verified native Queue DAO source-batch hooks are unavailable"
            }
            request.provisionalCurrentQueuePosition = 1
            request.zeroPoolBoundaryPrepared = true
            request.stage = "WAIT_PLAY"
            Log.i(
                TAG,
                "MIX PREDECODE INITIAL | verified=true | boundary=natural-current-plus-one | " +
                    "seed=${target.id} | initial=$initial | smart=${expectedSmart.size} | " +
                    "native=${(initial - 1 - expectedSmart.size).coerceAtLeast(0)} | " +
                    "currentPosition=1 | playbackMethodInvoked=false"
            )
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            Log.e(
                TAG,
                "MIX PREDECODE INITIAL | preparation failed; fail closed",
                failure
            )
        } finally {
            zeroPoolBoundaryInFlight.set(false)
        }
    }

    private fun guardZeroPoolNaturalNextSource(
        request: Pending,
        service: Any,
        method: java.lang.reflect.Method,
        value: Int,
        key: String
    ) {
        if (request.zeroPoolNextSourceGuardAttempted) return
        if (!request.zeroPoolBoundaryPrepared) return
        if (request.stage != "WAIT_PLAY") return
        if (request.prePlayTarget == null) return
        val boundary = request.nextSourceBoundary ?: return
        if (request.nextSourceBoundaryAmbiguous || boundary.key != key) return
        if (boundary.service.get() !== service || boundary.method != method) return
        val autoDj = nativeAutoDj?.get() ?: return
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
            runCatching { resolvePrePlayTarget(autoDj, items) }.getOrNull()
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

    fun narrowNativePlaybackListForZeroPool(
        action: Int?,
        nativeItems: List<*>?
    ): List<*>? = null

    fun prepareZeroPoolAtNativeRefill(
        autoDj: Any,
        requestedTracks: Int
    ): ZeroPoolNativeRefillBoundary? = null

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
            Log.i(
                TAG,
                "MIX ZERO-POOL BOUNDARY | verified=true | beforeDecoder=true" +
                    " | source=natural-native-refill | seed=${boundary.selectedTrackId}" +
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
        if (currentId != target.id) return null

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
            Log.i(TAG, "MIX NATIVE REFILL | requested=$requested | totalRequested=$total")
        }
    }

    fun onMenuInflated(menuResId: Int, menu: Menu?, inflater: Any?) {
        if (menu == null) return
        val context = menuContext(menu, inflater) ?: return
        if (context.packageName != GMMP_PACKAGE) return
        val name = runCatching { context.resources.getResourceEntryName(menuResId) }.getOrNull() ?: return
        if (name !in SONG_MENUS) return
        menu.findItem(MIX_ITEM_ID)?.let {
            it.isVisible = enabled
            return
        }
        if (!enabled) return
        val playId = context.resources.getIdentifier("menuContextPlay", "id", GMMP_PACKAGE)
        val nextId = context.resources.getIdentifier("menuContextPlayNext", "id", GMMP_PACKAGE)
        val nativePlay = menu.findItem(playId) ?: return
        val nativeNext = menu.findItem(nextId) ?: return
        val language = context.resources.configuration.locales[0].language
        fun nativeText(resource: String): String? {
            val resourceId = context.resources.getIdentifier(resource, "string", GMMP_PACKAGE)
            if (resourceId == 0) return null
            return runCatching { context.resources.getString(resourceId) }.getOrNull()
        }
        val label = TrackMixPlan.localizedMenuLabel(
            language = language,
            nativeTrack = nativeText("track"),
            nativeAutoDj = nativeText("auto_dj")
        )
        if (label.isBlank()) return
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
        Log.i(TAG, "MIX MENU | menu=$name | positionAfterPlayNext=$inserted | nativePlay=${nativePlay.title}")
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
            Log.i(TAG, "MIX SKIPPED | previous Track Mix still starting")
            return
        }
        if (!nativePlay.isEnabled) {
            toast(context, "This song cannot be played.")
            return
        }

        val callback = runCatching { field(menu, "mCallback") }.getOrNull()
        val popup = callback?.let { runCatching { field(it, "this$0") }.getOrNull() }
        val listener = popup?.let {
            runCatching {
                field(it, "mMenuItemClickListener")
                    ?: field(it, "mOnMenuItemClickListener")
            }.getOrNull()
        }
        val prePlayRoots = listOfNotNull(listener, popup, callback, menu, nativePlay)
            .distinctBy { System.identityHashCode(it) }
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
                    if (pending === request) pending = null
                    main.post { fail("Could not access Auto-DJ for this song.", context) }
                    return@execute
                }
                request.stage = "PREPLAY_RESOLVE"
                val resolution = resolvePrePlayTarget(autoDj, prePlayRoots)
                if (resolution == null) {
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
                if (source == "menu_gm_context_queue") {
                    request.prePlayPrepared = TrackMixPreparedInitialPool.EMPTY
                    request.stage = "PREPARED"
                    Log.i(
                        TAG,
                        "MIX PREPLAY | target=${resolution.track.id} | initial=$initial | " +
                            "strategy=native-auto-dj"
                    )
                } else {
                    val requiredUpcoming = (initial - 1).coerceAtLeast(0)
                    request.stage = "PREPLAY_PREPARE"
                    request.prePlayPrepared = preparePrePlaySmartPool(
                        autoDj,
                        resolution.track,
                        requiredUpcoming
                    )
                    request.stage = "PREPARED"
                    Log.i(
                        TAG,
                        "MIX PREPLAY | target=${resolution.track.id} | initial=$initial | " +
                            "smartPrepared=${request.prePlayPrepared.trackIds.size} | " +
                            "strategy=predecoder-current-plus-one"
                    )
                }
            }

            main.post {
                if (!isCurrent(request)) return@post
                request.before = old
                request.stage = "WAIT_PLAY"
                refillHold.set(true)
                nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 8_000L
                suppressedToastCount.set(0)

                if (!enableSmartDj(context)) {
                    if (pending === request) pending = null
                    refillHold.set(false)
                    fail("Smart DJ could not be enabled.", context)
                    return@post
                }
                val started = runCatching { nativePlayDispatch() }
                    .onFailure { Log.e(TAG, "Native track Play failed", it) }
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

            if (!legacyQueue && request.zeroPoolBoundaryPrepared) {
                val selectedQueuePosition = loaded.currentQueuePosition ?: run {
                    fail("Could not identify the selected queue position safely.", request.context)
                    return
                }
                val filled = fillZeroPoolNativeFallback(
                    request = request,
                    selectedId = selectedId,
                    selectedEntryId = selectedEntryId,
                    selectedQueuePosition = selectedQueuePosition,
                    initial = initial,
                    upcomingSetting = upcoming
                ) ?: run {
                    fail("Could not preserve GMMP's predecoded Initial Size safely.", request.context)
                    return
                }
                val exactContext = nativeAutoDj?.get()?.let { autoDj ->
                    queueReader.read(autoDj, currentQueueEntryIdHint = selectedEntryId)
                }
                val positions = exactContext?.items?.sortedBy { it.queuePosition }
                    ?.map { it.queuePosition }.orEmpty()
                if (exactContext?.currentItem?.track?.id != selectedId ||
                    !TrackMixInitialFillPolicy.isExactInitialQueue(
                        initialSize = initial,
                        currentQueuePosition = exactContext?.currentQueuePosition,
                        queuePositions = positions
                    )
                ) {
                    fail("GMMP's predecoded Initial Size was not normalized correctly.", request.context)
                    return
                }
                managedSessionReady = managedAutoDj?.get() != null
                Log.i(
                    TAG,
                    "MIX VERIFIED | initial=$initial | actual=${filled.ids.size} | " +
                        "currentPreserved=true | tracksAfterSeed=${filled.ids.size - 1} | " +
                        "owner=predecoder-GMMP"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "${request.menuLabel}: ${filled.ids.size - 1} tracks queued after the selected song."
                )
                toast(request.context, request.confirmation)
                nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 3_000L
                return
            }

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
                fail("Could not verify the isolated Auto-DJ seed.", request.context)
                return
            }
            request.provisionalCurrentQueuePosition = seed.currentQueuePosition
            tryPromoteNextSourceBoundary(request)

            val sessionAutoDj = nativeAutoDj?.get()
            if (sessionAutoDj == null ||
                !resetRecommendationSession(sessionAutoDj, selectedId, selectedEntryId)
            ) {
                fail("Could not start a fresh recommendation session for this song.", request.context)
                return
            }
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
            val orderedPositions = exactContext?.items?.sortedBy { it.queuePosition }
                ?.map { it.queuePosition }.orEmpty()
            if (exactContext?.currentItem?.track?.id != selectedId ||
                !TrackMixInitialFillPolicy.isExactInitialQueue(
                    initialSize = initial,
                    currentQueuePosition = exactContext?.currentQueuePosition,
                    queuePositions = orderedPositions
                )
            ) {
                fail("GMMP's Initial Size was not normalized correctly.", request.context)
                return
            }

            // Do not replay MusicService CURRENT+1 after decoder materialization.
            // R49/R50 device evidence proved that it is not a player-cache
            // refresh and can disturb playback. Either the predecoder boundary
            // owned CURRENT+1, or this request remains on GMMP's native path.
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
                Log.i(TAG, "MIX CONTINUITY | retained after verified Initial Size")
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
                if (snapshot.ids.size > 1) return null
            }
            if (!request.refillObserved &&
                SystemClock.elapsedRealtime() >= commandDeadline
            ) return null
            Thread.sleep(100L)
        }
        return null
    }

    private fun isolateNativeSeed(
        request: Pending,
        selectedTrackId: Long
    ): Snapshot? {
        val queue = nativeQueue?.get()
            ?.takeIf { it.javaClass.name == "ex3" }
            ?: nativeAutoDj?.get()?.let { field(it, "q") }
                ?.takeIf { it.javaClass.name == "ex3" }
            ?: return null
        val dao = field(queue, "r") ?: return null
        val read = dao.javaClass.getMethod("H1")
        val position = queue.javaClass.getDeclaredMethod("D").apply { isAccessible = true }
        val positionField = queue.javaClass.getDeclaredField("p").apply { isAccessible = true }
        fun entries(): List<Any> =
            ((read.invoke(dao) as? List<*>) ?: error("Native queue cannot be read"))
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
        val plan = TrackMixPlan.planNativeIsolation(before, originalPosition, selectedTrackId)
        if (plan.removeEntryIds.isEmpty()) {
            return Snapshot(listOf(selectedTrackId), 0, listOf(plan.selectedEntryId))
        }
        val entryType = originals.first().javaClass
        val stale = originals.filter {
            (field(it, "d") as Number).toLong() != plan.selectedEntryId
        }
        val staleArray = java.lang.reflect.Array.newInstance(entryType, stale.size)
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
                        check(isCurrent(request))
                        check(refs(entries()) == before &&
                            (position.invoke(queue) as? Int) == originalPosition)
                        dao.javaClass.getMethod("O", Array<Any>::class.java)
                            .invoke(dao, staleArray)
                        val selected = originals.first {
                            (field(it, "d") as Number).toLong() == plan.selectedEntryId
                        }
                        selected.javaClass.getDeclaredField("a").apply {
                            isAccessible = true
                        }.setInt(selected, 1)
                        dao.javaClass.getMethod("O0", List::class.java)
                            .invoke(dao, arrayListOf(selected))
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
        queue.javaClass.getDeclaredMethod("c", nativeInterface)
            .apply { isAccessible = true }.invoke(queue, callback)
        mutationError?.let { throw it }
        check(mutationPerformed)
        val after = refs(entries())
        check(after.size == 1 &&
            after[0].queueId == plan.selectedEntryId &&
            after[0].trackId == selectedTrackId &&
            after[0].position == 1)
        positionField.setInt(queue, 1)
        queue.javaClass.getDeclaredMethod("b2", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(queue, 1)
        check((position.invoke(queue) as? Int) == 1)
        return Snapshot(listOf(selectedTrackId), 0, listOf(plan.selectedEntryId))
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
        val writer = nativeTrackMixPositionWriterProvider(autoDj, currentPosition) ?: return
        val readback = runCatching { writer.read() }.getOrNull() ?: return
        if (readback != currentPosition) return
        prepareZeroPoolAtNaturalCurrentBoundary(autoDj, currentPosition)
    }

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
        }.getOrNull() ?: return
        val current = boundaryContext.currentItem ?: return
        if (current.track.id != target.id) return
        val currentWriter = nativeTrackMixPositionWriterProvider(autoDj, position) ?: return
        if (!zeroPoolBoundaryInFlight.compareAndSet(false, true)) return
        request.zeroPoolBoundaryAttempted = true
        try {
            request.verifiedCurrentQueueEntryId = current.queueEntryId
            request.provisionalCurrentQueuePosition = position
            request.stage = "ZERO_POOL_BOUNDARY"
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = currentWriter,
                    currentQueueEntryIdHint = current.queueEntryId
                ).isolateTrackMixZeroPoolSeed(target.id)
            )
            val initial = request.prePlayInitialSize.coerceAtLeast(1)
            val refillCount = (initial - 1).coerceAtLeast(0)
            request.stage = "FILLING"
            request.transitionalRefillSuppressed = false
            check(requestHeldNativeRefill(refillCount))
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
                    orderedVerified.map { it.queuePosition } == expectedPositions
            )
            request.zeroPoolNativeFallbackTrackIds = orderedVerified.drop(1).map { it.track.id }
            check(ensureZeroPoolSourceBatchHooks(autoDj))
            request.provisionalCurrentQueuePosition = position
            request.zeroPoolBoundaryPrepared = true
            request.stage = "WAIT_PLAY"
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            Log.e(TAG, "MIX ZERO-POOL BOUNDARY | preparation failed; fail closed", failure)
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
        }.getOrNull() ?: return
        request.zeroPoolSourceBatchGuardApplied = true
        Log.i(
            TAG,
            "MIX ZERO-POOL SOURCE BATCH | applied=true | beforeOriginal=true" +
                " | writer=" + method.declaringClass.name + "." + method.name +
                "(" + method.parameterTypes.firstOrNull()?.name + ")" +
                " | size=" + result.batchSize +
                " | currentPosition=" + result.currentPosition +
                " | changed=" + result.changed
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
        if (!request.zeroPoolBoundaryPrepared) return null
        val expectedTrackIds = runCatching {
            TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = selectedId,
                nativeFallbackTrackIds = request.zeroPoolNativeFallbackTrackIds,
                expectedSize = initial
            )
        }.getOrNull() ?: return null
        fun exactSnapshot(entryId: Long): Snapshot? =
            queueSnapshot(entryId)?.takeIf { snapshot ->
                snapshot.currentId == selectedId &&
                    snapshot.currentIndex == 0 &&
                    snapshot.ids == expectedTrackIds
            }
        fun finalSnapshot(entryId: Long): Snapshot? =
            exactSnapshot(entryId)?.takeIf { it.currentQueuePosition == 1 }

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
            if (!request.zeroPoolNextSourceGuardApplied) return null
            if (sourceRebuild && !request.zeroPoolSourceBatchGuardApplied) return null
            val cleanupPosition = intactAnchoredFallback?.currentQueuePosition
                ?: selectedQueuePosition
            if (cleanupPosition == null || cleanupPosition <= 0) return null
            val autoDj = nativeAutoDj?.get() ?: return null
            val currentWriter = nativeTrackMixPositionWriterProvider(
                autoDj,
                cleanupPosition
            ) ?: return null
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
                    )
                }
                check(
                    bridge.rebaseTrackMixZeroPoolInitialQueue(
                        selectedTrackId = selectedId,
                        expectedSize = initial
                    )
                )
                true
            }.getOrDefault(false)
            if (!restored) return null
            request.verifiedCurrentQueueEntryId = selectedEntryId
            request.provisionalCurrentQueuePosition = 1
            filled = finalSnapshot(selectedEntryId) ?: return null
            Log.i(
                TAG,
                "MIX ZERO-POOL RESTORE | verified=true | sourceRebuild=$sourceRebuild | " +
                    "queueIdentityRestored=true | oldCurrentPosition=$cleanupPosition | " +
                    "currentPosition=1 | nativeFallback=" +
                    request.zeroPoolNativeFallbackTrackIds.joinToString(",")
            )
        }

        armManagedAutoDjSession()
        request.stage = "FILLING"
        request.transitionalRefillSuppressed = false
        sendCommand(request.context, COMMAND_AUTO_DJ)
        nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 7_000L
        awaitAutoDjCommandRefillBoundary(request)
        Log.i(
            TAG,
            "MIX PREPLAY QUEUE | verified=true | initial=$initial | " +
                "smart=${request.bootstrapAppliedTrackIds.size} | " +
                "nativeGenerated=${(initial - 1 - request.bootstrapAppliedTrackIds.size).coerceAtLeast(0)} | " +
                "current=$selectedId | currentPosition=1 | boundary=natural-current-plus-one-predecoder"
        )
        Log.i(
            TAG,
            "MIX AUTO-DJ | command=sent-predecoded-initial | " +
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
            TrackMixQueueSettlingPolicy.requiredCompletionGuardMs(request.source)

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
            } else null
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
            val changed = TrackMixPlaybackIdentityPolicy.changed(before, identity)
            val playbackChanged = TrackMixPlaybackIdentityPolicy.playbackChanged(before, identity)
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
                        }
                        continue
                    }
                    TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TARGET -> {
                        targetIdentity = identity
                        detectedAt = now
                        detectedQueueSize = current.ids.size
                        stableSnapshot = current
                        stableAt = now
                        transitionIdentity = null
                        transitionDetectedAt = 0L
                        request.provisionalCurrentQueuePosition = current.currentQueuePosition
                        tryPromoteNextSourceBoundary(request)
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
                (request.prePlayTarget == null || identity.trackId == request.prePlayTarget?.id)
            ) {
                targetIdentity = identity
                detectedAt = now
                detectedQueueSize = current.ids.size
                stableSnapshot = current
                stableAt = now
                request.provisionalCurrentQueuePosition = current.currentQueuePosition
                tryPromoteNextSourceBoundary(request)
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
                    targetIdentity = identity
                    detectedAt = now
                    detectedQueueSize = current.ids.size
                    stableSnapshot = current
                    stableAt = now
                    continue
                }
                stableSnapshot = null
                stableAt = now
                continue
            }
            val currentStillSelected = TrackMixPlaybackIdentityPolicy.sameCurrent(target, identity)
            val selectedOccurrences = current.ids.count { it == target.trackId }
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
                    targetIdentity = identity
                    detectedAt = now
                    detectedQueueSize = current.ids.size
                    stableSnapshot = current
                    stableAt = now
                    continue
                }
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
                        " | rebuilt=" + (current.ids.size != detectedQueueSize) +
                        " | nativeSignal=" + request.nativePlaySignal +
                        " | changed=" + changed
                )
                return LoadedPlay(current, target.trackId)
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

    private fun isCurrent(request: Pending): Boolean = pending === request

    private fun queueSnapshot(
        currentQueueEntryIdHint: Long? = null,
        currentTrackPathHint: String? = null
    ): Snapshot? = runCatching {
        nativeAutoDj?.get()?.let { autoDj ->
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

        val queue = nativeQueue?.get()
            ?.takeIf { it.javaClass.name == "ex3" }
            ?: return@runCatching null
        val dao = field(queue, "r") ?: return@runCatching null
        val raw = dao.javaClass.getMethod("H1").invoke(dao) as? List<*>
            ?: return@runCatching null
        val sorted = raw.filterNotNull().sortedBy {
            (field(it, "a") as Number).toInt()
        }
        val ids = sorted.map { (field(it, "b") as Number).toLong() }
        val entryIds = sorted.map { (field(it, "d") as Number).toLong() }
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

    fun showOwnedStatusNotice(noticeKey: String): Boolean {
        if (noticeKey != "no-matches-native-fallback") return false
        val request = pending ?: return false
        if (request.prePlayTarget == null || request.stage == "DONE") return false
        Log.i(TAG, "MIX NOTICE | key=$noticeKey | route=owned-toast")
        toast(
            request.context,
            NativeGmmpUiText.smartDjNotice(request.context, noticeKey)
        )
        return true
    }

    private fun toast(context: Context, message: String) {
        main.post {
            val notification = Toast.makeText(context, message, Toast.LENGTH_SHORT)
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
