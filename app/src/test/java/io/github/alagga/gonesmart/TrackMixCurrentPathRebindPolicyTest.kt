package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMixCurrentPathRebindPolicyTest {
    @Test
    fun `unique exact path resolves its native row`() {
        val result = TrackMixCurrentPathRebindPolicy.resolve(
            listOf("/a.mp3", "/b.mp3", "/c.mp3"),
            "/b.mp3"
        )
        assertEquals(1, result.index)
        assertEquals(1, result.matchCount)
    }

    @Test
    fun `missing exact path fails closed`() {
        val result = TrackMixCurrentPathRebindPolicy.resolve(
            listOf("/a.mp3", "/b.mp3"),
            "/c.mp3"
        )
        assertNull(result.index)
        assertEquals(0, result.matchCount)
    }

    @Test
    fun `duplicate exact path fails closed`() {
        val result = TrackMixCurrentPathRebindPolicy.resolve(
            listOf("/b.mp3", "/b.mp3"),
            "/b.mp3"
        )
        assertNull(result.index)
        assertEquals(2, result.matchCount)
    }

    @Test
    fun `blank or null path never resolves`() {
        assertNull(
            TrackMixCurrentPathRebindPolicy.resolve(
                listOf("/a.mp3"),
                null
            ).index
        )
        assertNull(
            TrackMixCurrentPathRebindPolicy.resolve(
                listOf("/a.mp3"),
                ""
            ).index
        )
    }

    @Test
    fun `path comparison is exact and never normalized`() {
        val result = TrackMixCurrentPathRebindPolicy.resolve(
            listOf("/Music/BUNT.mp3"),
            "/music/BUNT.mp3"
        )
        assertNull(result.index)
        assertEquals(0, result.matchCount)
    }
}
