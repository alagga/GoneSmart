package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFolderSurfaceLifecyclePolicyTest {
    @Test fun preHideOnlyPlaylistRowsWhenFolderFeatureEnabled() {
        val p = PlaylistFolderSurfaceLifecyclePolicy
        assertTrue(p.shouldPreHideNativeRows(true, "playlistListRecyclerView"))
        assertFalse(p.shouldPreHideNativeRows(false, "playlistListRecyclerView"))
        assertFalse(p.shouldPreHideNativeRows(true, "trackListRecyclerView"))
    }

    @Test fun retainedOverlayMayReturnOnlyAfterPageBecomesFrontmost() {
        val p = PlaylistFolderSurfaceLifecyclePolicy
        assertFalse(p.shouldRestoreRetainedBrowser(true, true, false))
        assertFalse(p.shouldRestoreRetainedBrowser(true, false, true))
        assertFalse(p.shouldRestoreRetainedBrowser(false, true, true))
        assertTrue(p.shouldRestoreRetainedBrowser(true, true, true))
    }

    @Test fun mainBrowserBuildsOnlyWhenActuallyVisibleAndFrontmost() {
        assertTrue(
            PlaylistFolderSurfaceLifecyclePolicy.shouldBuildBrowser(
                isPicker = false, frontFragment = true,
                nativeShown = true, hasVisibleBounds = true
            )
        )
        assertFalse(
            PlaylistFolderSurfaceLifecyclePolicy.shouldBuildBrowser(
                isPicker = false, frontFragment = false,
                nativeShown = true, hasVisibleBounds = true
            )
        )
        assertFalse(
            PlaylistFolderSurfaceLifecyclePolicy.shouldBuildBrowser(
                isPicker = false, frontFragment = true,
                nativeShown = false, hasVisibleBounds = true
            )
        )
    }

    @Test fun pickerCanPrepareOutsideMainFragmentVisibility() {
        assertTrue(
            PlaylistFolderSurfaceLifecyclePolicy.shouldBuildBrowser(
                isPicker = true, frontFragment = false,
                nativeShown = false, hasVisibleBounds = false
            )
        )
    }

    @Test fun onlyPickerMayKeepNativeListHiddenAcrossDetach() {
        assertTrue(
            PlaylistFolderSurfaceLifecyclePolicy.preserveNativeAlphaOnDetach(
                foldersEnabled = true, isPicker = true
            )
        )
        assertFalse(
            PlaylistFolderSurfaceLifecyclePolicy.preserveNativeAlphaOnDetach(
                foldersEnabled = true, isPicker = false
            )
        )
        assertFalse(
            PlaylistFolderSurfaceLifecyclePolicy.preserveNativeAlphaOnDetach(
                foldersEnabled = false, isPicker = true
            )
        )
    }
}
