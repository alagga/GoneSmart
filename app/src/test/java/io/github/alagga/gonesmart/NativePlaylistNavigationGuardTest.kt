package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistNavigationGuardTest {
    @Test fun onlyRealPlaylistSurfacesAreEligible() {
        val policy = NativePlaylistRecyclerSurfacePolicy
        assertTrue(policy.isPlaylistCandidate("playlistListRecyclerView", true))
        assertTrue(policy.isPlaylistCandidate("playlistListRecyclerView", false))
        assertTrue(policy.isPlaylistCandidate("unknownPlaylistSurface", true))
        assertFalse(policy.isPlaylistCandidate("trackListRecyclerView", false))
        assertFalse(policy.isPlaylistCandidate("trackListRecyclerView", true))
        assertFalse(policy.isPlaylistCandidate("albumListRecyclerView", false))
    }

    @Test fun multiAddAllowsOneCloseAndSuppressesFurtherNativeCloses() {
        val p = NativePlaylistCloseEventPolicy
        assertTrue(p.eligible(true, 3, 3, 0, true))
        assertTrue(p.eligible(true, 3, 3, 1, true))
        assertFalse(p.eligible(true, 3, 3, 3, true))
        assertFalse(p.eligible(true, 2, 2, 0, false))
        assertFalse(p.eligible(false, 2, 2, 0, true))
        assertFalse(p.eligible(true, 1, 1, 0, true))
        assertFalse(p.eligible(true, 2, 1, 0, true))
    }
}
