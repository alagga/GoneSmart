from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


# Share the already passively-verified native queue-position writer with Track Mix.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/QueueFlipController.kt",
    '''    fun captureNativeAutoDj(candidate: Any?) {
        if (candidate != null) nativeAutoDj = WeakReference(candidate)
    }

    /**''',
    '''    fun captureNativeAutoDj(candidate: Any?) {
        if (candidate != null) nativeAutoDj = WeakReference(candidate)
    }

    fun verifiedPositionWriter(autoDj: Any): NativeQueuePositionWriter? =
        positionWriterObserver.binding(autoDj)

    /**'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    '''    private val trackMixController = TrackMixController(
        enableSmartDj = { context -> enableSmartDjForTrackMix(context) },
        requestNativeRefill = { count -> requestNativeTrackMixRefill(count) }
    )''',
    '''    private val trackMixController = TrackMixController(
        enableSmartDj = { context -> enableSmartDjForTrackMix(context) },
        requestNativeRefill = { count -> requestNativeTrackMixRefill(count) },
        nativePositionWriterProvider = { autoDj ->
            queueFlipController.verifiedPositionWriter(autoDj)
        }
    )'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''internal class TrackMixController(
    private val enableSmartDj: (Context) -> Boolean,
    private val requestNativeRefill: (Int) -> Boolean
) {''',
    '''internal class TrackMixController(
    private val enableSmartDj: (Context) -> Boolean,
    private val requestNativeRefill: (Int) -> Boolean,
    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null }
) {'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''                    GmmpQueueMutationBridge(autoDj)
                        .isolateCurrentTrack(selectedTrackId)''',
    '''                    GmmpQueueMutationBridge(
                        autoDj = autoDj,
                        verifiedPositionWriter = nativePositionWriterProvider(autoDj)
                    ).isolateCurrentTrack(selectedTrackId)'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''        while (isCurrent(request) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(160)
            val current = queueSnapshot() ?: continue''',
    '''        while (isCurrent(request) && SystemClock.elapsedRealtime() < deadline) {
            // Before selection is proven, sample quickly enough to catch the
            // native Play transition. Afterwards the passively verified
            // position writer owns CURRENT, so a slower structural cadence is
            // sufficient and avoids repeatedly materializing a large queue.
            Thread.sleep(if (targetIdentity == null) 160L else 400L)
            val current = queueSnapshot() ?: continue'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''        nativeAutoDj?.get()?.let { autoDj ->
            // 4.2.1 can briefly have no uniquely resolvable CURRENT while
            // native Play rebuilds the source. That is a retryable miss, not
            // evidence that the 4.2.0 queue wrapper should be used.
            val context = queueReader.read(autoDj) ?: return@runCatching null''',
    '''        nativeAutoDj?.get()?.let { autoDj ->
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
            ) ?: return@runCatching null'''
)

# Stop an absent alternate navigation surface from causing endless full-decor scans.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt",
    '''        private const val TAG = "GoneSmartPlaylist"
        private const val RESCAN_DEBOUNCE_MS = 350L
    }''',
    '''        private const val TAG = "GoneSmartPlaylist"
        private const val RESCAN_DEBOUNCE_MS = 350L
        private const val INITIAL_RECOVERY_RESCAN_MS = 900L
    }'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt",
    '''    private var lastRefreshElapsed = Long.MIN_VALUE
    private var cachedPlaylistTargets = emptyList<WeakReference<TextView>>()
    private var cachedSmartTargets = emptyList<WeakReference<TextView>>()
    private val adapterGetterCache = mutableMapOf<Class<*>, Method?>()

    private val globalLayoutListener =''',
    '''    private var lastRefreshElapsed = Long.MIN_VALUE
    private var cachedPlaylistTargets = emptyList<WeakReference<TextView>>()
    private var cachedSmartTargets = emptyList<WeakReference<TextView>>()
    private var negativeDiscoverySettled = false
    private val adapterGetterCache = mutableMapOf<Class<*>, Method?>()

    private val recoveryRescanRunnable = Runnable {
        val activity = activityRef?.get() ?: return@Runnable
        if (activity.isFinishing || activity.isDestroyed) return@Runnable
        negativeDiscoverySettled = false
        scheduleRefresh(force = true)
    }

    private val globalLayoutListener ='''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt",
    '''        playlistEnabled = playlistFoldersEnabled
        smartEnabled = smartPlaylistFoldersEnabled
        if (changed) scheduleRefresh(force = true)
    }''',
    '''        playlistEnabled = playlistFoldersEnabled
        smartEnabled = smartPlaylistFoldersEnabled
        if (changed) {
            negativeDiscoverySettled = false
            scheduleRefresh(force = true)
            scheduleInitialRecoveryRescan()
        }
    }'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt",
    '''        if (previous !== decor) {
            previous?.viewTreeObserver
                ?.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(globalLayoutListener)
            decorRef = WeakReference(decor)
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.addOnGlobalLayoutListener(
                    globalLayoutListener
                )
            }
        }
        scheduleRefresh(force = true)
    }''',
    '''        if (previous !== decor) {
            previous?.viewTreeObserver
                ?.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(globalLayoutListener)
            decorRef = WeakReference(decor)
            negativeDiscoverySettled = false
            cachedPlaylistTargets = emptyList()
            cachedSmartTargets = emptyList()
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.addOnGlobalLayoutListener(
                    globalLayoutListener
                )
            }
            scheduleRefresh(force = true)
            scheduleInitialRecoveryRescan()
            return
        }
        scheduleRefresh()
    }

    private fun scheduleInitialRecoveryRescan() {
        main.removeCallbacks(recoveryRescanRunnable)
        main.postDelayed(
            recoveryRescanRunnable,
            INITIAL_RECOVERY_RESCAN_MS
        )
    }'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt",
    '''        if (!playlistEnabled && !smartEnabled &&
            playlistOriginals.isEmpty() && smartOriginals.isEmpty()
        ) return

        forceRefreshPending = forceRefreshPending || force''',
    '''        if (!playlistEnabled && !smartEnabled &&
            playlistOriginals.isEmpty() && smartOriginals.isEmpty()
        ) return

        // Some GMMP navigation modes expose only the classic drawer. Once an
        // initial scan plus one delayed recovery scan have proved that no
        // alternate Playlist labels exist, global-layout waves must be free.
        // A decor replacement, option change or invalidated cached target
        // explicitly reopens structural discovery.
        if (!force && negativeDiscoverySettled &&
            cachedPlaylistTargets.isEmpty() &&
            cachedSmartTargets.isEmpty()
        ) return

        forceRefreshPending = forceRefreshPending || force'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt",
    '''        cachedPlaylistTargets = playlistTargets.map(::WeakReference)
        cachedSmartTargets = smartTargets.map(::WeakReference)

        restoreMissing(playlistOriginals, playlistTargets)''',
    '''        cachedPlaylistTargets = playlistTargets.map(::WeakReference)
        cachedSmartTargets = smartTargets.map(::WeakReference)
        negativeDiscoverySettled =
            playlistTargets.isEmpty() && smartTargets.isEmpty()

        restoreMissing(playlistOriginals, playlistTargets)'''
)

# Persist the two regression lessons in the repo playbook.
agents = Path("AGENTS.md")
text = agents.read_text()
anchor = '''- Smart Auto-DJ latency diagnostics must distinguish queue-read-before, pool hit/fill, native refill, queue-read-after and total time so future regressions can be localized without multiple probe APKs.\n'''
addition = '''- Smart Auto-DJ latency diagnostics must distinguish queue-read-before, pool hit/fill, native refill, queue-read-after and total time so future regressions can be localized without multiple probe APKs.\n- If GMMP has already passively proved a native queue-position writer/readback for the live Auto-DJ instance, all Track Mix CURRENT checks and queue mutations must reuse that proof. Do not restart broad integer/getter discovery on every settling poll; transient structural CURRENT ambiguity is not evidence that the selected native Play failed.\n- Negative UI discovery is cacheable. If an initial scan plus one bounded delayed recovery prove that a navigation surface has no alternate Playlist/Smart-Playlist targets, persistent global-layout callbacks must become no-ops until the decor/options/cached target identity actually changes.\n'''
if anchor not in text:
    raise SystemExit("AGENTS runtime invariant anchor missing")
agents.write_text(text.replace(anchor, addition, 1))

print("runtime regression patch applied")
