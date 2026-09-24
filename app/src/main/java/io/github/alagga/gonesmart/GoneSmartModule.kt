package io.github.alagga.gonesmart

import android.app.Activity
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GoneSmartModule : XposedModule() {

    companion object {

        private const val TAG =
            "GoneSmart"

        private const val GMMP_PACKAGE =
            "gonemad.gmmp"

        private const val MAX_RECORDING_MATCHES_TO_TRY =
            5

        private const val LASTFM_RESULT_LIMIT =
            50

        private const val BROAD_LASTFM_RESULT_LIMIT =
            150

        private const val BROAD_LISTENBRAINZ_MAX_RECORDING_MATCHES =
            8

        private const val BROAD_LISTENBRAINZ_IDENTITIES_TO_USE =
            3

        private const val MERGED_RESULTS_TO_LOG =
            20

        private const val LOCAL_RESULTS_TO_LOG =
            20

        private const val SMART_PREPARE_TIMEOUT_SECONDS =
            60L

        private const val RECENT_DUPLICATE_HISTORY_LIMIT =
            8

        private const val STARTUP_PREWARM_STATUS_DELAY_MS =
            700L

        private const val STARTUP_PREWARM_MAX_ATTEMPTS =
            20

        private const val STARTUP_PREWARM_RETRY_DELAY_MS =
            400L

        /*
         * Weak local results are worse than waiting online.
         * Build 33's Rock test selected Mood with a score around
         * 0.24 simply because only three local matches existed.
         */
        private const val MIN_POOL_RECOMMENDATION_SCORE =
            0.35

        private const val MIN_POOL_LOCAL_MATCH_SCORE =
            0.90

        private const val ENABLE_SMART_SELECTION_TEST =
            true

        private val CUSTOM_NUMBERED_EDIT_SUFFIX_REGEX =
            Regex(
                pattern =
                    """(?i)\s*[\(\[]\s*edit\s*\d*\s*[\)\]]\s*$"""
            )
    }

    @Volatile
    private var options =
        GoneSmartOptions()

    private var remoteSettingsPreferences:
            SharedPreferences? =
        null

    private val remoteSettingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->

            val previous =
                options

            options =
                GoneSmartOptions.fromPreferences(
                    preferences
                )

            if (
                BuildConfig.DEBUG && (
                    key == GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS ||
                    key == GoneSmartSettingsKeys.KEY_GROUP_EXTERNAL_PLAYLISTS ||
                    key == GoneSmartSettingsKeys.KEY_GROUP_ROOT_PLAYLISTS
                )
            ) {
                val next = options
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    playlistFolderPreview.setOptions(
                        next.playlistFoldersEnabled,
                        next.groupExternalPlaylists,
                        next.groupRootPlaylists
                    )
                }
            }
            if (key == GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST) {
                playlistController.setEnabled(options.multiPlaylistEnabled)
                if (previous.multiPlaylistEnabled != options.multiPlaylistEnabled) {
                    runtimeReporter.reportEvent(
                        GoneSmartRuntimeContract.CATEGORY_UI,
                        "Multi-playlist selection " +
                            if (options.multiPlaylistEnabled) "enabled." else "disabled."
                    )
                }
            }
            if (key == GoneSmartSettingsKeys.KEY_TRACK_MIX) {
                trackMixController.setEnabled(options.trackMixEnabled)
                if (previous.trackMixEnabled != options.trackMixEnabled) {
                    runtimeReporter.reportEvent(
                        GoneSmartRuntimeContract.CATEGORY_UI,
                        if (options.trackMixEnabled)
                            "Track Mix enabled." else "Track Mix disabled."
                    )
                }
            }
            if (key == GoneSmartSettingsKeys.KEY_FLIP_QUEUE) {
                queueFlipController.setEnabled(options.flipQueueEnabled)
                if (previous.flipQueueEnabled != options.flipQueueEnabled) {
                    runtimeReporter.reportEvent(
                        GoneSmartRuntimeContract.CATEGORY_UI,
                        "Flip queue / reverse playlist playback " +
                            if (options.flipQueueEnabled) "enabled." else "disabled."
                    )
                }
            }

            if (
                key != GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES &&
                key != GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST &&
                key != GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS &&
                key != GoneSmartSettingsKeys.KEY_GROUP_EXTERNAL_PLAYLISTS &&
                key != GoneSmartSettingsKeys.KEY_GROUP_ROOT_PLAYLISTS &&
                key != GoneSmartSettingsKeys.KEY_FLIP_QUEUE &&
                key != GoneSmartSettingsKeys.KEY_TRACK_MIX &&
                // Persisting Track Mix's already-active Smart DJ switch
                // must not discard its first live recommendation pool.
                !(key == GoneSmartSettingsKeys.KEY_ENABLED &&
                    previous.enabled == options.enabled)
            ) {

                pipelineGeneration
                    .incrementAndGet()

                recommendationPool
                    .reset(
                        newSessionId = -1L,
                        newTargetSize = 1
                    )
            }

            if (
                previous.enabled &&
                !options.enabled
            ) {

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.NONE
                    )

                runtimeReporter.report(
                    mode = GoneSmartRuntimeContract.MODE_NONE,
                    message = "GoneSmart disabled. GMMP Auto-DJ is selecting tracks normally.",
                    appendEvent = true
                )

            } else if (
                options.enabled
            ) {

                updatePlayerBadgeReadiness(
                    appendRuntimeEvent =
                        !previous.enabled
                )
            }

            Log.i(
                TAG,
                "GoneSmart settings updated | key=$key | options=$options"
            )
        }

    private val queueReader =
        GmmpQueueReader()

    private val queueSessionTracker =
        QueueSessionTracker()

    private val seedSelector =
        SeedSelector(
            historyLimit = 3,
            upcomingLimit = 2,
            maxTotalSeeds = 5
        )

    private val listenBrainzClient =
        ListenBrainzClient()

    private val lastFmClient =
        LastFmClient(
            apiKey =
                BuildConfig.LASTFM_API_KEY
        )

    private val artistCatalog =
        GmmpArtistCatalog()

    private val trackInputResolver =
        TrackInputResolver()

    private val metadataNormalizer =
        TrackMetadataNormalizer(
            knownAmpersandArtistsProvider = {

                artistCatalog
                    .getKnownAmpersandArtists()
            }
        )

    private val matchSelector =
        ListenBrainzMatchSelector(
            metadataNormalizer =
                metadataNormalizer
        )

    private val recommendationAggregator =
        RecommendationAggregator(
            metadataNormalizer =
                metadataNormalizer
        )

    private val gmmpLibraryReader =
        GmmpLibraryReader()

    private val localLibraryMatcher =
        LocalLibraryMatcher(
            metadataNormalizer =
                metadataNormalizer,

            trackInputResolver =
                trackInputResolver
        )

    private val localArtistFallbackMatcher =
        LocalArtistFallbackMatcher()

    private val trackFamilyKeyBuilder =
        TrackFamilyKeyBuilder(
            metadataNormalizer =
                metadataNormalizer,

            trackInputResolver =
                trackInputResolver
        )

    private val localPreferenceRanker =
        LocalPreferenceRanker(
            trackFamilyKeyBuilder =
                trackFamilyKeyBuilder
        )

    private val networkStateReader =
        NetworkStateReader()

    private val gmmpAutoDjSettingsReader =
        GmmpAutoDjSettingsReader()

    private val statusNotifier =
        GoneSmartStatusNotifier()

    private val runtimeReporter =
        GoneSmartRuntimeReporter()

    private val playerBadgeController =
        PlayerAutoDjBadgeController()

    private val playlistController =
        PlaylistMultiSelectController()

    private val playlistFolderPreview =
        PlaylistFolderPreviewController(playlistController)

    private val queueFlipController =
        QueueFlipController()

    // Track Mix is always available from single-song context menus,
    // even while Smart DJ is disabled. Clicking it explicitly enables
    // Smart DJ and uses GMMP's documented native Auto-DJ command.
    private val trackMixController = TrackMixController(
        enableSmartDj = { context -> enableSmartDjForTrackMix(context) },
        requestNativeRefill = { count -> requestNativeTrackMixRefill(count) }
    )

    @Volatile
    private var trackMixAutoDj: WeakReference<Any>? = null

    private val recommendationPool =
        SessionRecommendationPool()

    private val autoDjSelectionWindow =
        ThreadLocal<SelectionWindowContext?>()

    /*
     * One provider pipeline at a time is intentional. Network
     * requests and full recommendation aggregation are the
     * expensive part; serializing them avoids duplicate work and
     * keeps resource usage predictable.
     */
    private val pipelineExecutor =
        Executors.newSingleThreadExecutor()

    private val startupExecutor =
        Executors.newSingleThreadExecutor()

    private val poolFillLock =
        Any()

    @Volatile
    private var activePoolFillFuture:
            Future<Boolean>? =
        null

    @Volatile
    private var activePoolFillSessionId =
        -1L

    private val pipelineGeneration =
        AtomicLong(
            0L
        )

    private val startupPrewarmStarted =
        AtomicBoolean(
            false
        )

    private val startupPrewarmRunning =
        AtomicBoolean(
            false
        )

    private val shownSessionNotices =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    override fun onModuleLoaded(
        param: ModuleLoadedParam
    ) {

        Log.i(
            TAG,
            "GoneSmart module loaded"
        )

        log(
            Log.INFO,
            TAG,
            "GoneSmart module loaded"
        )
    }

    override fun onPackageReady(
        param: PackageReadyParam
    ) {

        if (
            !param.isFirstPackage
        ) {

            return
        }

        if (
            param.packageName !=
            GMMP_PACKAGE
        ) {

            return
        }

        initializeRemoteSettings()
        trackMixController.setEnabled(options.trackMixEnabled)

        Log.i(
            TAG,
            "GoneMAD Music Player detected - v${BuildConfig.VERSION_NAME}"
        )

        runtimeReporter.report(
            mode = GoneSmartRuntimeContract.MODE_NONE,
            message = "GoneSmart v${BuildConfig.VERSION_NAME} loaded in GoneMAD Music Player.",
            appendEvent = true
        )

        Log.i(
            TAG,
            "Last.fm configured = " +
                lastFmClient.isConfigured()
        )

        Log.i(
            TAG,
            "GoneSmart options = $options"
        )

        log(
            Log.INFO,
            TAG,
            "GoneMAD Music Player detected - v${BuildConfig.VERSION_NAME}"
        )

        try {

            installAutoDjRefillHook(
                param
            )

            installAutoDjSelectionHook(
                param
            )

            try {

                installPlayerBadgeHook(
                    param
                )

            } catch (playerBadgeHookError: Throwable) {

                Log.w(
                    TAG,
                    "Player badge hook could not be installed",
                    playerBadgeHookError
                )
            }

            try {

                installStartupPrewarmHook(
                    param
                )

            } catch (prewarmHookError: Throwable) {

                Log.w(
                    TAG,
                    "Startup cache prewarm hook could not be installed",
                    prewarmHookError
                )
            }

            // Native playlist UI is opt-in through the companion app and
            // remains independent of the Smart Auto-DJ recommendation mode.
            // Hook registration is available in release builds too.
            if (true) {
                try {
                    playlistController.setEnabled(options.multiPlaylistEnabled)
                    installPlaylistMultiSelectHooks(param)
                    if (options.multiPlaylistEnabled) {
                        runtimeReporter.reportEvent(
                            GoneSmartRuntimeContract.CATEGORY_SYSTEM,
                            "Multi-playlist selection is available."
                        )
                    }
                } catch (playlistHookError: Throwable) {
                    Log.w(
                        TAG,
                        "Experimental playlist hooks unavailable; native picker unaffected",
                        playlistHookError
                    )
                }
            }

            // Playlist folders are debug-only while the inline integration
            // is validated. GMMP's native adapter remains attached and owns
            // all playlist actions; GoneSmart only changes presentation.
            try {
                if (BuildConfig.DEBUG) {
                    playlistFolderPreview.setOptions(
                        options.playlistFoldersEnabled,
                        options.groupExternalPlaylists,
                        options.groupRootPlaylists
                    )
                    installPlaylistSurfaceDiscoveryHooks(param)
                }
            } catch (folderDiscoveryError: Throwable) {
                Log.w(
                    "GoneSmartPlaylist",
                    "FOLDER SURFACE | RecyclerView observer unavailable",
                    folderDiscoveryError
                )
            }

            // Native queue and reverse-playlist playback hooks are
            // independently controlled by the companion UI setting.
            // Install even while disabled so it can be enabled live.
            try {
                queueFlipController.setEnabled(options.flipQueueEnabled)
                installQueueFlipHooks(param)
            } catch (flipHookError: Throwable) {
                Log.w(
                    TAG,
                    "Queue Flip hooks unavailable; native menus unaffected",
                    flipHookError
                )
                runtimeReporter.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_FLIP,
                    "Flip unavailable: GMMP hooks could not be installed."
                )
            }

            Log.i(
                TAG,
                "All GoneSmart core hooks installed successfully"
            )

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "Failed to install GoneSmart hooks",
                t
            )
        }
    }

    /**
     * GMMP 4.2.0 menu resources verified from its own APK:
     * - menu_gm_queue: Queue overflow; Remove duplicates is the anchor.
     * - menu_gm_context_playlist_list: playlist three-dot popup.
     * - menu_gm_context_smart: Smart Playlist three-dot popup.
     *
     * Fully implemented queue and reverse-playlist playback; companion
     * settings control these hooks independently of Smart DJ.
     */
    private fun enableSmartDjForTrackMix(
        context: android.content.Context
    ): Boolean {
        return runCatching {
            if (!options.enabled) {
                // Immediate activation in this GMMP process: don't wait
                // for a cross-process preferences notification to let
                // the very first auto-DJ refill select GoneSmart songs.
                options = options.copy(enabled = true)
                runtimeReporter.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_UI,
                    "Smart DJ enabled by song-based Auto-DJ."
                )
                val intent = android.content.Intent(
                    GoneSmartRuntimeContract.ACTION_ENABLE_SMART_DJ_FOR_MIX
                ).setClassName(
                    "io.github.alagga.gonesmart",
                    "io.github.alagga.gonesmart.GoneSmartEventReceiver"
                )
                context.applicationContext.sendBroadcast(intent)
            }
            trackMixAutoDj?.get()?.let { startStartupPrewarm(it) }
            true
        }.onFailure {
            Log.e(TAG, "Track Mix could not enable Smart DJ", it)
        }.getOrDefault(false)
    }

    private fun requestNativeTrackMixRefill(count: Int): Boolean {
        if (count <= 0) return true
        val manager = trackMixAutoDj?.get() ?: return false
        return runCatching {
            manager.javaClass.getDeclaredMethod(
                "z", Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
                .invoke(manager, count)
            Log.i(
                "GoneSmartTrackMix",
                "MIX REQUEST REFILL | requested=$count"
            )
            true
        }.onFailure {
            Log.e(TAG, "Track Mix native refill unavailable", it)
        }.getOrDefault(false)
    }

    private fun installQueueFlipHooks(param: PackageReadyParam) {
        val menuInflaterClasses = listOf(
            android.view.MenuInflater::class.java,
            param.classLoader.loadClass(
                "androidx.appcompat.view.SupportMenuInflater"
            )
        )
        var installed = 0
        for (inflaterClass in menuInflaterClasses) {
            runCatching {
                val method = inflaterClass.getDeclaredMethod(
                    "inflate",
                    Int::class.javaPrimitiveType,
                    android.view.Menu::class.java
                ).apply { isAccessible = true }
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        queueFlipController.onMenuInflated(
                            chain.getArg(0) as? Int ?: 0,
                            chain.getArg(1) as? android.view.Menu,
                            chain.getThisObject()
                        )
                    } catch (error: Throwable) {
                        Log.e(TAG, "Flip menu observation failed", error)
                    }
                    try {
                        trackMixController.onMenuInflated(
                            chain.getArg(0) as? Int ?: 0,
                            chain.getArg(1) as? android.view.Menu,
                            chain.getThisObject()
                        )
                    } catch (error: Throwable) {
                        Log.e(TAG, "Track Mix menu insertion failed", error)
                    }
                    if (BuildConfig.DEBUG) {
                        runCatching {
                            playlistFolderPreview.onMenuInflated(
                                chain.getArg(0) as? Int ?: 0,
                                chain.getArg(1) as? android.view.Menu
                            )
                        }.onFailure {
                            Log.w(
                                "GoneSmartPlaylist",
                                "FOLDER NATIVE MENU | observer failed",
                                it
                            )
                        }
                    }
                    result
                }
                installed++
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Flip menu inflater hook unavailable: " +
                        inflaterClass.name,
                    error
                )
            }
        }

        runCatching {
            val queueClass = param.classLoader.loadClass("ex3")

            val constructor = queueClass.declaredConstructors.firstOrNull {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == android.content.Context::class.java
            }
            if (constructor != null) {
                hook(constructor.apply { isAccessible = true })
                    .intercept { chain ->
                        val result = chain.proceed()
                        queueFlipController.captureNativeQueue(
                            chain.getThisObject()
                        )
                        trackMixController.captureNativeQueue(
                            chain.getThisObject()
                        )
                        result
                    }
            }

            // Fallback if GMMP constructed its queue before hooks were
            // registered. This method is observed during normal queue UI.
            val queuePosition = queueClass.getDeclaredMethod("D")
                .apply { isAccessible = true }
            hook(queuePosition).intercept { chain ->
                queueFlipController.captureNativeQueue(
                    chain.getThisObject()
                )
                trackMixController.captureNativeQueue(
                    chain.getThisObject()
                )
                chain.proceed()
            }
            Log.i(
                "GoneSmartFlip",
                "FLIP QUEUE READY | native ex3 capture installed"
            )
        }.onFailure {
            Log.w(TAG, "Flip queue capture hooks unavailable", it)
        }

        // Native playback from ordinary track lists may replace the
        // queue through ex3.w(List), while Play on an existing queue
        // row may only seek to a new absolute position via ex3.b2().
        // These are passive, short-circuit-free notifications for the
        // ONE pending Track Mix request, never global queue mutations.
        runCatching {
            val queueClass = param.classLoader.loadClass("ex3")
            val queueWrite = queueClass.declaredMethods.firstOrNull {
                it.name == "w" &&
                    it.parameterCount == 1 &&
                    java.util.List::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    )
            }
            if (queueWrite != null) {
                hook(queueWrite.apply { isAccessible = true })
                    .intercept { chain ->
                        val result = chain.proceed()
                        trackMixController.captureNativeQueue(
                            chain.getThisObject()
                        )
                        trackMixController.onNativePlaybackQueueUpdated(
                            "ex3.w"
                        )
                        result
                    }
            } else {
                Log.w(TAG, "Track Mix ex3.w observer unavailable")
            }
            val positionSet = queueClass.getDeclaredMethod(
                "b2", Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            hook(positionSet).intercept { chain ->
                val result = chain.proceed()
                trackMixController.captureNativeQueue(
                    chain.getThisObject()
                )
                trackMixController.onNativePlaybackQueueUpdated(
                    "ex3.b2"
                )
                result
            }
            Log.i(
                "GoneSmartTrackMix",
                "MIX QUEUE OBSERVERS READY | queue-write and position"
            )
        }.onFailure {
            Log.w(TAG, "Track Mix native queue observers unavailable", it)
        }

        // Playlist and Smart Playlist both use MusicService.w1(action=0)
        // to replace the queue with a fully resolved list of native rm3
        // tracks. Invoke GMMP's normal Play listener on the selected row,
        // then reverse THAT list synchronously at its native entry point,
        // before any queue reset or first-track playback can occur.
        runCatching {
            val serviceClass = param.classLoader.loadClass(
                "gonemad.gmmp.playback.service.MusicService"
            )
            val playListMethod = serviceClass.getDeclaredMethod(
                "w1",
                Int::class.javaPrimitiveType,
                Any::class.java,
                List::class.java
            ).apply { isAccessible = true }
            hook(playListMethod).intercept { chain ->
                val originalList = chain.getArg(2) as? List<*>
                val reversed = queueFlipController
                    .consumeReversePlaylistForNativePlay(
                        chain.getArg(0) as? Int,
                        originalList
                    )
                if (reversed == null) {
                    val result = chain.proceed()
                    trackMixController.onNativePlaybackMethodFinished(
                        chain.getArg(0) as? Int
                    )
                    result
                } else {
                    // The hook framework does not expose an argument
                    // setter. Re-enter the original native method with a
                    // new List while our pending request is already
                    // consumed; the nested hook proceeds normally.
                    Log.i(
                        "GoneSmartFlip",
                        "FLIP SERVICE | native action=0 | " +
                            "originalCount=${originalList?.size} | " +
                            "reversedCount=${reversed.tracks.size}"
                    )
                    val result = playListMethod.invoke(
                        chain.getThisObject(),
                        chain.getArg(0),
                        chain.getArg(1),
                        reversed.tracks
                    )
                    queueFlipController.verifyNativePlaylistPlayback(
                        reversed.tracks,
                        reversed.sourceKind
                    )
                    result
                }
            }
            queueFlipController.setNativePlaylistInterceptorReady(true)
            Log.i(
                "GoneSmartFlip",
                "FLIP PLAY HOOK READY | MusicService.w1 native reversed list"
            )
        }.onFailure { error ->
            queueFlipController.setNativePlaylistInterceptorReady(false)
            Log.e(TAG, "Flip native Playlist Play hook unavailable", error)
        }

        Log.i(
            "GoneSmartFlip",
            "FLIP READY | menuInflaters=$installed | " +
                "enabled=${options.flipQueueEnabled} | " +
                "phase=native-flip"
        )
        if (options.flipQueueEnabled) {
            runtimeReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_SYSTEM,
                "Flip Queue and reverse playlist playback are available."
            )
        }
        Log.i(
            "GoneSmartTrackMix",
            "MIX READY | menuInflaters=$installed | " +
                "scope=individual-track-menus | initialSize=GMMP"
        )
        if (installed > 0) {
            runtimeReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_SYSTEM,
                "Song-based Auto-DJ is available in song menus."
            )
        }
    }

    /**
     * Debug-only experimental multi-destination playlist picker for GMMP 4.2.0.
     * Hooks only native picker methods and the three verified UI callbacks.
     * The native io3 handler performs all actual playlist writes.
     */

    /**
     * Observe RecyclerView adapter installation and attachment without
     * assuming the normal Playlists tab shares the picker's zn3 adapter.
     * This is instrumentation only; the original native methods still run.
     */
    private fun installPlaylistSurfaceDiscoveryHooks(
        param: PackageReadyParam
    ) {
        val recycler = param.classLoader.loadClass(
            "androidx.recyclerview.widget.RecyclerView"
        )
        val setAdapter = recycler.declaredMethods.firstOrNull {
            it.name == "setAdapter" && it.parameterCount == 1
        } ?: throw NoSuchMethodException("RecyclerView.setAdapter")
        setAdapter.isAccessible = true
        hook(setAdapter).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val list = chain.getThisObject() as? android.view.View
                playlistController.onNativeRecyclerAdapterSet(
                    list,
                    chain.getArg(0)
                )
                playlistFolderPreview.onNativeRecyclerObserved(list)
            }.onFailure {
                Log.w("GoneSmartPlaylist", "FOLDER SURFACE | adapter probe failed", it)
            }
            result
        }

        val attach = recycler.declaredMethods.firstOrNull {
            it.name == "onAttachedToWindow" && it.parameterCount == 0
        } ?: throw NoSuchMethodException("RecyclerView.onAttachedToWindow")
        attach.isAccessible = true
        hook(attach).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val list = chain.getThisObject() as? android.view.View
                playlistController.onNativeRecyclerAttached(list)
                playlistFolderPreview.onNativeRecyclerObserved(list)
            }.onFailure {
                Log.w("GoneSmartPlaylist", "FOLDER SURFACE | attach probe failed", it)
            }
            result
        }
        // Native GMMP obfuscates RecyclerView.Adapter itself. Its public
        // RecyclerView methods above are sufficient: the controller probes
        // already-bound visible holders after layout instead of hooking a
        // class name that does not exist in GMMP's APK.

        Log.i(
            "GoneSmartPlaylist",
            "FOLDER SURFACE READY | native RecyclerView setAdapter/attach hooks"
        )
    }

    private fun installPlaylistMultiSelectHooks(
        param: PackageReadyParam
    ) {
        val pickerClass = param.classLoader.loadClass("bo3")

        listOf("I3", "k2", "D1").forEach { name ->
            val method = pickerClass.declaredMethods
                .firstOrNull {
                    it.name == name && it.parameterCount == 0
                } ?: throw NoSuchMethodException("bo3.$name()")

            method.isAccessible = true
            hook(method).intercept { chain ->
                if (name == "I3") {
                    playlistController.beginPicker(
                        chain.getThisObject()
                    )
                }

                val result = chain.proceed()
                try {
                    when (name) {
                        "k2" -> (result as? android.view.View)?.let {
                            playlistController.onFabFound(it)
                        }
                        "D1" -> (result as? android.view.View)?.let {
                            playlistController.onListFound(it)
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Playlist $name observer failed", error)
                }
                result
            }
        }

        // go3.y2() constructs this native handler using the original
        // source selection (ho3). Capture it during picker startup.
        val handlerClass = param.classLoader.loadClass("io3")
        val nativeConstructor = handlerClass.declaredConstructors
            .firstOrNull {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == "ho3" &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("io3(ho3, boolean)")

        nativeConstructor.isAccessible = true
        hook(nativeConstructor).intercept { chain ->
            val result = chain.proceed()
            playlistController.onNativeHandler(
                chain.getThisObject()
            )
            result
        }

        // GMMP io3.r() builds one jd(mode=4) completion callback per
        // playlist. Each successful callback posts j83 to the activity,
        // whose onEvent(j83) navigates back once. Scope ONLY callbacks
        // created by our multi-add and forward that event once per batch.
        // Regular one-playlist adds and unrelated back actions are unchanged.
        runCatching {
            val callbackClass = param.classLoader.loadClass("jd")
            val nativeCallbackConstructor =
                callbackClass.declaredConstructors.first {
                    it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                }.apply { isAccessible = true }

            hook(nativeCallbackConstructor).intercept { chain ->
                val result = chain.proceed()
                playlistController.onNativeResultCallbackConstructed(
                    chain.getThisObject(),
                    chain.getArg(0)
                )
                result
            }

            val invoke = callbackClass.getDeclaredMethod(
                "invoke",
                Any::class.java
            ).apply { isAccessible = true }

            hook(invoke).intercept { chain ->
                playlistController.aroundNativeResultCallback(
                    chain.getThisObject()
                ) {
                    chain.proceed()
                }
            }

            val eventBusClass = param.classLoader.loadClass("f2")
            val emitEvent = eventBusClass.getDeclaredMethod(
                "b",
                Any::class.java
            ).apply { isAccessible = true }

            hook(emitEvent).intercept { chain ->
                if (
                    playlistController.shouldSuppressNativeCloseEvent(
                        chain.getArg(0)
                    )
                ) {
                    null
                } else {
                    chain.proceed()
                }
            }

            Log.i(
                "GoneSmartPlaylist",
                "MULTI NAV READY | one native j83 close event per batch"
            )
        }.onFailure { error ->
            Log.e(
                TAG,
                "Playlist native navigation guard unavailable",
                error
            )
        }

        // GMMP displays a native Toast for each playlist completion.
        // Hide only those Toast.show() calls made INSIDE the jd(mode=4)
        // callbacks tagged during GoneSmart multi-add. The controller
        // posts one aggregated Toast using GMMP's own app context once
        // all successful native completions have been observed.
        // The normal GMMP one-playlist operation is never affected.
        runCatching {
            val nativeToastShow = android.widget.Toast::class.java
                .getDeclaredMethod("show")
                .apply { isAccessible = true }
            hook(nativeToastShow).intercept { chain ->
                if (playlistController.shouldSuppressNativeResultToast()) {
                    null
                } else if (trackMixController.shouldSuppressNativeToast(
                        chain.getThisObject() as? android.widget.Toast
                    )
                ) {
                    null
                } else {
                    chain.proceed()
                }
            }
            Log.i(
                "GoneSmartPlaylist",
                "MULTI TOAST READY | native results scoped to one summary"
            )
        }.onFailure { error ->
            Log.e(
                TAG,
                "Playlist native result Toast hook unavailable",
                error
            )
        }

        // GMMP can display the Auto-DJ rules-changed notification as a
        // Material Snackbar, not only as an Android Toast. Hide only its
        // short Track Mix transition window. Ordinary settings changes
        // and all notifications outside that window are untouched.
        runCatching {
            val snackClass = param.classLoader.loadClass(
                "com.google.android.material.snackbar.BaseTransientBottomBar"
            )
            val show = snackClass.getDeclaredMethod("show")
                .apply { isAccessible = true }
            hook(show).intercept { chain ->
                if (trackMixController.shouldSuppressNativeSnackbar()) {
                    Log.i(
                        "GoneSmartTrackMix",
                        "MIX POPUP | intermediate GMMP snackbar hidden"
                    )
                    null
                } else {
                    chain.proceed()
                }
            }
            Log.i("GoneSmartTrackMix", "MIX POPUP | snackbar guard ready")
        }.onFailure {
            Log.i(
                "GoneSmartTrackMix",
                "MIX POPUP | GMMP has no compatible snackbar hook"
            )
        }

        val clickClass = param.classLoader.loadClass("xj5\$a")
        val clickMethod = clickClass.getDeclaredMethod(
            "onClick",
            android.view.View::class.java
        )
        clickMethod.isAccessible = true
        hook(clickMethod).intercept { chain ->
            val view = chain.getArg(0) as? android.view.View
            val intercepted = runCatching {
                playlistController.onClick(view) ||
                    playlistFolderPreview.interceptNativePickerFabClick(view)
            }.getOrElse { error ->
                Log.e(TAG, "Playlist click interception failed", error)
                false
            }

            if (intercepted) null else chain.proceed()
        }

        val longClickClass = param.classLoader.loadClass("rk5\$a")
        val longClickMethod = longClickClass.getDeclaredMethod(
            "onLongClick",
            android.view.View::class.java
        )
        longClickMethod.isAccessible = true
        hook(longClickMethod).intercept { chain ->
            val view = chain.getArg(0) as? android.view.View
            val intercepted = runCatching {
                playlistController.onLongClick(view)
            }.getOrElse { error ->
                Log.e(TAG, "Playlist long-click interception failed", error)
                false
            }

            if (intercepted) true else chain.proceed()
        }

        // GMMP reuses PlaylistAdd row views while scrolling. Refresh the
        // tint AFTER a native bind so no selected background can leak onto
        // an unrelated playlist occupying the same RecyclerView holder.
        runCatching {
            val adapterClass = param.classLoader.loadClass("zn3")
            val bindMethods = adapterClass.declaredMethods.filter { method ->
                method.name == "N0" && method.parameterCount >= 1
            }
            bindMethods.forEach { method ->
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val args = (0 until method.parameterCount)
                        .map { index -> chain.getArg(index) }
                    runCatching {
                        if (BuildConfig.DEBUG && !options.playlistFoldersEnabled) {
                            playlistController.onNativeRowBindObserved(
                                method.toGenericString(),
                                args,
                                result
                            )
                        }
                    }.onFailure {
                        Log.w(
                            "GoneSmartPlaylist",
                            "FOLDER N0 CALL | probe failed",
                            it
                        )
                    }
                    val holder = args.firstOrNull {
                        it?.javaClass?.name == "jo3"
                    }
                    if (holder != null) {
                        playlistController.onRowBound(holder)
                    }
                    result
                }
            }
            Log.i(
                "GoneSmartPlaylist",
                "MULTI ROW BIND | native zn3.N0 hooks=${bindMethods.size}"
            )
        }.onFailure { error ->
            Log.w(
                TAG,
                "Native row-bind hook unavailable; scroll observer remains active",
                error
            )
        }

        // The native FAB normally disappears while scrolling. Suppress
        // its hide animation only while GoneSmart multi-select is active.
        runCatching {
            val fabClass = param.classLoader.loadClass(
                "com.google.android.material.floatingactionbutton.FloatingActionButton"
            )
            fabClass.declaredMethods
                .filter {
                    it.name == "hide" &&
                        it.returnType == Void.TYPE
                }
                .forEach { method ->
                    method.isAccessible = true
                    hook(method).intercept { chain ->
                        if (
                            playlistController.shouldBlockFabHide(
                                chain.getThisObject()
                            )
                        ) {
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                }
        }.onFailure { error ->
            Log.w(TAG, "FAB hide hook unavailable; layout pin remains", error)
        }

        runCatching {
            val backClass = param.classLoader.loadClass(
                "androidx.activity.OnBackPressedDispatcher"
            )
            val backMethod = backClass.getDeclaredMethod("onBackPressed")
            backMethod.isAccessible = true
            hook(backMethod).intercept { chain ->
                if (
                    playlistController.consumeBack() ||
                    playlistFolderPreview.consumeBack()
                ) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }.onFailure { error ->
            // GMMP's optimized APK may not ship the public AndroidX
            // dispatcher class. Fall back to the platform callback,
            // scoped to this injected GMMP process, so nested folder
            // navigation and multi-selection can still consume Back.
            Log.i(
                TAG,
                "Playlist AndroidX back dispatcher unavailable; " +
                    "trying Activity.onBackPressed"
            )
            runCatching {
                val method = android.app.Activity::class.java
                    .getDeclaredMethod("onBackPressed").apply {
                        isAccessible = true
                    }
                hook(method).intercept { chain ->
                    if (
                        playlistController.consumeBack() ||
                        playlistFolderPreview.consumeBack()
                    ) null else chain.proceed()
                }
                Log.i(TAG, "Playlist platform back hook ready")
            }.onFailure {
                Log.w(TAG, "Playlist platform back hook unavailable", it)
            }
        }

        Log.i(
            "GoneSmartPlaylist",
            "MULTI READY | experimental native playlist picker (debug build)"
        )
    }

    private fun initializeRemoteSettings() {

        try {

            val preferences =
                getRemotePreferences(
                    GoneSmartSettingsKeys.GROUP
                )

            remoteSettingsPreferences
                ?.unregisterOnSharedPreferenceChangeListener(
                    remoteSettingsListener
                )

            remoteSettingsPreferences =
                preferences

            options =
                GoneSmartOptions.fromPreferences(
                    preferences
                )

            preferences.registerOnSharedPreferenceChangeListener(
                remoteSettingsListener
            )

            Log.i(
                TAG,
                "Remote GoneSmart settings connected"
            )

        } catch (throwable: Throwable) {

            options =
                GoneSmartOptions()

            Log.w(
                TAG,
                "Remote GoneSmart settings unavailable; using defaults",
                throwable
            )
        }
    }

    private fun installPlayerBadgeHook(
        param: PackageReadyParam
    ) {

        val activityClass =
            param.classLoader.loadClass(
                "gonemad.gmmp.ui.main.MainActivity"
            )

        val resumeMethod =
            findNoArgMethod(
                type = activityClass,
                name = "onResume"
            )

        resumeMethod.isAccessible =
            true

        hook(
            resumeMethod
        ).intercept { chain ->

            val result =
                chain.proceed()

            val activity =
                chain.getThisObject()
                    as? Activity

            if (
                activity != null
            ) {

                playerBadgeController
                    .attach(
                        activity
                    )

                updatePlayerBadgeReadiness(
                    appendRuntimeEvent = false
                )
            }

            result
        }

        Log.i(
            TAG,
            "Player Auto-DJ badge hook installed successfully"
        )
    }

    private fun installStartupPrewarmHook(
        param: PackageReadyParam
    ) {

        val autoDjClass =
            param.classLoader.loadClass(
                "qr"
            )

        val constructor =
            autoDjClass
                .declaredConstructors
                .firstOrNull {
                    it.parameterTypes.size ==
                        2
                }
                ?: throw NoSuchMethodException(
                    "qr constructor with 2 parameters"
                )

        constructor.isAccessible =
            true

        hook(
            constructor
        ).intercept { chain ->

            val result =
                chain.proceed()

            chain
                .getThisObject()
                ?.let { autoDjInstance ->
                    trackMixAutoDj = WeakReference(autoDjInstance)
                    trackMixController.captureNativeAutoDj(
                        autoDjInstance
                    )
                    startStartupPrewarm(
                        autoDjInstance
                    )
                }

            result
        }

        Log.i(
            TAG,
            "Startup cache prewarm hook installed successfully"
        )
    }

    private fun startStartupPrewarm(
        autoDjInstance: Any
    ) {

        if (
            !options.enabled
        ) {

            return
        }

        if (
            !startupPrewarmStarted.compareAndSet(
                false,
                true
            )
        ) {

            return
        }

        startupPrewarmRunning.set(
            true
        )

        val startedAt =
            SystemClock.elapsedRealtime()

        statusNotifier.showDelayed(
            message =
                "Preparing GoneSmart cache… This may take a moment.",
            delayMs =
                STARTUP_PREWARM_STATUS_DELAY_MS,
            shouldShow = {
                startupPrewarmRunning.get()
            }
        )

        startupExecutor.execute {

            var success =
                false

            try {

                for (
                    attempt in
                    1..STARTUP_PREWARM_MAX_ATTEMPTS
                ) {

                    if (
                        !isAutoDjLibraryReady(
                            autoDjInstance
                        )
                    ) {

                        Thread.sleep(
                            STARTUP_PREWARM_RETRY_DELAY_MS
                        )

                        continue
                    }

                    try {

                        artistCatalog.ensureLoaded(
                            autoDjInstance
                        )

                        val library =
                            gmmpLibraryReader
                                .read(
                                    autoDjInstance
                                )

                        if (
                            library.isEmpty()
                        ) {

                            Thread.sleep(
                                STARTUP_PREWARM_RETRY_DELAY_MS
                            )

                            continue
                        }

                        val preparationResult =
                            localLibraryMatcher
                                .prepareLibrary(
                                    library
                                )

                        Log.i(
                            TAG,
                            "STARTUP PREWARM READY | " +
                                "library=${library.size} | " +
                                "index=$preparationResult"
                        )

                        success =
                            true

                        break

                    } catch (t: Throwable) {

                        Log.w(
                            TAG,
                            "Startup prewarm attempt $attempt failed",
                            t
                        )

                        Thread.sleep(
                            STARTUP_PREWARM_RETRY_DELAY_MS
                        )
                    }
                }

            } finally {

                startupPrewarmRunning.set(
                    false
                )

                val elapsedMs =
                    SystemClock.elapsedRealtime() -
                        startedAt

                if (
                    success &&
                    elapsedMs >=
                    STARTUP_PREWARM_STATUS_DELAY_MS
                ) {

                    statusNotifier.show(
                        "GoneSmart cache ready."
                    )
                }

                if (
                    !success
                ) {

                    startupPrewarmStarted.set(
                        false
                    )

                    Log.w(
                        TAG,
                        "STARTUP PREWARM NOT READY | " +
                            "will retry on demand"
                    )
                }
            }
        }
    }

    private fun isAutoDjLibraryReady(
        autoDjInstance: Any
    ): Boolean {

        return try {

            val trackDaoField =
                findField(
                    type = autoDjInstance.javaClass,
                    name = "r"
                )

            trackDaoField.isAccessible =
                true

            trackDaoField.get(
                autoDjInstance
            ) != null

        } catch (
            _: Throwable
        ) {

            false
        }
    }

    private fun installAutoDjRefillHook(
        param: PackageReadyParam
    ) {

        val autoDjClass =
            param.classLoader.loadClass(
                "qr"
            )

        val refillMethod =
            autoDjClass.getDeclaredMethod(
                "z",
                Integer.TYPE
            )

        refillMethod.isAccessible =
            true

        hook(
            refillMethod
        ).intercept { chain ->

            val requestedTracks =
                (chain.getArg(
                    0
                ) as? Int)
                    ?.coerceAtLeast(
                        1
                    )
                    ?: 1

            val autoDjInstance =
                chain.getThisObject()

            if (autoDjInstance != null) {
                trackMixAutoDj = WeakReference(autoDjInstance)
                trackMixController.captureNativeAutoDj(autoDjInstance)
                if (trackMixController.shouldSuppressNativeRefill()) {
                    // GMMP may start a refill as soon as native Play
                    // seeks to the selected queue row. That older refill
                    // races CLEAR_QUEUE and caused intermittent failures.
                    // Track Mix will explicitly enable native Auto-DJ
                    // after it confirms that only the new seed remains.
                    return@intercept null
                }
                trackMixController.onNativeAutoDjRefillRequested(
                    requestedTracks
                )
            }

            Log.i(
                TAG,
                "========================================"
            )

            Log.i(
                TAG,
                "Auto-DJ requested $requestedTracks track(s)"
            )

            if (
                autoDjInstance == null
            ) {

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | Auto-DJ instance unavailable"
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                Log.i(
                    TAG,
                    "========================================"
                )

                return@intercept null
            }

            if (
                !options.enabled
            ) {

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.NONE
                    )

                runtimeReporter.report(
                    mode = GoneSmartRuntimeContract.MODE_NONE,
                    message = "GoneSmart disabled. GMMP Auto-DJ is selecting tracks normally.",
                    appendEvent = false
                )

                return@intercept chain.proceed()
            }

            if (
                !startupPrewarmStarted.get()
            ) {

                startStartupPrewarm(
                    autoDjInstance
                )
            }

            val beforeContext =
                queueReader.read(
                    autoDjInstance
                )

            if (
                beforeContext == null
            ) {

                val state =
                    networkStateReader
                        .getState()

                if (
                    state ==
                    GoneSmartNetworkState.OFFLINE
                ) {

                    Log.i(
                        TAG,
                        "SMART DJ OFFLINE FALLBACK | " +
                            "queue context unavailable - using native GMMP Auto-DJ"
                    )

                    playerBadgeController
                        .setMode(
                            PlayerAutoDjBadgeController.Mode.FALLBACK
                        )

                    runtimeReporter.report(
                        mode = GoneSmartRuntimeContract.MODE_FALLBACK,
                        message = "Offline: using GMMP Auto-DJ fallback.",
                        appendEvent = true
                    )

                    return@intercept chain.proceed()
                }

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | " +
                        "queue context unavailable; native online fallback disabled"
                )

                Log.i(
                    TAG,
                    "========================================"
                )

                return@intercept null
            }

            val session =
                queueSessionTracker
                    .observe(
                        beforeContext
                    )

            logQueueSession(
                session
            )

            val gmmpSettings =
                gmmpAutoDjSettingsReader
                    .read()

            val poolSizing =
                gmmpAutoDjSettingsReader
                    .calculatePoolSizing(
                        gmmpSettings
                    )

            if (
                session.isNewSession ||
                !recommendationPool.isForSession(
                    session.sessionId
                )
            ) {

                resetRecommendationPoolForSession(
                    session = session,
                    sizing = poolSizing
                )

            } else {

                recommendationPool
                    .configureTarget(
                        expectedSessionId = session.sessionId,
                        newTargetSize = poolSizing.targetSize
                    )
            }

            Log.i(
                TAG,
                "GMMP AUTO-DJ SETTINGS | " +
                    "initial=${gmmpSettings.initialQueueSize} | " +
                    "upcoming=${gmmpSettings.upcomingTrackCount} | " +
                    "source=${gmmpSettings.source} | " +
                    "poolTarget=${poolSizing.targetSize} | " +
                    "lowWater=${poolSizing.lowWaterMark}"
            )

            val queueTrackIds =
                beforeContext
                    .items
                    .map {
                        it.track.id
                    }
                    .toSet()

            val networkState =
                networkStateReader
                    .getState()

            Log.i(
                TAG,
                "NETWORK STATE = $networkState"
            )

            val poolAlreadyReady =
                recommendationPool
                    .hasEnough(
                        expectedSessionId = session.sessionId,
                        count = requestedTracks,
                        excludedTrackIds = queueTrackIds
                    )

            updatePlayerBadgeReadiness(
                networkStateOverride = networkState,
                poolAvailableOverride = poolAlreadyReady,
                appendRuntimeEvent = false
            )

            if (
                !poolAlreadyReady &&
                networkState ==
                GoneSmartNetworkState.OFFLINE
            ) {

                Log.i(
                    TAG,
                    "SMART DJ OFFLINE FALLBACK | " +
                        "current session has no usable cached tracks - " +
                        "using native GMMP Auto-DJ"
                )

                showSessionNoticeOnce(
                    sessionId = session.sessionId,
                    noticeKey = "offline-native-fallback",
                    message = "GoneSmart is offline. Using GMMP Auto-DJ fallback."
                )

                autoDjSelectionWindow.set(
                    null
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                runtimeReporter.report(
                    mode = GoneSmartRuntimeContract.MODE_FALLBACK,
                    message = "Offline: using GMMP Auto-DJ fallback for the current queue.",
                    appendEvent = true
                )

                val result =
                    chain.proceed()

                val afterContext =
                    queueReader.read(
                        autoDjInstance
                    )

                if (
                    afterContext != null
                ) {

                    queueSessionTracker
                        .commitGeneratedAdditions(
                            beforeContext = beforeContext,
                            afterContext = afterContext,
                            origin = QueueEntryOrigin.NATIVE_AUTO_DJ
                        )

                    logAddedTracks(
                        beforeContext = beforeContext,
                        afterContext = afterContext
                    )
                }

                Log.i(
                    TAG,
                    "========================================"
                )

                return@intercept result
            }

            val seeds =
                seedSelector
                    .select(
                        session = session
                    )

            logSeeds(
                seeds
            )

            if (
                !poolAlreadyReady &&
                seeds.isEmpty()
            ) {

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | " +
                        "no usable current-session seeds"
                )

                showSessionNoticeOnce(
                    sessionId = session.sessionId,
                    noticeKey = "no-seeds",
                    message = "GoneSmart could not build a recommendation context for this queue."
                )

                Log.i(
                    TAG,
                    "========================================"
                )

                return@intercept null
            }

            val poolReady =
                if (
                    poolAlreadyReady
                ) {

                    Log.i(
                        TAG,
                        "SMART DJ POOL HIT | " +
                            recommendationPool.describe(
                                session.sessionId
                            )
                    )

                    true

                } else {

                    ensureRecommendationPoolReady(
                        seeds = seeds,
                        autoDjInstance = autoDjInstance,
                        queueContext = beforeContext,
                        session = session,
                        sizing = poolSizing,
                        requestedTracks = requestedTracks
                    )
                }

            if (
                !poolReady
            ) {

                val stateAfterFailure =
                    networkStateReader
                        .getState()

                val shouldUseNativeFallback =
                    stateAfterFailure ==
                        GoneSmartNetworkState.OFFLINE ||
                        options.fallbackToNativeAutoDjWhenNoSuitableTracks

                if (
                    shouldUseNativeFallback
                ) {

                    val offline =
                        stateAfterFailure ==
                            GoneSmartNetworkState.OFFLINE

                    val reason =
                        if (offline) {
                            "connection unavailable while preparing recommendations"
                        } else {
                            "no sufficiently good GoneSmart library matches"
                        }

                    Log.i(
                        TAG,
                        "SMART DJ NATIVE FALLBACK | $reason"
                    )

                    showSessionNoticeOnce(
                        sessionId = session.sessionId,
                        noticeKey = if (offline) {
                            "offline-native-fallback"
                        } else {
                            "no-matches-native-fallback"
                        },
                        message = if (offline) {
                            "GoneSmart is offline. Using GMMP Auto-DJ fallback."
                        } else {
                            "GoneSmart found no suitable library matches. Using GMMP Auto-DJ fallback."
                        }
                    )

                    autoDjSelectionWindow.set(
                        null
                    )

                    playerBadgeController
                        .setMode(
                            PlayerAutoDjBadgeController.Mode.FALLBACK
                        )

                    runtimeReporter.report(
                        mode = GoneSmartRuntimeContract.MODE_FALLBACK,
                        message = if (offline) {
                            "Offline: using GMMP Auto-DJ fallback."
                        } else {
                            "No suitable GoneSmart match: using GMMP Auto-DJ fallback."
                        },
                        appendEvent = true
                    )

                    val result =
                        chain.proceed()

                    val afterContext =
                        queueReader.read(
                            autoDjInstance
                        )

                    if (
                        afterContext != null
                    ) {

                        queueSessionTracker
                            .commitGeneratedAdditions(
                                beforeContext = beforeContext,
                                afterContext = afterContext,
                                origin = QueueEntryOrigin.NATIVE_AUTO_DJ
                            )

                        logAddedTracks(
                            beforeContext = beforeContext,
                            afterContext = afterContext
                        )
                    }

                    Log.i(
                        TAG,
                        "========================================"
                    )

                    return@intercept result
                }

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | " +
                        "no sufficiently good GoneSmart selection available; " +
                        "native online fallback disabled by GoneSmart settings"
                )

                showSessionNoticeOnce(
                    sessionId = session.sessionId,
                    noticeKey = "no-matches-stopped",
                    message = "GoneSmart found no suitable tracks in your library. Auto-DJ stopped."
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                runtimeReporter.report(
                    mode = GoneSmartRuntimeContract.MODE_STOPPED,
                    message = "GoneSmart found no suitable local tracks and fallback is disabled.",
                    appendEvent = true
                )

                Log.i(
                    TAG,
                    "========================================"
                )

                return@intercept null
            }

            val result =
                try {

                    autoDjSelectionWindow.set(
                        SelectionWindowContext(
                            sessionId = session.sessionId,
                            excludedTrackIds = queueTrackIds
                        )
                    )

                    chain.proceed()

                } finally {

                    autoDjSelectionWindow.set(
                        null
                    )
                }

            val afterContext =
                queueReader.read(
                    autoDjInstance
                )

            if (
                afterContext != null
            ) {

                queueSessionTracker
                    .commitGeneratedAdditions(
                        beforeContext = beforeContext,
                        afterContext = afterContext,
                        origin = QueueEntryOrigin.GONESMART
                    )

                logAddedTracks(
                    beforeContext = beforeContext,
                    afterContext = afterContext
                )

                val updatedSession =
                    queueSessionTracker
                        .observe(
                            afterContext
                        )

                maybeScheduleBackgroundPoolRefill(
                    autoDjInstance = autoDjInstance,
                    queueContext = afterContext,
                    session = updatedSession,
                    sizing = poolSizing
                )
            }

            Log.i(
                TAG,
                "SMART DJ POOL STATE | " +
                    recommendationPool.describe(
                        session.sessionId
                    )
            )

            Log.i(
                TAG,
                "========================================"
            )

            result
        }

        Log.i(
            TAG,
            "Auto-DJ refill hook installed successfully"
        )
    }

    private fun installAutoDjSelectionHook(
        param: PackageReadyParam
    ) {

        val autoDjDaoClass =
            param.classLoader.loadClass(
                "kr"
            )

        val selectionMethod =
            autoDjDaoClass.getDeclaredMethod(
                "F1",
                Integer.TYPE
            )

        selectionMethod.isAccessible =
            true

        hook(
            selectionMethod
        ).intercept { chain ->

            val requestedTracks =
                chain.getArg(
                    0
                ) as? Int ?: -1

            val nativeResult =
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

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | GMMP returned no Auto-DJ rows"
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                return@intercept ArrayList<Any>()
            }

            val desiredCount =
                if (
                    requestedTracks > 0
                ) {

                    minOf(
                        requestedTracks,
                        rows.size
                    )

                } else {

                    rows.size
                }

            val smartTrackIds =
                recommendationPool
                    .peek(
                        expectedSessionId = selectionContext.sessionId,
                        count = desiredCount,
                        excludedTrackIds = selectionContext.excludedTrackIds
                    )

            if (
                smartTrackIds.size <
                desiredCount
            ) {

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | " +
                        "needed=$desiredCount | " +
                        "poolAvailable=${smartTrackIds.size}"
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                return@intercept ArrayList<Any>()
            }

            var appliedCount =
                0

            smartTrackIds
                .forEachIndexed { index, trackId ->

                    val row =
                        rows[
                            index
                        ]
                            ?: return@forEachIndexed

                    try {

                        setAutoDjTrackId(
                            row = row,
                            trackId = trackId
                        )

                        appliedCount +=
                            1

                        Log.i(
                            TAG,
                            "SMART DJ SELECT | " +
                                "slot=${index + 1} | " +
                                "trackId=$trackId"
                        )

                    } catch (t: Throwable) {

                        Log.e(
                            TAG,
                            "Failed to replace Auto-DJ row " +
                                "at slot ${index + 1}",
                            t
                        )
                    }
                }

            if (
                appliedCount !=
                desiredCount
            ) {

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | " +
                        "replacement incomplete " +
                        "($appliedCount/$desiredCount)"
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                return@intercept ArrayList<Any>()
            }

            recommendationPool
                .commitSelected(
                    expectedSessionId = selectionContext.sessionId,
                    selectedTrackIds = smartTrackIds
                )

            Log.i(
                TAG,
                "SMART DJ APPLIED | " +
                    "$appliedCount/$desiredCount track(s) replaced | " +
                    recommendationPool.describe(
                        selectionContext.sessionId
                    )
            )

            playerBadgeController
                .setMode(
                    PlayerAutoDjBadgeController.Mode.SMART
                )

            runtimeReporter.report(
                mode = GoneSmartRuntimeContract.MODE_SMART,
                message = "GoneSmart selected $appliedCount track(s) from the current session pool.",
                appendEvent = true
            )

            nativeResult
        }

        Log.i(
            TAG,
            "Auto-DJ selection hook installed successfully"
        )
    }

    private fun updatePlayerBadgeReadiness(
        networkStateOverride: GoneSmartNetworkState? = null,
        poolAvailableOverride: Boolean? = null,
        appendRuntimeEvent: Boolean = false
    ) {

        if (
            !options.enabled
        ) {

            playerBadgeController
                .setMode(
                    PlayerAutoDjBadgeController.Mode.NONE
                )

            return
        }

        val networkState =
            networkStateOverride
                ?: networkStateReader
                    .getState()

        val cachedPoolAvailable =
            poolAvailableOverride
                ?: recommendationPool
                    .hasAnyPending()

        val ready =
            networkState == GoneSmartNetworkState.ONLINE ||
                cachedPoolAvailable

        playerBadgeController
            .setMode(
                if (ready) {
                    PlayerAutoDjBadgeController.Mode.SMART
                } else {
                    PlayerAutoDjBadgeController.Mode.FALLBACK
                }
            )

        runtimeReporter.report(
            mode = if (ready) {
                GoneSmartRuntimeContract.MODE_SMART
            } else {
                GoneSmartRuntimeContract.MODE_STOPPED
            },
            message = if (ready) {
                "GoneSmart enabled and ready."
            } else {
                "GoneSmart enabled, but no network or cached session pool is currently available."
            },
            appendEvent = appendRuntimeEvent
        )
    }

    private fun resetRecommendationPoolForSession(
        session: QueueSessionSnapshot,
        sizing: SmartPoolSizing
    ) {

        pipelineGeneration
            .incrementAndGet()

        synchronized(
            poolFillLock
        ) {

            activePoolFillFuture
                ?.cancel(
                    true
                )

            activePoolFillFuture =
                null

            activePoolFillSessionId =
                -1L
        }

        recommendationPool
            .reset(
                newSessionId = session.sessionId,
                newTargetSize = sizing.targetSize
            )

        Log.i(
            TAG,
            "SMART DJ POOL RESET | " +
                "session=${session.sessionId} | " +
                "target=${sizing.targetSize}"
        )
    }

    private fun ensureRecommendationPoolReady(
        seeds: List<RecommendationSeed>,
        autoDjInstance: Any,
        queueContext: QueueContext,
        session: QueueSessionSnapshot,
        sizing: SmartPoolSizing,
        requestedTracks: Int
    ): Boolean {

        val queueTrackIds =
            queueContext
                .items
                .map {
                    it.track.id
                }
                .toSet()

        if (
            recommendationPool
                .hasEnough(
                    expectedSessionId = session.sessionId,
                    count = requestedTracks,
                    excludedTrackIds = queueTrackIds
                )
        ) {

            return true
        }

        val future =
            startPoolFill(
                seeds = seeds,
                autoDjInstance = autoDjInstance,
                queueContext = queueContext,
                session = session,
                sizing = sizing,
                background = false
            )

        Log.i(
            TAG,
            "SMART DJ WAIT | " +
                "pool empty/insufficient - preparing session pool"
        )

        return try {

            future.get(
                SMART_PREPARE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            val ready =
                recommendationPool
                    .hasEnough(
                        expectedSessionId = session.sessionId,
                        count = requestedTracks,
                        excludedTrackIds = queueTrackIds
                    )

            Log.i(
                TAG,
                "SMART DJ WAIT END | ready=$ready | " +
                    recommendationPool.describe(
                        session.sessionId
                    )
            )

            ready

        } catch (timeoutException: TimeoutException) {

            pipelineGeneration
                .incrementAndGet()

            future.cancel(
                true
            )

            Log.w(
                TAG,
                "SMART DJ WAIT TIMEOUT | native online fallback disabled"
            )

            false

        } catch (throwable: Throwable) {

            Log.e(
                TAG,
                "SMART DJ pool preparation failed; " +
                    "native online fallback disabled",
                throwable
            )

            false
        }
    }

    private fun maybeScheduleBackgroundPoolRefill(
        autoDjInstance: Any,
        queueContext: QueueContext,
        session: QueueSessionSnapshot,
        sizing: SmartPoolSizing
    ) {

        if (
            networkStateReader
                .getState() !=
            GoneSmartNetworkState.ONLINE
        ) {

            return
        }

        if (
            !recommendationPool
                .shouldRefill(
                    expectedSessionId = session.sessionId,
                    sizing = sizing
                )
        ) {

            return
        }

        val seeds =
            seedSelector
                .select(
                    session = session
                )

        if (
            seeds.isEmpty()
        ) {

            return
        }

        Log.i(
            TAG,
            "SMART DJ BACKGROUND REFILL | " +
                recommendationPool.describe(
                    session.sessionId
                )
        )

        startPoolFill(
            seeds = seeds,
            autoDjInstance = autoDjInstance,
            queueContext = queueContext,
            session = session,
            sizing = sizing,
            background = true
        )
    }

    private fun startPoolFill(
        seeds: List<RecommendationSeed>,
        autoDjInstance: Any,
        queueContext: QueueContext,
        session: QueueSessionSnapshot,
        sizing: SmartPoolSizing,
        background: Boolean
    ): Future<Boolean> {

        synchronized(
            poolFillLock
        ) {

            val existing =
                activePoolFillFuture

            if (
                existing != null &&
                !existing.isDone &&
                activePoolFillSessionId ==
                session.sessionId
            ) {

                return existing
            }

            val generation =
                pipelineGeneration
                    .get()

            recommendationPool
                .markRefillAttempt(
                    session.sessionId
                )

            val future =
                pipelineExecutor
                    .submit<Boolean> {

                        val candidateTrackIds =
                            runRecommendationPipeline(
                                seeds = seeds,
                                autoDjInstance = autoDjInstance,
                                queueContext = queueContext,
                                session = session,
                                generation = generation,
                                targetPoolSize = sizing.targetSize
                            )

                        if (
                            generation !=
                            pipelineGeneration.get()
                        ) {

                            Log.i(
                                TAG,
                                "SMART DJ POOL FILL DISCARDED | stale generation"
                            )

                            return@submit false
                        }

                        val added =
                            recommendationPool
                                .mergeCandidates(
                                    expectedSessionId = session.sessionId,
                                    candidateTrackIds = candidateTrackIds,
                                    newTargetSize = sizing.targetSize
                                )

                        Log.i(
                            TAG,
                            "SMART DJ POOL FILL END | " +
                                "background=$background | " +
                                "candidates=${candidateTrackIds.size} | " +
                                "added=$added | " +
                                recommendationPool.describe(
                                    session.sessionId
                                )
                        )

                        added > 0
                    }

            activePoolFillFuture =
                future

            activePoolFillSessionId =
                session.sessionId

            return future
        }
    }

    private fun runRecommendationPipeline(
        seeds: List<RecommendationSeed>,
        autoDjInstance: Any,
        queueContext: QueueContext,
        session: QueueSessionSnapshot,
        generation: Long,
        targetPoolSize: Int
    ): List<Long> {

        return try {

            artistCatalog.ensureLoaded(
                autoDjInstance
            )

            Log.i(
                TAG,
                "========== GONESMART AGGREGATION START =========="
            )

            val rawContributions =
                mutableListOf<RawRecommendationContribution>()

            seeds.forEachIndexed { seedIndex, seed ->

                val seedNumber =
                    seedIndex + 1

                val resolvedInput =
                    trackInputResolver
                        .resolve(
                            seed.track
                        )

                if (
                    resolvedInput.source !=
                    TrackInputSource.TAGS
                ) {

                    Log.i(
                        TAG,
                        "GS INPUT FALLBACK $seedNumber | " +
                            "source=${resolvedInput.source} | " +
                            "rawArtist=${seed.track.artist} | " +
                            "rawTitle=${seed.track.title} | " +
                            "resolvedArtist=${resolvedInput.track.artist} | " +
                            "resolvedTitle=${resolvedInput.track.title}"
                    )
                }

                val metadata =
                    metadataNormalizer
                        .normalize(
                            resolvedInput.track
                        )

                logNormalizedMetadata(
                    seedNumber = seedNumber,
                    metadata = metadata
                )

                if (
                    metadata.searchArtist.isBlank() ||
                    metadata.searchTitle.isBlank()
                ) {

                    return@forEachIndexed
                }

                var seedContributions =
                    collectSeedContributions(
                        seedNumber = seedNumber,
                        seed = seed,
                        metadata = metadata
                    )

                if (
                    seedContributions.isEmpty() &&
                    resolvedInput.source !=
                    TrackInputSource.TAGS
                ) {

                    val providerFallbackTrack =
                        buildProviderFallbackTrack(
                            resolvedInput.track
                        )

                    if (
                        providerFallbackTrack != null
                    ) {

                        val fallbackMetadata =
                            metadataNormalizer
                                .normalize(
                                    providerFallbackTrack
                                )

                        if (
                            fallbackMetadata.searchArtist.isNotBlank() &&
                            fallbackMetadata.searchTitle.isNotBlank()
                        ) {

                            Log.i(
                                TAG,
                                "GS PROVIDER TITLE FALLBACK $seedNumber | " +
                                    "artist=${fallbackMetadata.searchArtist} | " +
                                    "title=${fallbackMetadata.searchTitle}"
                            )

                            seedContributions =
                                collectSeedContributions(
                                    seedNumber = seedNumber,
                                    seed = seed,
                                    metadata = fallbackMetadata
                                )
                        }
                    }
                }

                rawContributions +=
                    seedContributions
            }

            if (
                generation !=
                pipelineGeneration.get()
            ) {

                return emptyList()
            }

            var broadSearchPerformed =
                false

            var merged =
                recommendationAggregator
                    .aggregate(
                        rawContributions
                    )

            logMergedRecommendations(
                merged
            )

            if (
                merged.isEmpty()
            ) {

                Log.i(
                    TAG,
                    "SMART DJ BROAD SEARCH START | " +
                        "reason=no-external-recommendations | " +
                        "seeds=${seeds.size}"
                )

                broadSearchPerformed =
                    true

                val broadContributions =
                    collectBroadSeedContributions(
                        seeds = seeds
                    )

                if (
                    generation !=
                    pipelineGeneration.get()
                ) {

                    return emptyList()
                }

                merged =
                    recommendationAggregator
                        .aggregate(
                            broadContributions
                        )

                Log.i(
                    TAG,
                    "SMART DJ BROAD SEARCH EXTERNAL END | " +
                        "broadContributions=${broadContributions.size} | " +
                        "merged=${merged.size}"
                )

                logMergedRecommendations(
                    merged
                )
            }

            if (
                merged.isEmpty()
            ) {

                Log.w(
                    TAG,
                    "SMART DJ PIPELINE | no external recommendations after broad search"
                )

                return emptyList()
            }

            val library =
                gmmpLibraryReader
                    .read(
                        autoDjInstance
                    )

            Log.i(
                TAG,
                "LOCAL LIBRARY TRACK COUNT = " +
                    library.size
            )

            if (
                library.isEmpty()
            ) {

                return emptyList()
            }

            val poolSeenTrackIds =
                recommendationPool
                    .allSeenTrackIds(
                        session.sessionId
                    )

            val excludedTrackIds =
                buildSet {

                    addAll(
                        queueContext
                            .items
                            .map {
                                it.track.id
                            }
                    )

                    addAll(
                        poolSeenTrackIds
                    )
                }

            var activeRecommendations =
                merged

            var directLocalMatches =
                localLibraryMatcher
                    .match(
                        recommendations = activeRecommendations,
                        library = library,
                        excludedTrackIds = excludedTrackIds
                    )

            /*
             * External services recommend tracks globally, but the user can
             * only play files that exist in the local GMMP library. When the
             * exact recommended recordings are missing, expand cautiously to
             * other local songs by strongly recommended artists and by the
             * current session's seed artists. This is intentionally a lower-
             * scored fallback and therefore cannot outrank a strong exact
             * track match.
             *
             * The artist fallback also understands acronym identities such as
             * "Machine Gun Kelly" <-> "MGK".
             */
            var artistFallbackMatches =
                if (
                    directLocalMatches.size <
                    targetPoolSize
                ) {

                    localArtistFallbackMatcher
                        .match(
                            recommendations = activeRecommendations,
                            seeds = seeds,
                            library = library,
                            excludedTrackIds = excludedTrackIds,
                            maxResults =
                                targetPoolSize * 2
                        )

                } else {

                    emptyList()
                }

            var rawLocalMatches =
                directLocalMatches +
                    artistFallbackMatches

            Log.i(
                TAG,
                "LOCAL MATCH COMBINED | " +
                    "direct=${directLocalMatches.size} | " +
                    "artistFallback=${artistFallbackMatches.size} | " +
                    "total=${rawLocalMatches.size}"
            )

            /*
             * Build 45: exactly one broader provider round is allowed, and
             * only when the normal provider round produced recommendations
             * but not a single local-library candidate (including the local
             * artist fallback). This keeps the common case cheap while giving
             * sparse/local libraries one additional chance.
             *
             * The seed cap remains unchanged. The broad round widens only the
             * provider result breadth: Last.fm asks for more similar tracks,
             * while ListenBrainz may use several accepted recording identities
             * instead of stopping after the first useful one.
             */
            if (
                rawLocalMatches.isEmpty() &&
                !broadSearchPerformed
            ) {

                broadSearchPerformed =
                    true

                Log.i(
                    TAG,
                    "SMART DJ BROAD SEARCH START | " +
                        "reason=no-local-matches | " +
                        "initialRecommendations=${merged.size} | " +
                        "seeds=${seeds.size}"
                )

                val broadContributions =
                    collectBroadSeedContributions(
                        seeds = seeds
                    )

                if (
                    generation !=
                    pipelineGeneration.get()
                ) {

                    return emptyList()
                }

                if (
                    broadContributions.isNotEmpty()
                ) {

                    val broadMerged =
                        recommendationAggregator
                            .aggregate(
                                rawContributions +
                                    broadContributions
                            )

                    if (
                        broadMerged.isNotEmpty()
                    ) {

                        activeRecommendations =
                            broadMerged

                        directLocalMatches =
                            localLibraryMatcher
                                .match(
                                    recommendations = activeRecommendations,
                                    library = library,
                                    excludedTrackIds = excludedTrackIds
                                )

                        artistFallbackMatches =
                            if (
                                directLocalMatches.size <
                                targetPoolSize
                            ) {

                                localArtistFallbackMatcher
                                    .match(
                                        recommendations = activeRecommendations,
                                        seeds = seeds,
                                        library = library,
                                        excludedTrackIds = excludedTrackIds,
                                        maxResults =
                                            targetPoolSize * 2
                                    )

                            } else {

                                emptyList()
                            }

                        rawLocalMatches =
                            directLocalMatches +
                                artistFallbackMatches

                        Log.i(
                            TAG,
                            "SMART DJ BROAD SEARCH END | " +
                                "broadContributions=${broadContributions.size} | " +
                                "merged=${activeRecommendations.size} | " +
                                "direct=${directLocalMatches.size} | " +
                                "artistFallback=${artistFallbackMatches.size} | " +
                                "totalLocal=${rawLocalMatches.size}"
                        )
                    }
                }

                if (
                    rawLocalMatches.isEmpty()
                ) {

                    Log.i(
                        TAG,
                        "SMART DJ BROAD SEARCH EXHAUSTED | no local matches"
                    )
                }
            }

            val duplicateContextTracks =
                buildDuplicateContextTracks(
                    session = session,
                    library = library,
                    additionalTrackIds = poolSeenTrackIds
                )

            val currentOptions =
                options

            val ratingProfile =
                localPreferenceRanker
                    .ratingThresholdProfile(
                        seeds = seeds,
                        library = library,
                        options = currentOptions
                    )

            val rankedLocalMatches =
                localPreferenceRanker
                    .rank(
                        matches = rawLocalMatches,
                        library = library,
                        seeds = seeds,
                        duplicateContextTracks = duplicateContextTracks,
                        options = currentOptions
                    )

            var qualityMatches =
                applyPoolQualityGate(
                    matches = rankedLocalMatches,
                    targetPoolSize = targetPoolSize,
                    mode = "rating"
                )

            if (
                qualityMatches.isEmpty() &&
                ratingProfile.active &&
                currentOptions.fallbackWithoutRatingRestrictions
            ) {

                Log.i(
                    TAG,
                    "SMART DJ RATING FALLBACK START | " +
                        "minimum=${formatScore(ratingProfile.minimumRatingStars)} | " +
                        "smartMedian=${ratingProfile.smartMedianRatingStars?.let { formatScore(it) } ?: "off"} | " +
                        "effective=${formatScore(ratingProfile.effectiveRatingStars)}"
                )

                val fallbackOptions =
                    currentOptions.copy(
                        minimumRatingStars = 0.0,
                        smartRatingEnabled = false
                    )

                val fallbackRankedMatches =
                    localPreferenceRanker
                        .rank(
                            matches = rawLocalMatches,
                            library = library,
                            seeds = seeds,
                            duplicateContextTracks = duplicateContextTracks,
                            options = fallbackOptions
                        )

                val fallbackQualityMatches =
                    applyPoolQualityGate(
                        matches = fallbackRankedMatches,
                        targetPoolSize = targetPoolSize,
                        mode = "rating-fallback"
                    )

                if (
                    fallbackQualityMatches.isNotEmpty()
                ) {

                    qualityMatches =
                        fallbackQualityMatches

                    Log.i(
                        TAG,
                        "SMART DJ RATING FALLBACK APPLIED | " +
                            "accepted=${qualityMatches.size}"
                    )

                    showSessionNoticeOnce(
                        sessionId = session.sessionId,
                        noticeKey = "rating-fallback",
                        message = "No suitable tracks met the current rating rules. GoneSmart is temporarily ignoring Minimum Rating and Smart Rating for this recommendation pool."
                    )

                    runtimeReporter.report(
                        mode = GoneSmartRuntimeContract.MODE_SMART,
                        message = "Rating fallback active: Minimum Rating and Smart Rating were ignored for the current recommendation pool.",
                        appendEvent = true
                    )
                }
            }

            logLocalRecommendations(
                qualityMatches
            )

            if (
                generation !=
                pipelineGeneration.get()
            ) {

                return emptyList()
            }

            qualityMatches
                .map {
                    it.track.id
                }
                .distinct()

        } catch (throwable: Throwable) {

            Log.e(
                TAG,
                "Recommendation aggregation failed",
                throwable
            )

            emptyList()

        } finally {

            Log.i(
                TAG,
                "========== GONESMART AGGREGATION END =========="
            )
        }
    }

    private fun applyPoolQualityGate(
        matches: List<LocalRecommendationMatch>,
        targetPoolSize: Int,
        mode: String
    ): List<LocalRecommendationMatch> {

        val accepted =
            matches
                .filter { match ->

                    match.finalScore >=
                        MIN_POOL_RECOMMENDATION_SCORE &&
                        match.matchScore >=
                        MIN_POOL_LOCAL_MATCH_SCORE
                }
                .take(
                    targetPoolSize
                )

        Log.i(
            TAG,
            "SMART DJ QUALITY GATE | " +
                "mode=$mode | " +
                "input=${matches.size} | " +
                "accepted=${accepted.size} | " +
                "minRecommendation=$MIN_POOL_RECOMMENDATION_SCORE | " +
                "minLocalMatch=$MIN_POOL_LOCAL_MATCH_SCORE"
        )

        return accepted
    }

    private fun buildDuplicateContextTracks(
        session: QueueSessionSnapshot,
        library: List<GmmpLibraryTrack>,
        additionalTrackIds: Set<Long>
    ): List<TrackInfo> {

        val result =
            mutableListOf<TrackInfo>()

        session
            .currentItem
            ?.let {
                result +=
                    it.track
            }

        session
            .pastItems
            .sortedByDescending {
                it.queuePosition
            }
            .take(
                RECENT_DUPLICATE_HISTORY_LIMIT
            )
            .forEach {
                result +=
                    it.track
            }

        session
            .upcomingItems
            .forEach {
                result +=
                    it.track
            }

        result +=
            session.userAnchorTracks

        result +=
            session.recentGeneratedTracks

        if (
            additionalTrackIds.isNotEmpty()
        ) {

            library
                .asSequence()
                .filter {
                    it.track.id in
                        additionalTrackIds
                }
                .forEach {
                    result +=
                        it.track
                }
        }

        return result
            .distinctBy {
                it.id
            }
    }

    private fun setAutoDjTrackId(
        row: Any,
        trackId: Long
    ) {

        val field =
            findField(
                type = row.javaClass,
                name = "a"
            )

        field.isAccessible =
            true

        when (
            field.type
        ) {

            java.lang.Long.TYPE -> {

                field.setLong(
                    row,
                    trackId
                )
            }

            java.lang.Integer.TYPE -> {

                field.setInt(
                    row,
                    trackId.toInt()
                )
            }

            else -> {

                field.set(
                    row,
                    trackId
                )
            }
        }
    }

    private fun collectSeedContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {

        val result =
            mutableListOf<RawRecommendationContribution>()

        result +=
            collectListenBrainzContributions(
                seedNumber = seedNumber,
                seed = seed,
                metadata = metadata,
                broadSearch = broadSearch
            )

        result +=
            collectLastFmContributions(
                seedNumber = seedNumber,
                seed = seed,
                metadata = metadata,
                broadSearch = broadSearch
            )

        return result
    }

    private fun collectBroadSeedContributions(
        seeds: List<RecommendationSeed>
    ): List<RawRecommendationContribution> {

        val result =
            mutableListOf<RawRecommendationContribution>()

        seeds.forEachIndexed { seedIndex, seed ->

            val seedNumber =
                seedIndex + 1

            val resolvedInput =
                trackInputResolver
                    .resolve(
                        seed.track
                    )

            val metadata =
                metadataNormalizer
                    .normalize(
                        resolvedInput.track
                    )

            if (
                metadata.searchArtist.isBlank() ||
                metadata.searchTitle.isBlank()
            ) {

                return@forEachIndexed
            }

            var contributions =
                collectSeedContributions(
                    seedNumber = seedNumber,
                    seed = seed,
                    metadata = metadata,
                    broadSearch = true
                )

            if (
                contributions.isEmpty() &&
                resolvedInput.source !=
                TrackInputSource.TAGS
            ) {

                val providerFallbackTrack =
                    buildProviderFallbackTrack(
                        resolvedInput.track
                    )

                if (
                    providerFallbackTrack != null
                ) {

                    val fallbackMetadata =
                        metadataNormalizer
                            .normalize(
                                providerFallbackTrack
                            )

                    if (
                        fallbackMetadata.searchArtist.isNotBlank() &&
                        fallbackMetadata.searchTitle.isNotBlank()
                    ) {

                        contributions =
                            collectSeedContributions(
                                seedNumber = seedNumber,
                                seed = seed,
                                metadata = fallbackMetadata,
                                broadSearch = true
                            )
                    }
                }
            }

            result +=
                contributions
        }

        return result
    }

    private fun buildProviderFallbackTrack(
        track: TrackInfo
    ): TrackInfo? {

        val title =
            track.title
                ?.trim()
                .orEmpty()

        if (
            title.isBlank()
        ) {

            return null
        }

        val fallbackTitle =
            CUSTOM_NUMBERED_EDIT_SUFFIX_REGEX
                .replace(
                    title,
                    ""
                )
                .trim()

        if (
            fallbackTitle.isBlank() ||
            fallbackTitle ==
            title
        ) {

            return null
        }

        return track.copy(
            title = fallbackTitle
        )
    }

    private fun collectListenBrainzContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {

        val broadListenBrainzContributions =
            mutableListOf<RawRecommendationContribution>()

        var broadListenBrainzIdentityCount =
            0

        try {

            val matches =
                listenBrainzClient
                    .searchRecording(
                        artist = metadata.searchArtist,
                        title = metadata.searchTitle
                    )

            val evaluations =
                matchSelector
                    .evaluateAll(
                        localMetadata = metadata,
                        matches = matches
                    )
                    .filter {
                        it.accepted
                    }
                    .take(
                        if (
                            broadSearch
                        ) {

                            BROAD_LISTENBRAINZ_MAX_RECORDING_MATCHES

                        } else {

                            MAX_RECORDING_MATCHES_TO_TRY
                        }
                    )

            for (
                evaluation in
                evaluations
            ) {

                val similar =
                    try {

                        listenBrainzClient
                            .getSimilarRecordings(
                                evaluation
                                    .match
                                    .recordingMbid
                            )

                    } catch (t: Throwable) {

                        emptyList()
                    }

                if (
                    similar.isEmpty()
                ) {

                    continue
                }

                val highestScore =
                    similar
                        .maxOfOrNull {
                            it.score
                        }
                        ?.takeIf {
                            it > 0.0
                        }
                        ?: 1.0

                val seedWeight =
                    seedWeight(
                        seed
                    )

                Log.i(
                    TAG,
                    (
                        if (
                            broadSearch
                        ) {

                            "LB BROAD CONTRIBUTIONS seed=$seedNumber | "

                        } else {

                            "LB CONTRIBUTIONS seed=$seedNumber | "
                        }
                    ) +
                        "results=${similar.size} | " +
                        "identityConfidence=" +
                        formatScore(
                            evaluation.totalScore
                        )
                )

                val mapped =
                    similar
                        .map { item ->

                            val normalizedSimilarity =
                                (
                                    item.score /
                                        highestScore
                                    )
                                    .coerceIn(
                                        0.0,
                                        1.0
                                    )

                            RawRecommendationContribution(
                                provider =
                                    RecommendationProvider
                                        .LISTENBRAINZ,

                                seed = seed,

                                artist =
                                    item.artistName,

                                title =
                                    item.recordingName,

                                mbid =
                                    item.recordingMbid,

                                providerSimilarity =
                                    normalizedSimilarity,

                                seedWeight =
                                    seedWeight,

                                identityConfidence =
                                    evaluation.totalScore
                                        .coerceIn(
                                            0.0,
                                            1.0
                                        )
                            )
                        }

                if (
                    !broadSearch
                ) {

                    return mapped
                }

                broadListenBrainzContributions +=
                    mapped

                broadListenBrainzIdentityCount +=
                    1

                if (
                    broadListenBrainzIdentityCount >=
                    BROAD_LISTENBRAINZ_IDENTITIES_TO_USE
                ) {

                    break
                }
            }

            if (
                broadSearch &&
                broadListenBrainzContributions.isNotEmpty()
            ) {

                return broadListenBrainzContributions
                    .distinctBy { contribution ->
                        Triple(
                            contribution.artist,
                            contribution.title,
                            contribution.mbid
                        )
                    }
            }

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "ListenBrainz contribution collection failed " +
                    "for seed $seedNumber",
                t
            )
        }

        return emptyList()
    }

    private fun collectLastFmContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {

        if (
            !lastFmClient.isConfigured()
        ) {

            return emptyList()
        }

        val queryCandidates =
            buildLastFmQueryCandidates(
                metadata
            )

        val testedIdentityKeys =
            mutableSetOf<String>()

        for (
            candidate in
            queryCandidates
        ) {

            val identity =
                try {

                    lastFmClient
                        .resolveTrack(
                            artist = candidate.artist,
                            title = candidate.title
                        )

                } catch (t: Throwable) {

                    null
                }
                    ?: continue

            val identityKey =
                buildLastFmIdentityKey(
                    identity
                )

            if (
                identityKey in
                testedIdentityKeys
            ) {

                continue
            }

            testedIdentityKeys +=
                identityKey

            val similar =
                try {

                    lastFmClient
                        .getSimilarTracks(
                            identity = identity,
                            limit =
                                if (
                                    broadSearch
                                ) {

                                    BROAD_LASTFM_RESULT_LIMIT

                                } else {

                                    LASTFM_RESULT_LIMIT
                                }
                        )

                } catch (t: Throwable) {

                    emptyList()
                }

            if (
                similar.isEmpty()
            ) {

                continue
            }

            val identityConfidence =
                lastFmIdentityConfidence(
                    metadata = metadata,
                    candidate = candidate
                )

            val seedWeight =
                seedWeight(
                    seed
                )

            Log.i(
                TAG,
                (
                    if (
                        broadSearch
                    ) {

                        "LASTFM BROAD CONTRIBUTIONS seed=$seedNumber | "

                    } else {

                        "LASTFM CONTRIBUTIONS seed=$seedNumber | "
                    }
                ) +
                    "results=${similar.size} | " +
                    "identityConfidence=" +
                    formatScore(
                        identityConfidence
                    ) +
                    " | source=${candidate.artist} - " +
                    candidate.title
            )

            return similar
                .map { item ->

                    RawRecommendationContribution(
                        provider =
                            RecommendationProvider
                                .LASTFM,

                        seed = seed,

                        artist =
                            item.artistName,

                        title =
                            item.trackName,

                        mbid =
                            item.mbid,

                        providerSimilarity =
                            item.match
                                .coerceIn(
                                    0.0,
                                    1.0
                                ),

                        seedWeight =
                            seedWeight,

                        identityConfidence =
                            identityConfidence
                    )
                }
        }

        return emptyList()
    }

    private fun seedWeight(
        seed: RecommendationSeed
    ): Double {

        val baseWeight =
            when (
                seed.type
            ) {

                SeedType.CURRENT ->
                    1.0

                SeedType.HISTORY ->
                    when (
                        seed.recency
                    ) {

                        1 -> 0.85
                        2 -> 0.72
                        3 -> 0.60
                        else -> 0.50
                    }

                SeedType.UPCOMING ->
                    when (
                        seed.recency
                    ) {

                        1 -> 0.75
                        else -> 0.65
                    }

                SeedType.ANCHOR ->
                    0.85

                SeedType.GENERATED ->
                    1.0
            }

        return (
            baseWeight *
                seed.weightMultiplier
            )
            .coerceIn(
                0.0,
                1.0
            )
    }

    private fun lastFmIdentityConfidence(
        metadata: NormalizedTrackMetadata,
        candidate: LastFmQueryCandidate
    ): Double {

        val exactTitle =
            metadataNormalizer
                .comparisonKey(
                    candidate.title
                ) ==
                metadataNormalizer
                    .comparisonKey(
                        metadata.searchTitle
                    )

        return if (
            exactTitle
        ) {

            1.0

        } else {

            0.75
        }
    }

    private fun buildLastFmQueryCandidates(
        metadata: NormalizedTrackMetadata
    ): List<LastFmQueryCandidate> {

        val remixKeys =
            metadata
                .remixArtists
                .map {
                    metadataNormalizer
                        .comparisonKey(
                            it
                        )
                }
                .toSet()

        val coreArtists =
            metadata
                .artists
                .filter { artist ->

                    metadataNormalizer
                        .comparisonKey(
                            artist
                        ) !in
                        remixKeys
                }
                .ifEmpty {
                    metadata.artists
                }

        val artistCandidates =
            linkedSetOf<String>()

        val primaryArtist =
            coreArtists
                .firstOrNull()
                ?.trim()
                .orEmpty()

        if (
            primaryArtist.isNotBlank()
        ) {

            artistCandidates +=
                primaryArtist
        }

        if (
            coreArtists.size > 1
        ) {

            artistCandidates +=
                coreArtists
                    .joinToString(
                        " & "
                    )
        }

        if (
            primaryArtist.isNotBlank() &&
            metadata.featuredArtists.isNotEmpty()
        ) {

            val featured =
                metadata
                    .featuredArtists
                    .joinToString(
                        " & "
                    )

            artistCandidates +=
                "$primaryArtist feat. $featured"

            if (
                coreArtists.size > 1
            ) {

                artistCandidates +=
                    coreArtists
                        .joinToString(
                            " & "
                        ) +
                        " feat. " +
                        featured
            }
        }

        val titleCandidates =
            linkedSetOf<String>()

        if (
            metadata.searchTitle.isNotBlank()
        ) {

            titleCandidates +=
                metadata.searchTitle
        }

        if (
            metadata.baseTitle.isNotBlank()
        ) {

            titleCandidates +=
                metadata.baseTitle
        }

        val result =
            mutableListOf<LastFmQueryCandidate>()

        artistCandidates
            .forEach { artist ->

                titleCandidates
                    .forEach { title ->

                        result +=
                            LastFmQueryCandidate(
                                artist = artist,
                                title = title
                            )
                    }
            }

        return result
            .distinctBy {

                metadataNormalizer
                    .comparisonKey(
                        it.artist
                    ) +
                    "|" +
                    metadataNormalizer
                        .comparisonKey(
                            it.title
                        )
            }
    }

    private fun buildLastFmIdentityKey(
        identity: LastFmTrackIdentity
    ): String {

        if (
            !identity.mbid.isNullOrBlank()
        ) {

            return "mbid:" +
                identity.mbid
                    .lowercase(
                        java.util.Locale.ROOT
                    )
        }

        return "text:" +
            metadataNormalizer
                .comparisonKey(
                    identity.artistName
                ) +
            "|" +
            metadataNormalizer
                .comparisonKey(
                    identity.trackName
                )
    }

    private fun logQueueSession(
        session: QueueSessionSnapshot
    ) {

        Log.i(
            TAG,
            "QUEUE SESSION | " +
                "id=${session.sessionId} | " +
                "new=${session.isNewSession} | " +
                "reason=${session.reason} | " +
                "items=${session.sessionItems.size} | " +
                "past=${session.pastItems.size} | " +
                "upcoming=${session.upcomingItems.size} | " +
                "manualUpcoming=${session.manualUpcomingItems.size} | " +
                "userAnchors=${session.userAnchorTracks.size} | " +
                "generatedContext=${session.recentGeneratedTracks.size}"
        )
    }

    private fun logMergedRecommendations(
        candidates: List<RecommendationCandidate>
    ) {

        Log.i(
            TAG,
            "========== MERGED RECOMMENDATIONS START =========="
        )

        Log.i(
            TAG,
            "MERGED CANDIDATE COUNT = " +
                candidates.size
        )

        candidates
            .take(
                MERGED_RESULTS_TO_LOG
            )
            .forEachIndexed { index, candidate ->

                val providers =
                    candidate
                        .contributions
                        .map {
                            it.provider
                        }
                        .distinct()

                val seeds =
                    candidate
                        .contributions
                        .map {
                            it.seed.track.id
                        }
                        .distinct()

                Log.i(
                    TAG,
                    "MERGED ${index + 1} | " +
                        "score=${formatScore(candidate.totalScore)} | " +
                        "artist=${candidate.artist} | " +
                        "title=${candidate.title} | " +
                        "providers=$providers | " +
                        "seedIds=$seeds | " +
                        "contributions=" +
                        candidate.contributions.size
                )
            }

        Log.i(
            TAG,
            "========== MERGED RECOMMENDATIONS END =========="
        )
    }

    private fun logLocalRecommendations(
        matches: List<LocalRecommendationMatch>
    ) {

        Log.i(
            TAG,
            "========== LOCAL RECOMMENDATIONS START =========="
        )

        Log.i(
            TAG,
            "LOCAL MATCH COUNT = " +
                matches.size
        )

        matches
            .take(
                LOCAL_RESULTS_TO_LOG
            )
            .forEachIndexed { index, match ->

                Log.i(
                    TAG,
                    "LOCAL ${index + 1} | " +
                        "rankingScore=${formatScore(match.rankingScore)} | " +
                        "baseScore=${formatScore(match.finalScore)} | " +
                        "matchScore=${formatScore(match.matchScore)} | " +
                        "titleScore=${formatScore(match.titleScore)} | " +
                        "artistScore=${formatScore(match.artistScore)} | " +
                        "versionScore=${formatScore(match.versionScore)} | " +
                        "preferenceMultiplier=${formatScore(match.preferenceMultiplier)} | " +
                        "rating=${match.ratingStars ?: "unrated"} | " +
                        "year=${match.libraryTrack.year} | " +
                        "trackId=${match.track.id} | " +
                        "localArtist=${match.track.artist} | " +
                        "localTitle=${match.track.title} | " +
                        "candidateArtist=${match.recommendation.artist} | " +
                        "candidateTitle=${match.recommendation.title}"
                )
            }

        Log.i(
            TAG,
            "========== LOCAL RECOMMENDATIONS END =========="
        )
    }

    private fun logNormalizedMetadata(
        seedNumber: Int,
        metadata: NormalizedTrackMetadata
    ) {

        Log.i(
            TAG,
            "GS NORMALIZED $seedNumber | " +
                "confidence=${metadata.confidence} | " +
                "artists=${metadata.artists} | " +
                "featured=${metadata.featuredArtists} | " +
                "remixArtists=${metadata.remixArtists} | " +
                "baseTitle=${metadata.baseTitle} | " +
                "versionType=${metadata.versionType} | " +
                "versionLabel=${metadata.versionLabel} | " +
                "searchArtist=${metadata.searchArtist} | " +
                "searchTitle=${metadata.searchTitle}"
        )
    }

    private fun formatScore(
        score: Double
    ): String {

        return String.format(
            java.util.Locale.US,
            "%.3f",
            score
        )
    }

    private fun logSeeds(
        seeds: List<RecommendationSeed>
    ) {

        Log.i(
            TAG,
            "========== GONESMART SEEDS START =========="
        )

        seeds.forEachIndexed { index, seed ->

            Log.i(
                TAG,
                "SEED ${index + 1} | " +
                    "type=${seed.type} | " +
                    "recency=${seed.recency} | " +
                    "weight=${formatScore(seedWeight(seed))} | " +
                    "trackId=${seed.track.id} | " +
                    "artist=${seed.track.artist} | " +
                    "title=${seed.track.title}"
            )
        }

        Log.i(
            TAG,
            "========== GONESMART SEEDS END =========="
        )
    }

    private fun logAddedTracks(
        beforeContext: QueueContext,
        afterContext: QueueContext
    ) {

        val previousEntryIds =
            beforeContext
                .items
                .map {
                    it.queueEntryId
                }
                .toSet()

        val addedTracks =
            afterContext
                .items
                .filter {
                    it.queueEntryId !in
                        previousEntryIds
                }

        Log.i(
            TAG,
            "GMMP added ${addedTracks.size} track(s)"
        )

        addedTracks.forEach { item ->

            Log.i(
                TAG,
                "GMMP ADDED | " +
                    "position=${item.queuePosition} | " +
                    "trackId=${item.track.id} | " +
                    "artist=${item.track.artist} | " +
                    "title=${item.track.title}"
            )
        }
    }

    private fun showSessionNoticeOnce(
        sessionId: Long,
        noticeKey: String,
        message: String
    ) {

        if (
            !options.showStatusMessages
        ) {

            return
        }

        val uniqueKey =
            "$sessionId:$noticeKey"

        if (
            shownSessionNotices.add(
                uniqueKey
            )
        ) {

            statusNotifier.show(
                message
            )
        }
    }

    private fun findNoArgMethod(
        type: Class<*>,
        name: String
    ): Method {

        var current:
                Class<*>? =
            type

        while (
            current != null
        ) {

            try {

                return current
                    .getDeclaredMethod(
                        name
                    )

            } catch (
                _: NoSuchMethodException
            ) {

                current =
                    current.superclass
            }
        }

        throw NoSuchMethodException(
            "$name() in ${type.name}"
        )
    }

    private fun findField(
        type: Class<*>,
        name: String
    ): Field {

        var current:
                Class<*>? =
            type

        while (
            current != null
        ) {

            try {

                return current
                    .getDeclaredField(
                        name
                    )

            } catch (
                _: NoSuchFieldException
            ) {

                current =
                    current.superclass
            }
        }

        throw NoSuchFieldException(
            "${type.name}.$name"
        )
    }

    private data class SelectionWindowContext(
        val sessionId: Long,
        val excludedTrackIds: Set<Long>
    )

    private data class LastFmQueryCandidate(
        val artist: String,
        val title: String
    )
}
