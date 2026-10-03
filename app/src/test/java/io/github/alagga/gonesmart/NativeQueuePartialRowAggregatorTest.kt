package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQueuePartialRowAggregatorTest {
    private data class QueueRow(
        val queueId: Long,
        val trackId: Long,
        val position: Int
    )

    private data class UnrelatedRow(
        val id: Long,
        val value: Int
    )

    private fun fingerprint(row: Any): String = when (row) {
        is QueueRow -> "q:${row.queueId}:${row.trackId}:${row.position}"
        is UnrelatedRow -> "u:${row.id}:${row.value}"
        else -> row.javaClass.name + "@" + System.identityHashCode(row)
    }

    @Test fun aggregatesSameRuntimeModelAcrossReactiveCarriersWithoutHint() {
        val first = QueueRow(101L, 11L, 1)
        val second = QueueRow(102L, 12L, 2)
        val result = NativeQueuePartialRowAggregator.aggregate(
            partials = listOf(
                "W1" to listOf(first),
                "X1" to listOf(second)
            ),
            expectedRows = 2,
            fingerprint = ::fingerprint,
            correlates = { rows -> rows.toSet() == setOf(first, second) }
        )

        assertEquals(1, result.size)
        assertEquals(listOf(first, second), result.single().second)
        assertTrue(result.single().first.startsWith("reactive-aggregate:"))
    }

    @Test fun duplicateRowsDoNotFakeExpectedRowCount() {
        val row = QueueRow(201L, 21L, 1)
        val result = NativeQueuePartialRowAggregator.aggregate(
            partials = listOf(
                "W1" to listOf(row),
                "X1" to listOf(row)
            ),
            expectedRows = 2,
            fingerprint = ::fingerprint,
            correlates = { true }
        )

        assertTrue(result.isEmpty())
    }

    @Test fun unrelatedRuntimeModelsAreNeverMerged() {
        val queue = QueueRow(301L, 31L, 1)
        val unrelated = UnrelatedRow(302L, 2)
        val result = NativeQueuePartialRowAggregator.aggregate(
            partials = listOf(
                "W1" to listOf(queue),
                "X1" to listOf(unrelated)
            ),
            expectedRows = 2,
            fingerprint = ::fingerprint,
            correlates = { true }
        )

        assertTrue(result.isEmpty())
    }

    @Test fun fullCountStillRequiresCursorCorrelation() {
        val first = QueueRow(401L, 41L, 1)
        val second = QueueRow(402L, 42L, 2)
        val result = NativeQueuePartialRowAggregator.aggregate(
            partials = listOf(
                "W1" to listOf(first),
                "X1" to listOf(second)
            ),
            expectedRows = 2,
            fingerprint = ::fingerprint,
            correlates = { false }
        )

        assertTrue(result.isEmpty())
    }
}
