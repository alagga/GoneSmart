package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSessionTrackerTest {
    private fun item(
        queueId: Long,
        trackId: Long,
        position: Int,
        state: QueueItemState
    ) = QueueItemInfo(
        orderIndex = position - 1,
        queuePosition = position,
        shufflePosition = position,
        queueEntryId = queueId,
        track = TrackInfo(trackId, "t$trackId", "artist", null, null),
        state = state
    )

    @Test
    fun `GMMP clear row replacement keeps session when Current track is unchanged`() {
        val tracker = QueueSessionTracker()
        val before = tracker.observe(
            QueueContext(
                currentQueuePosition = 1,
                items = listOf(
                    item(100L, 42L, 1, QueueItemState.CURRENT),
                    item(101L, 43L, 2, QueueItemState.UPCOMING),
                    item(102L, 44L, 3, QueueItemState.UPCOMING)
                )
            )
        )
        assertTrue(before.isNewSession)

        val after = tracker.observe(
            QueueContext(
                currentQueuePosition = 1,
                items = listOf(
                    item(900L, 42L, 1, QueueItemState.CURRENT)
                )
            )
        )

        assertFalse(after.isNewSession)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(42L, after.currentItem?.track?.id)
        assertEquals(
            "current track preserved across queue row replacement",
            after.reason
        )
    }

    @Test
    fun `sole replacement row with different Current track starts a new session`() {
        val tracker = QueueSessionTracker()
        val before = tracker.observe(
            QueueContext(
                currentQueuePosition = 1,
                items = listOf(
                    item(100L, 42L, 1, QueueItemState.CURRENT),
                    item(101L, 43L, 2, QueueItemState.UPCOMING)
                )
            )
        )

        val after = tracker.observe(
            QueueContext(
                currentQueuePosition = 1,
                items = listOf(
                    item(900L, 99L, 1, QueueItemState.CURRENT)
                )
            )
        )

        assertTrue(after.isNewSession)
        assertEquals(before.sessionId + 1L, after.sessionId)
    }

    @Test
    fun `explicit Track Auto-DJ boundary drops previous anchors and promotes new Current`() {
        val tracker = QueueSessionTracker()
        val first = tracker.observe(
            QueueContext(
                currentQueuePosition = 1,
                items = listOf(
                    item(100L, 19118L, 1, QueueItemState.CURRENT),
                    item(101L, 4260L, 2, QueueItemState.UPCOMING)
                )
            )
        )
        assertTrue(first.userAnchorTracks.any { it.id == 19118L })

        val forced = tracker.forceNewSession(
            context = QueueContext(
                currentQueuePosition = 1,
                items = listOf(
                    item(900L, 3010L, 1, QueueItemState.CURRENT)
                )
            ),
            reason = "explicit Track Auto-DJ seed"
        )

        assertTrue(forced.isNewSession)
        assertEquals(first.sessionId + 1L, forced.sessionId)
        assertEquals(3010L, forced.currentItem?.track?.id)
        assertEquals(listOf(3010L), forced.userAnchorTracks.map { it.id })
        assertTrue(forced.recentGeneratedTracks.isEmpty())
        assertEquals("explicit Track Auto-DJ seed", forced.reason)
    }
}
