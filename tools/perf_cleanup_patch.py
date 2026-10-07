from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one literal match, got {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, got {count}")
    return updated


smart_path = Path("app/src/main/java/io/github/alagga/gonesmart/SmartPlaylistFolderController.kt")
smart = smart_path.read_text()

smart = replace_once(
    smart,
    """        var touchGuardReported: Boolean = false
        var overscrollReported: Boolean = false
    )""",
    """        var touchGuardReported: Boolean = false
        var overscrollReported: Boolean = false,
        var visibleRowSyncPending: Boolean = false
    )""",
    "smart browser row-sync state",
)

smart = regex_once(
    smart,
    r'''        val scrollDrawListener =
            android\.view\.ViewTreeObserver\.OnPreDrawListener \{
.*?
            \}
        val detachListener = object : View\.OnAttachStateChangeListener \{''',
    '''        val scrollDrawListener =
            android.view.ViewTreeObserver.OnPreDrawListener {
                if (browsers[list] === browser) {
                    syncPagerOverlayVisibility(browser)
                    val front = isFrontFragmentView(list)
                    val visible = front &&
                        browser.overlay.visibility == View.VISIBLE &&
                        list.isShown

                    if (visible) {
                        // If the current folder projection completed while a
                        // Smart-Playlist detail was covering this fragment,
                        // reveal it only in this first real front-surface draw.
                        if (!browser.nativeContentReady) {
                            val ready = nativeProjectionReady(browser)
                            if (ready || browser.projectionFailOpenAllowed) {
                                revealInitialContent(
                                    browser,
                                    allowProjectionMismatch =
                                        browser.projectionFailOpenAllowed
                                )
                                scheduleVisibleRowSync(browser)
                            }
                        } else if (browser.projectionPrepared) {
                            // GMMP may submit its physical Smart root again
                            // after GoneSmart committed a nested projection.
                            // The adapter-count guard is foreground-only and
                            // throttled; hidden ViewPager pages do no work.
                            val now = android.os.SystemClock.uptimeMillis()
                            if (!browser.projectionRepairPending &&
                                now - browser.lastProjectionGuardAt >= 350L
                            ) {
                                browser.lastProjectionGuardAt = now
                                val actual = nativeAdapterItemCount(browser)
                                val expected = browser.nativeOrder.size
                                if (actual != null && actual != expected) {
                                    browser.projectionRepairPending = true
                                    browser.nativeSubmitted = false
                                    Log.i(
                                        TAG,
                                        "SMART FOLDERS PROJECTION DRIFT | expected=" +
                                            expected + " | actual=" + actual +
                                            " | repairing current folder"
                                    )
                                    refresh(browser)
                                }
                            }
                        }

                        // Edge stretch is the only operation that genuinely
                        // needs draw-time sampling. Row reflection/alignment
                        // is coalesced from native scroll/refresh events.
                        syncNativeVerticalOverscroll(browser)
                    } else if (browser.overscrollReported) {
                        resetFolderOverscroll(browser)
                    }
                }
                true
            }
        val detachListener = object : View.OnAttachStateChangeListener {''',
    "smart pre-draw hot path",
)

smart = replace_once(
    smart,
    """    fun onNativeRecyclerScrolled(view: View?, dy: Int) {
        if (dy == 0) return
        val list = view as? ViewGroup ?: return
        val browser = browsers[list] ?: return
        syncFolderRowsScrollByDelta(browser, dy)
    }
""",
    """    fun onNativeRecyclerScrolled(view: View?, dy: Int) {
        if (dy == 0) return
        val list = view as? ViewGroup ?: return
        val browser = browsers[list] ?: return
        syncFolderRowsScrollByDelta(browser, dy)
        scheduleVisibleRowSync(browser)
    }

    private fun scheduleVisibleRowSync(browser: Browser) {
        if (browser.visibleRowSyncPending ||
            browsers[browser.list] !== browser ||
            !browser.list.isAttachedToWindow
        ) return
        browser.visibleRowSyncPending = true
        browser.list.postOnAnimation {
            browser.visibleRowSyncPending = false
            if (browsers[browser.list] !== browser ||
                !browser.list.isAttachedToWindow ||
                !isFrontFragmentView(browser.list) ||
                browser.overlay.visibility != View.VISIBLE
            ) return@postOnAnimation
            alignVisibleNativeTitles(browser)
            syncVisibleSmartRowInteractions(browser)
        }
    }
""",
    "smart scroll row sync",
)

smart = replace_once(
    smart,
    """                settleFolderScrollAfterRefresh(browser, generation)
                positionOverlay(browser)
            }
""",
    """                settleFolderScrollAfterRefresh(browser, generation)
                positionOverlay(browser)
                scheduleVisibleRowSync(browser)
            }
""",
    "smart refresh row sync",
)

smart_path.write_text(smart)

playlist_path = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistFolderPreviewController.kt")
playlist = playlist_path.read_text()

playlist = regex_once(
    playlist,
    r'''        var lastThemeProbe = 0L
        var lastSelectionChromeProbe = 0L
.*?
        val detachListener = object : View\.OnAttachStateChangeListener \{''',
    '''        var lastThemeProbe = 0L
        var lastSelectionChromeProbe = 0L
        // Keep draw-time work scoped to the actually visible page. Adapter
        // notifications are the primary model-refresh path; this listener is
        // only a foreground fallback for palette and lifecycle changes.
        val themeListener = android.view.ViewTreeObserver.OnPreDrawListener {
            val now = android.os.SystemClock.uptimeMillis()
            weakList.get()?.let { current ->
                browsers[current]?.let { browser ->
                    syncPagerOverlayVisibility(browser)
                    val foreground =
                        browser.overlay.visibility == View.VISIBLE &&
                            current.isShown &&
                            isFrontFragmentView(current)
                    if (foreground) {
                        // 4.2.1 renamed the native playlist ActionMode
                        // callback, so visible native chrome remains the
                        // semantic postcondition while selection is active.
                        if (!isPicker(current) &&
                            browser.mainSelection.isSelecting &&
                            now - lastSelectionChromeProbe >= 80L
                        ) {
                            lastSelectionChromeProbe = now
                            val nativeChromeColor =
                                multiSelect.nativeContextBarColor(current)
                            val chromeVisible = nativeChromeColor != null
                            if (chromeVisible) {
                                browser.nativeSelectionChromeSeen = true
                                nativeChromeColor?.let(
                                    multiSelect::rememberNativeSelectionAccent
                                )
                                if (browser.liveSelectionAccent !=
                                    nativeChromeColor
                                ) {
                                    browser.liveSelectionAccent = nativeChromeColor
                                    syncMainSelectionVisuals(browser)
                                }
                            } else if (
                                NativeSelectionChromePolicy.shouldClear(
                                    selectionActive =
                                        browser.mainSelection.isSelecting,
                                    visibleChromeSeen =
                                        browser.nativeSelectionChromeSeen,
                                    chromeVisibleNow = false
                                )
                            ) {
                                clearMainSelectionPresentation(browser)
                                Log.i(
                                    TAG,
                                    "FOLDER MAIN SELECT | visible native ActionMode " +
                                        "ended; cleared"
                                )
                            }
                        }

                        // Adapter events already refresh immediately. Probe
                        // only as a slow foreground fallback so attached
                        // offscreen ViewPager pages are effectively idle.
                        if (now - lastThemeProbe >= 1200L) {
                            lastThemeProbe = now
                            runCatching {
                                positionOverlay(browser)
                                scheduleNativePlaylistRefresh(browser)
                            }.onFailure { error ->
                                Log.e(
                                    TAG,
                                    "FOLDER INLINE ERROR | theme probe",
                                    error
                                )
                                removeBrowser(current)
                            }
                        }
                    }
                }
            }
            true
        }
        val detachListener = object : View.OnAttachStateChangeListener {''',
    "playlist foreground-only pre-draw",
)

playlist_path.write_text(playlist)
print("performance cleanup patch applied")
