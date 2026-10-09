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
}
