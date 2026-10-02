package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReactiveListReaderTest {
    private fun interface Receiver {
        fun accept(value: Any?)
    }

    private class Disposable {
        var disposed = false
        fun dispose() {
            disposed = true
        }
    }

    private class ReactiveSource(
        private val rows: List<Any>
    ) {
        val disposable = Disposable()

        fun subscribe(receiver: Receiver): Disposable {
            receiver.accept(rows)
            return disposable
        }

        // Transformation-like boundary: same source family must not be
        // mistaken for a terminal subscription.
        fun map(receiver: Receiver): ReactiveSource = this
    }

    @Test fun readsOneMatchingListFromCallbackTerminal() {
        val source = ReactiveSource(listOf(Any(), Any(), Any()))
        val result = NativeReactiveListReader.read(
            source = source,
            expectedRows = 3,
            timeoutMs = 100
        )

        assertEquals(3, result?.rows?.size)
        assertTrue(result?.boundary?.contains("subscribe") == true)
        assertTrue(source.disposable.disposed)
    }

    @Test fun rejectsEmissionWithUnexpectedRowCount() {
        val source = ReactiveSource(listOf(Any(), Any()))
        assertNull(
            NativeReactiveListReader.read(
                source = source,
                expectedRows = 3,
                timeoutMs = 50
            )
        )
    }

    private class BlockingCarrier(
        private val rows: List<Any>
    ) {
        fun value(): Any = rows
        fun sourceAgain(): BlockingCarrier = this
    }

    @Test fun readsErasedObjectBlockingTerminal() {
        val result = NativeReactiveListReader.read(
            source = BlockingCarrier(listOf(Any(), Any(), Any())),
            expectedRows = 3,
            timeoutMs = 100
        )

        assertEquals(3, result?.rows?.size)
        assertTrue(result?.boundary?.contains("blocking-object") == true)
    }

    private data class QueueRow(
        val queueId: Long,
        val songId: Long,
        val position: Int
    )

    private class ItemStreamSource(
        private val rows: List<QueueRow>
    ) {
        val disposable = Disposable()

        fun subscribe(receiver: Receiver): Disposable {
            rows.forEach(receiver::accept)
            return disposable
        }
    }

    @Test fun aggregatesHomogeneousQueueEntityStream() {
        val source = ItemStreamSource(
            listOf(
                QueueRow(1L, 11L, 1),
                QueueRow(2L, 22L, 2),
                QueueRow(3L, 33L, 3)
            )
        )
        val result = NativeReactiveListReader.read(
            source = source,
            expectedRows = 3,
            timeoutMs = 100
        )

        assertEquals(3, result?.rows?.size)
        assertEquals(
            listOf(1L, 2L, 3L),
            result?.rows?.map { (it as QueueRow).queueId }
        )
        assertTrue(source.disposable.disposed)
    }

    @Test fun directListStillWorksWithoutReactiveReflection() {
        val result = NativeReactiveListReader.read(
            source = listOf(Any(), Any()),
            expectedRows = 2,
            timeoutMs = 50
        )
        assertEquals(2, result?.rows?.size)
        assertEquals("direct", result?.boundary)
    }
}
