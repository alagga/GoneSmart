package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixNativeFallbackPolicyTest {
    @Test
    fun `native selection excludes selected invalid and duplicate IDs`() {
        assertEquals(
            listOf(7L, 8L, 9L),
            TrackMixNativeFallbackPolicy.selectNativeTrackIds(
                rawTrackIds = listOf(0L, 42L, 7L, 7L, -2L, 8L, 9L),
                selectedTrackId = 42L,
                requested = 3
            )
        )
    }

    @Test
    fun `native selection remains bounded`() {
        assertEquals(
            listOf(1L, 2L),
            TrackMixNativeFallbackPolicy.selectNativeTrackIds(
                rawTrackIds = listOf(1L, 2L, 3L),
                selectedTrackId = 99L,
                requested = 2
            )
        )
    }

    @Test
    fun `final queue keeps selected then exact fallback order`() {
        assertEquals(
            listOf(42L, 7L, 8L, 9L),
            TrackMixNativeFallbackPolicy.expectedQueueIds(42L, listOf(7L, 8L, 9L))
        )
    }

    @Test
    fun `preloaded current plus one must equal final first fallback`() {
        assertTrue(
            TrackMixNativeFallbackPolicy.preservesPreloadedNext(7L, listOf(7L, 8L))
        )
        assertFalse(
            TrackMixNativeFallbackPolicy.preservesPreloadedNext(9L, listOf(7L, 8L))
        )
        assertTrue(
            TrackMixNativeFallbackPolicy.preservesPreloadedNext(null, emptyList())
        )
    }
}
