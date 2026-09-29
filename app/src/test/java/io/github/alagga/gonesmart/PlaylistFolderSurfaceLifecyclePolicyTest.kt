package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFolderSurfaceLifecyclePolicyTest {
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
