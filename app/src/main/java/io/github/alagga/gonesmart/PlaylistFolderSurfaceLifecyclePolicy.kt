package io.github.alagga.gonesmart

/**
 * Keeps the native Playlist list masked across fragment detach/reattach.
 * While GoneSmart removes its sibling folder overlay during a tab switch,
 * GMMP may still render the old fragment's exiting RecyclerView. Never
 * restore raw native rows during this handoff while folders stay enabled.
 * All alpha masks remain bounded with a native fail-open on attach failure.
 */
internal object PlaylistFolderSurfaceLifecyclePolicy {
    fun shouldPreHideNativeRows(
        foldersEnabled: Boolean,
        resourceName: String,
        adapterVerified: Boolean
    ): Boolean = foldersEnabled && adapterVerified &&
        resourceName == "playlistListRecyclerView"

    fun shouldRestoreRetainedBrowser(
        listAttached: Boolean,
        listShown: Boolean,
        frontFragment: Boolean
    ): Boolean = listAttached && listShown && frontFragment

    fun shouldBuildBrowser(
        isPicker: Boolean,
        frontFragment: Boolean,
        nativeShown: Boolean,
        hasVisibleBounds: Boolean
    ): Boolean = isPicker ||
        (frontFragment && nativeShown && hasVisibleBounds)

    @Suppress("UNUSED_PARAMETER")
    fun preserveNativeAlphaOnDetach(
        foldersEnabled: Boolean,
        isPicker: Boolean
    ): Boolean = foldersEnabled
}
