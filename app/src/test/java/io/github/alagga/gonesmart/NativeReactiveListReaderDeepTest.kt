package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReactiveListReaderDeepTest {
    private data class QueueRow(
        val queueId: Long,
        val songId: Long,
        val position: Int,
        val shufflePosition: Int
    )

    private class Snapshot(
        val rows: List<QueueRow>
    )

    private class NestedBlockingCarrier(
        private val snapshot: Snapshot
    ) {
        fun value(): Any = snapshot
    }

    @Test fun readsUniqueNestedListFromErasedBlockingValue() {
        val expected = listOf(
            QueueRow(101L, 11L, 1, 3),
            QueueRow(102L, 12L, 2, 1),
            QueueRow(103L, 13L, 3, 2)
        )
        val result = NativeReactiveListReader.read(
            source = NestedBlockingCarrier(Snapshot(expected)),
            expectedRows = expected.size,
            timeoutMs = 100
        )

        assertEquals(expected, result?.rows)
        assertTrue(result?.boundary?.contains("blocking-object") == true)
    }

    private fun interface Receiver {
        fun accept(value: Any?)
    }

    private class Disposable {
        var disposed = false
        fun dispose() {
            disposed = true
        }
    }

    private class RelationRow(
        val entity: QueueRow,
        val label: String
    )

    private class RelationStream(
        private val rows: List<QueueRow>
    ) {
        val disposable = Disposable()

        fun subscribe(receiver: Receiver): Disposable {
            rows.forEachIndexed { index, row ->
                receiver.accept(RelationRow(row, "row-$index"))
            }
            return disposable
        }
    }

    @Test fun unwrapsUniqueQueueShapedEntityFromRelationEmissions() {
        val expected = listOf(
            QueueRow(201L, 21L, 1, 2),
            QueueRow(202L, 22L, 2, 3),
            QueueRow(203L, 23L, 3, 1)
        )
        val source = RelationStream(expected)
        val result = NativeReactiveListReader.read(
            source = source,
            expectedRows = expected.size,
            timeoutMs = 200
        )

        assertEquals(expected, result?.rows)
        assertTrue(source.disposable.disposed)
    }
}
