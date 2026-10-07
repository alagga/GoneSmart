from pathlib import Path

# Track Mix
p = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
s = p.read_text()
s = s.replace('import java.util.concurrent.TimeUnit\n', '')
s = s.replace('        val before: Snapshot?,\n', '        @Volatile var before: Snapshot?,\n', 1)
s = s.replace(
    '    private val settings = GmmpAutoDjSettingsReader()\n',
    '    private val settings = GmmpAutoDjSettingsReader()\n    private val queueReader = GmmpQueueReader()\n',
    1
)

start = s.index('        // GMMP Room forbids reads on the UI thread. Capture the old queue\n')
end_marker = '        work.execute { runTrackMix(request) }\n'
end = s.index(end_marker, start) + len(end_marker)
new_block = '''        // Capture the pre-Play identity on the worker. The old implementation
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
'''
s = s[:start] + new_block + s[end:]

qs = s.index('    private fun queueSnapshot(): Snapshot? = runCatching {\n')
legacy = s.index('        // Verified 4.2.0 fallback.\n', qs)
legacy_end = s.index('        val dao = field(queue, "r")', legacy)
modern = '''    private fun queueSnapshot(): Snapshot? = runCatching {
        nativeAutoDj?.get()?.let { autoDj ->
            // 4.2.1 can briefly have no uniquely resolvable CURRENT while
            // native Play rebuilds the source. That is a retryable miss, not
            // evidence that the 4.2.0 queue wrapper should be used.
            val context = queueReader.read(autoDj) ?: return@runCatching null
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
'''
s = s[:qs] + modern + s[legacy_end:]
p.write_text(s)

