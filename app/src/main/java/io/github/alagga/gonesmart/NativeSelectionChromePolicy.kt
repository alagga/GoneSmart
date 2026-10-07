package io.github.alagga.gonesmart

/**
 * Pure lifecycle rule for synthetic Playlist selection presentation.
 *
 * Receiving an ActionMode callback does not mean its contextual bar has
 * already been laid out. Only a bar that was actually observed visible may
 * later disappear and prove that native selection ended.
 */
internal object NativeSelectionChromePolicy {
    fun chooseSelectionColor(
        verifiedNativeSelection: Int?,
        nativeBar: Int?,
        nativeHighlight: Int?,
        controlHighlight: Int?,
        nativeFab: Int?,
        observedAccent: Int?,
        fallback: Int
    ): Int =
        verifiedNativeSelection
            ?: nativeBar
            ?: nativeHighlight
            ?: controlHighlight
            ?: nativeFab
            ?: observedAccent
            ?: fallback

    fun shouldClear(
        selectionActive: Boolean,
        visibleChromeSeen: Boolean,
        chromeVisibleNow: Boolean
    ): Boolean =
        selectionActive && visibleChromeSeen && !chromeVisibleNow
}
