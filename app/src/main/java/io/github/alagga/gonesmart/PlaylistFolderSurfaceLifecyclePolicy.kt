package io.github.alagga.gonesmart

/**
 * Keeps the normal Playlists surface isolated from offscreen GMMP pages while
 * preserving the special Add-picker fragment-replacement handoff.
 */
internal object PlaylistFolderSurfaceLifecyclePolicy {
    fun shouldPreHideNativeRows(
        foldersEnabled: Boolean,
        resourceName: String
    ): Boolean = foldersEnabled && resourceName == "playlistListRecyclerView"

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

    fun preserveNativeAlphaOnDetach(
        foldersEnabled: Boolean,
        isPicker: Boolean
    ): Boolean = foldersEnabled && isPicker
}