# Player Auto-DJ badge
p = Path('app/src/main/java/io/github/alagga/gonesmart/PlayerAutoDjBadgeController.kt')
s = p.read_text()
s = s.replace('    private var lastScanElapsedMs = Long.MIN_VALUE\n', '')
anchor = '''    private val globalLayoutListener =
        ViewTreeObserver.OnGlobalLayoutListener {
            scheduleRescan()
        }

'''
assert anchor in s
insert = '''    private val rescanRunnable = Runnable {
        val activity = activityRef?.get() ?: return@Runnable
        if (activity.isFinishing || activity.isDestroyed) return@Runnable
        val target = targetRef?.get()
        val marker = nowPlayingMarkerRef?.get()
        if (
            isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME) &&
            isUsableNativeView(marker, NOW_PLAYING_MARKER_RESOURCE_NAME)
        ) {
            if (isVisibleNativeView(target) && isVisibleNativeView(marker)) {
                updateOverlayBounds(target!!)
            } else {
                clearOverlay()
            }
            return@Runnable
        }
        findAndAttach(activity, forceLog = false)
    }

'''
s = s.replace(anchor, anchor + insert, 1)
old = '''            if (
                target != null &&
                marker != null &&
                isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME) &&
                isUsableNativeView(marker, NOW_PLAYING_MARKER_RESOURCE_NAME)
            ) {
                refreshBadge(target)
            } else {
                findAndAttach(activity, forceLog = false)
            }

            scheduleModeMonitor()
'''
new = '''            if (
                isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME) &&
                isUsableNativeView(marker, NOW_PLAYING_MARKER_RESOURCE_NAME)
            ) {
                if (isVisibleNativeView(target) && isVisibleNativeView(marker)) {
                    refreshBadge(target!!)
                } else {
                    clearOverlay()
                }
                scheduleModeMonitor()
            } else {
                // Missing Now Playing must not become an endless 550-ms
                // full decor-tree scan while the Library pager is active.
                clearOverlay()
                scheduleRescan()
            }
'''
assert old in s
s = s.replace(old, new, 1)
old = '''        val decor = activity.window?.decorView ?: return
        installGlobalLayoutListener(decor)
        scheduleModeMonitor()

        decor.post { findAndAttach(activity, forceLog = false) }
        mainHandler.postDelayed({ findAndAttach(activity, forceLog = false) }, 250L)
        mainHandler.postDelayed({ findAndAttach(activity, forceLog = false) }, 900L)
        mainHandler.postDelayed({ findAndAttach(activity, forceLog = true) }, 1800L)
'''
new = '''        val decor = activity.window?.decorView ?: return
        installGlobalLayoutListener(decor)
        // Discover once after layout quiet. A later Now-Playing creation
        // produces its own layout event and schedules another bounded scan.
        scheduleRescan()
'''
assert old in s
s = s.replace(old, new, 1)
start = s.index('    private fun scheduleRescan() {\n')
end = s.index('\n    private fun findAndAttach(', start)
replacement = '''    private fun scheduleRescan() {
        val activity = activityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val target = targetRef?.get()
        val marker = nowPlayingMarkerRef?.get()

        // ViewPager visibility changes do not invalidate a verified anchor.
        if (
            isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME) &&
            isUsableNativeView(marker, NOW_PLAYING_MARKER_RESOURCE_NAME)
        ) {
            mainHandler.removeCallbacks(rescanRunnable)
            if (isVisibleNativeView(target) && isVisibleNativeView(marker)) {
                updateOverlayBounds(target!!)
            } else {
                clearOverlay()
            }
            return
        }

        // True trailing debounce: continuous pager layout waves keep pushing
        // the one structural recovery scan back until the UI is quiet.
        mainHandler.removeCallbacks(rescanRunnable)
        mainHandler.postDelayed(rescanRunnable, RESCAN_DEBOUNCE_MS)
    }
'''
s = s[:start] + replacement + s[end:]
old = '''    private fun isUsableNativeView(view: View?, expectedResourceName: String): Boolean {
        return view != null &&
            view.isAttachedToWindow &&
            view.isShown &&
            view.width > 0 &&
            view.height > 0 &&
            resourceName(view).equals(expectedResourceName, ignoreCase = true)
    }
'''
new = '''    private fun isUsableNativeView(view: View?, expectedResourceName: String): Boolean {
        return view != null &&
            view.isAttachedToWindow &&
            resourceName(view).equals(expectedResourceName, ignoreCase = true)
    }

    private fun isVisibleNativeView(view: View?): Boolean =
        view != null &&
            view.isAttachedToWindow &&
            view.isShown &&
            view.width > 0 &&
            view.height > 0
'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace(
    '            updateOverlayBounds(target)\n            refreshBadge(target)\n            return\n',
    '            updateOverlayBounds(target)\n            refreshBadge(target)\n            scheduleModeMonitor()\n            return\n',
    1
)
s = s.replace(
    '        refreshBadge(target)\n    }\n\n    private fun isUsableNativeView',
    '        refreshBadge(target)\n        scheduleModeMonitor()\n    }\n\n    private fun isUsableNativeView',
    1
)
p.write_text(s)

# Playlist/Smart navigation badge
p = Path('app/src/main/java/io/github/alagga/gonesmart/PlaylistNavigationBadgeController.kt')
s = p.read_text()
old = '''        if (!isVisible(view) || insideClassicDrawer(view) ||
            !hasClickableAncestor(view)
        ) return false
'''
new = '''        // Hidden during a pager transition is still the same native target.
        if (!view.isAttachedToWindow || insideClassicDrawer(view) ||
            !hasClickableAncestor(view)
        ) return false
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)

# Durable rules
p = Path('AGENTS.md')
s = p.read_text()
anchor = '- Full view-tree scans may remain as a bounded recovery path when a cached native target is detached/replaced, but must not run continuously during normal pager/layout waves.\n'
assert anchor in s
addition = '''- `View.isShown == false` during a ViewPager transition does **not** invalidate a semantically verified native UI anchor. Treat visibility as a rendering/discovery condition, not target identity; only detach/replacement/resource mismatch should force structural rediscovery.
- Version-specific legacy fallbacks must never be reached merely because the accepted-version reader has a transient unresolved state. In particular Track Mix on 4.2.1 must retry the semantic Queue reader when CURRENT is temporarily unresolved; it must not reinterpret an obfuscated Auto-DJ field as the 4.2.0 queue wrapper.
- Pre-action Room/Queue reads used as race barriers must not block GMMP's main thread with `Future.get(...)`. Preserve before/after ordering by doing the read on a worker and dispatching the native action back to main afterward.
'''
if addition not in s:
    s = s.replace(anchor, anchor + addition, 1)
p.write_text(s)
