package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQueueEntityAdapterTypeResolverTest {
    private interface Binder {
        fun bindLong(index: Int, value: Long)
    }

    private data class QueueRow(
        val queueId: Long,
        val trackId: Long,
        val position: Int,
        val shuffle: Int
    )

    private data class OtherRow(
        val id: Long
    )

    private class QueueAdapter {
        fun M(): String =
            "INSERT OR REPLACE INTO queue_table " +
                "(queue_id, queue_track_id, queue_position, " +
                "queue_shuffle_position) VALUES (?,?,?,?)"

        fun G(binder: Binder, value: Any) {
            val row = value as QueueRow
            binder.bindLong(1, row.queueId)
            binder.bindLong(2, row.trackId)
            binder.bindLong(3, row.position.toLong())
            binder.bindLong(4, row.shuffle.toLong())
        }
    }

    private class OtherQueueAdapter {
        fun M(): String =
            "DELETE FROM queue_table WHERE queue_id = ?"

        fun G(binder: Binder, value: Any) {
            val row = value as OtherRow
            binder.bindLong(1, row.id)
        }
    }

    private class NonQueueAdapter {
        fun M(): String =
            "INSERT INTO tracks(song_id) VALUES (?)"

        fun G(binder: Binder, value: Any) {
            binder.bindLong(1, value.hashCode().toLong())
        }
    }

    private class QueueDao {
        @Suppress("unused")
        private val insert = QueueAdapter()

        @Suppress("unused")
        private val unrelated = NonQueueAdapter()
    }

    private class AmbiguousDao {
        @Suppress("unused")
        private val first = QueueAdapter()

        @Suppress("unused")
        private val second = OtherQueueAdapter()
    }

    @Test fun infersEntityFromGeneratedQueueBinderCast() {
        val result = NativeQueueEntityAdapterTypeResolver.resolve(QueueDao())

        assertEquals(QueueRow::class.java, result?.modelClass)
        assertEquals(true, result?.evidence?.contains("binder-cast"))
    }

    @Test fun differentQueueAdapterEntityTypesFailClosed() {
        assertNull(
            NativeQueueEntityAdapterTypeResolver.resolve(AmbiguousDao())
        )
    }
}
