from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Playlist navigation badge: global layout is a very hot callback while GMMP's
# pager animates. Reuse already-proven native TextViews until they detach,
# disappear, or are rebound to a different localized title. Full structural
# discovery remains the fallback for future layouts / GMMP versions.
# ---------------------------------------------------------------------------
nav_path = Path(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt"
)
nav = nav_path.read_text()

nav = replace_once(
    nav,
    "import java.lang.ref.WeakReference\nimport java.util.WeakHashMap\n",
    "import java.lang.ref.WeakReference\nimport java.lang.reflect.Method\nimport java.util.WeakHashMap\n",
    "navigation Method import",
)

nav = replace_once(
    nav,
    """    private var activityRef: WeakReference<Activity>? = null
    private var decorRef: WeakReference<View>? = null
    private var refreshPending = false
    private var lastRefreshElapsed = Long.MIN_VALUE
""",
    """    private var activityRef: WeakReference<Activity>? = null
    private var decorRef: WeakReference<View>? = null
    private var refreshPending = false
    private var forceRefreshPending = false
    private var lastRefreshElapsed = Long.MIN_VALUE
    private var cachedPlaylistTargets = emptyList<WeakReference<TextView>>()
    private var cachedSmartTargets = emptyList<WeakReference<TextView>>()
    private val adapterGetterCache = mutableMapOf<Class<*>, Method?>()
""",
    "navigation cached state",
)

nav = replace_once(
    nav,
    """    private fun scheduleRefresh(force: Boolean = false) {
        val activity = activityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (refreshPending) return

        val now = SystemClock.elapsedRealtime()
        val delay = if (force || lastRefreshElapsed == Long.MIN_VALUE) {
            0L
        } else {
            (RESCAN_DEBOUNCE_MS - (now - lastRefreshElapsed)).coerceAtLeast(0L)
        }
        refreshPending = true
        main.postDelayed({
            refreshPending = false
            lastRefreshElapsed = SystemClock.elapsedRealtime()
            activityRef?.get()?.let(::refresh)
        }, delay)
    }
""",
    """    private fun scheduleRefresh(force: Boolean = false) {
        val activity = activityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        // With both badges disabled there is nothing to discover once any
        // previous decoration has been restored. Keep the listener installed
        // for future option changes, but make its steady-state callback free.
        if (!playlistEnabled && !smartEnabled &&
            playlistOriginals.isEmpty() && smartOriginals.isEmpty()
        ) return

        forceRefreshPending = forceRefreshPending || force
        if (refreshPending) return

        val now = SystemClock.elapsedRealtime()
        val delay = if (force || lastRefreshElapsed == Long.MIN_VALUE) {
            0L
        } else {
            (RESCAN_DEBOUNCE_MS - (now - lastRefreshElapsed)).coerceAtLeast(0L)
        }
        refreshPending = true
        main.postDelayed({
            refreshPending = false
            lastRefreshElapsed = SystemClock.elapsedRealtime()
            val forceFull = forceRefreshPending
            forceRefreshPending = false
            val current = activityRef?.get() ?: return@postDelayed
            if (!forceFull && refreshCachedTargets(current)) {
                return@postDelayed
            }
            refresh(current)
        }, delay)
    }
""",
    "navigation schedule fast path",
)

nav = replace_once(
    nav,
    """        val playlistTargets = linkedSetOf<TextView>().apply {
            addAll(tabPlaylist)
            if (hasNavigationPair) addAll(playlistCandidates)
        }
        val smartTargets = linkedSetOf<TextView>().apply {
            addAll(tabSmart)
            if (hasNavigationPair) addAll(smartCandidates)
        }

        restoreMissing(playlistOriginals, playlistTargets)
""",
    """        val playlistTargets = linkedSetOf<TextView>().apply {
            addAll(tabPlaylist)
            if (hasNavigationPair) addAll(playlistCandidates)
        }
        val smartTargets = linkedSetOf<TextView>().apply {
            addAll(tabSmart)
            if (hasNavigationPair) addAll(smartCandidates)
        }

        cachedPlaylistTargets = playlistTargets.map(::WeakReference)
        cachedSmartTargets = smartTargets.map(::WeakReference)

        restoreMissing(playlistOriginals, playlistTargets)
""",
    "navigation remember targets",
)

