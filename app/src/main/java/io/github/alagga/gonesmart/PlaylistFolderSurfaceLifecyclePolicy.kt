package io.github.alagga.gonesmart

/**
 * Keeps the normal Playlists surface isolated from offscreen GMMP pages while
 * preserving the special Add-picker fragment-replacement handoff.
 */
internal object PlaylistFolderSurfaceLifecyclePolicy {
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
