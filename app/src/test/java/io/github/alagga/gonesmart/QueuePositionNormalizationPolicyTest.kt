package io.github.alagga.gonesmart

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull

class QueuePositionNormalizationPolicyTest {
    @Test
    fun `sparse Track Auto-DJ positions are rebased without changing order`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(10L, 1),
                QueuePositionNormalizationPolicy.Row(11L, 5905),
                QueuePositionNormalizationPolicy.Row(12L, 5906),
                QueuePositionNormalizationPolicy.Row(13L, 5907),
                QueuePositionNormalizationPolicy.Row(14L, 5908)
            ),
            currentQueueId = 10L
        )!!

        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), plan.orderedQueueIds)
        assertEquals(listOf(1, 2, 3, 4, 5), plan.normalizedPositions)
        assertEquals(1, plan.currentNewPosition)
    }

    @Test
    fun `normalization preserves the verified absolute current position`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(20L, 5908),
                QueuePositionNormalizationPolicy.Row(21L, 5912),
                QueuePositionNormalizationPolicy.Row(22L, 5913)
            ),
            currentQueueId = 21L
        )!!

        assertEquals(listOf(5911, 5912, 5913), plan.normalizedPositions)
        assertEquals(5912, plan.currentNewPosition)
    }

    @Test
    fun `first Track Mix seed can stay at position five while refill gaps close`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(50L, 5),
                QueuePositionNormalizationPolicy.Row(51L, 11),
                QueuePositionNormalizationPolicy.Row(52L, 12),
                QueuePositionNormalizationPolicy.Row(53L, 13),
                QueuePositionNormalizationPolicy.Row(54L, 14)
            ),
            currentQueueId = 50L
        )!!

        assertEquals(listOf(5, 6, 7, 8, 9), plan.normalizedPositions)
        assertEquals(5, plan.currentNewPosition)
    }

    @Test
    fun `already contiguous positions need no native mutation`() {
        assertNull(
            QueuePositionNormalizationPolicy.plan(
                rows = listOf(
                    QueuePositionNormalizationPolicy.Row(30L, 1),
                    QueuePositionNormalizationPolicy.Row(31L, 2)
                ),
                currentQueueId = 31L
            )
        )
    }

    @Test
    fun `ambiguous native identity fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            QueuePositionNormalizationPolicy.plan(
                rows = listOf(
                    QueuePositionNormalizationPolicy.Row(40L, 1),
                    QueuePositionNormalizationPolicy.Row(40L, 2)
                ),
                currentQueueId = 40L
            )
        }
    }
}
