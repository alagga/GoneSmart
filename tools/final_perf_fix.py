from pathlib import Path

path = Path("app/src/main/java/io/github/alagga/gonesmart/SmartPlaylistFolderController.kt")
text = path.read_text()
old = '''                    val front = isFrontFragmentView(list)
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
'''
new = '''                    val front = isFrontFragmentView(list) && list.isShown
                    if (front && !browser.nativeContentReady) {
                        // Initial projection is allowed to complete while the
                        // overlay is still INVISIBLE. Visibility is itself the
                        // postcondition of revealInitialContent().
                        val ready = nativeProjectionReady(browser)
                        if (ready || browser.projectionFailOpenAllowed) {
                            revealInitialContent(
                                browser,
                                allowProjectionMismatch =
                                    browser.projectionFailOpenAllowed
                            )
                            scheduleVisibleRowSync(browser)
                        }
                    }

                    val visible = front &&
                        browser.overlay.visibility == View.VISIBLE
                    if (visible) {
                        if (browser.nativeContentReady &&
                            browser.projectionPrepared
                        ) {
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
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"expected one Smart pre-draw lifecycle block, got {count}")
path.write_text(text.replace(old, new, 1))
print("final Smart-folder performance lifecycle fix applied")