nav = replace_once(
    nav,
    """    private fun reportOnce(
        surface: String,
        playlists: Int,
        smart: Int
    ) {
""",
    """    private fun refreshCachedTargets(activity: Activity): Boolean {
        val playlists = cachedPlaylistTargets.mapNotNull(WeakReference<TextView>::get)
        val smart = cachedSmartTargets.mapNotNull(WeakReference<TextView>::get)
        if (playlists.isEmpty() && smart.isEmpty()) return false

        val playlistNames = listOfNotNull(
            NativeGmmpUiText.string(activity, "playlists"),
            NativeGmmpUiText.string(activity, "playlist")
        ).map(String::trim)
        val smartNames = listOfNotNull(
            NativeGmmpUiText.string(activity, "smart"),
            NativeGmmpUiText.string(activity, "smart_playlist"),
            NativeGmmpUiText.string(activity, "smart_playlists"),
            NativeGmmpUiText.smartPlaylist(activity)
        ).map(String::trim).distinct()

        if (playlists.any { !isStillTarget(it, playlistNames, playlistOriginals) } ||
            smart.any { !isStillTarget(it, smartNames, smartOriginals) }
        ) return false

        playlists.forEach {
            updateSparkle(it, playlistEnabled, playlistOriginals)
        }
        smart.forEach {
            updateSparkle(it, smartEnabled, smartOriginals)
        }
        return true
    }

    private fun isStillTarget(
        view: TextView,
        localizedNames: List<String>,
        originals: WeakHashMap<TextView, CharSequence>
    ): Boolean {
        if (!isVisible(view) || insideClassicDrawer(view) ||
            !hasClickableAncestor(view)
        ) return false

        val current = view.text ?: return false
        val decoratedByUs = current is Spanned &&
            current.getSpans(
                0,
                current.length,
                BaselineCenteredSparkleSpan::class.java
            ).isNotEmpty()
        val nativeTitle = if (decoratedByUs) {
            originals[view] ?: current
        } else {
            current
        }
        return PlaylistDrawerBadgePolicy.matchesExactLocalizedTitle(
            localizedNames,
            nativeTitle.toString()
        )
    }

    private fun reportOnce(
        surface: String,
        playlists: Int,
        smart: Int
    ) {
""",
    "navigation cached refresh helpers",
)

nav = replace_once(
    nav,
    """            val adapterName = runCatching {
                view.javaClass.methods.firstOrNull {
                    it.name == "getAdapter" && it.parameterCount == 0
                }?.invoke(view)?.javaClass?.name
            }.getOrNull()
""",
    """            val adapterGetter = adapterGetterCache.getOrPut(view.javaClass) {
                view.javaClass.methods.firstOrNull {
                    it.name == "getAdapter" && it.parameterCount == 0
                }
            }
            val adapterName = runCatching {
                adapterGetter?.invoke(view)?.javaClass?.name
            }.getOrNull()
""",
    "navigation adapter reflection cache",
)

nav_path.write_text(nav)


# ---------------------------------------------------------------------------
# Playlist Flip: accepted 4.2.1 playback no longer needs a 12 x 250 ms active
# queue-polling loop after every successful native Play. Keep one delayed
# postcondition read (plus one legacy fallback) so unknown/future layouts can
# still fail visibly without sustained SQL/readback traffic.
# ---------------------------------------------------------------------------
flip_path = Path(
    "app/src/main/java/io/github/alagga/gonesmart/QueueFlipController.kt"
)
flip = flip_path.read_text()

old_verify = '''        diagnosticsExecutor.execute {
            repeat(12) { attempt ->
                Thread.sleep(250)
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
                                "currentPosition=${current?.queuePosition ?: -1} | " +
                                "checks=${attempt + 1}"
                        )
                        eventReporter.reportEvent(
                            GoneSmartRuntimeContract.CATEGORY_FLIP,
                            "Playing $sourceKind in reverse: ${expectedIds.size} tracks."
                        )
                        return@execute
                    }
                }
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
                        "FLIP PLAY VERIFIED | kind=$sourceKind | source=legacy | " +
                            "checks=${attempt + 1}"
                    )
                    return@execute
                }
            }
            Log.e(
                TAG,
                "FLIP PLAY VERIFY | $sourceKind playback did not match reversed " +
                    "playlist after 3 seconds"
            )
        }'''

new_verify = '''        diagnosticsExecutor.execute {
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
        }'''

flip = replace_once(
    flip,
    old_verify,
    new_verify,
    "queue flip bounded postcondition verification",
)
flip_path.write_text(flip)

print("runtime performance audit patch applied")
