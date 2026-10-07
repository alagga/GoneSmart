package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistNavigationSurfaceHostTest {
    @Test fun recognizesLegacyAndAndroidxPagerClassNames() {
        assertTrue(
            PlaylistNavigationSurfaceHost.isPagerClassName(
                "androidx.viewpager.widget.ViewPager"
            )
        )
        assertTrue(
            PlaylistNavigationSurfaceHost.isPagerClassName(
                "android.support.v4.view.ViewPager"
            )
        )
        assertFalse(
            PlaylistNavigationSurfaceHost.isPagerClassName(
                "androidx.recyclerview.widget.RecyclerView"
            )
        )
    }

    @Test fun recognizesSafeOverlayContainerNames() {
        assertTrue(
            PlaylistNavigationSurfaceHost.isOverlayContainerClassName(
                "com.afollestad.aesthetic.views.AestheticCoordinatorLayout"
            )
        )
        assertTrue(
            PlaylistNavigationSurfaceHost.isOverlayContainerClassName(
                "android.widget.FrameLayout"
            )
        )
        assertTrue(
            PlaylistNavigationSurfaceHost.isOverlayContainerClassName(
                "androidx.constraintlayout.widget.ConstraintLayout"
            )
        )
        assertFalse(
            PlaylistNavigationSurfaceHost.isOverlayContainerClassName(
                "androidx.fragment.app.FragmentContainerView"
            )
        )
        assertFalse(
            PlaylistNavigationSurfaceHost.isOverlayContainerClassName(
                "androidx.recyclerview.widget.RecyclerView"
            )
        )
    }
}
