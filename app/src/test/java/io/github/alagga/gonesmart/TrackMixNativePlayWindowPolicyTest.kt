package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMixNativePlayWindowPolicyTest {
    @Test
    fun `unique target keeps target plus initial-size suffix window`() {
        val plan = TrackMixNativePlayWindowPolicy.plan(
            trackIds = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L),
            targetTrackId = 30L,
            initialSize = 5
        )
        assertEquals(2, plan?.startIndex)
        assertEquals(7, plan?.endExclusive)
    }

    @Test
    fun `target near end keeps only available native followers`() {
        val plan = TrackMixNativePlayWindowPolicy.plan(
            trackIds = listOf(10L, 20L, 30L, 40L),
            targetTrackId = 30L,
            initialSize = 5
        )
        assertEquals(2, plan?.startIndex)
        assertEquals(4, plan?.endExclusive)
    }

    @Test
    fun `duplicate target fails closed`() {
        assertNull(
            TrackMixNativePlayWindowPolicy.plan(
                trackIds = listOf(10L, 20L, 20L, 30L),
                targetTrackId = 20L,
                initialSize = 5
            )
        )
    }

    @Test
    fun `missing target fails closed`() {
        assertNull(
            TrackMixNativePlayWindowPolicy.plan(
                trackIds = listOf(10L, 20L, 30L),
                targetTrackId = 99L,
                initialSize = 5
            )
        )
    }

    @Test
    fun `invalid initial size fails closed`() {
        assertNull(
            TrackMixNativePlayWindowPolicy.plan(
                trackIds = listOf(10L),
                targetTrackId = 10L,
                initialSize = 0
            )
        )
    }
}
