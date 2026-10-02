package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQueueEntityAdapterTypeResolverTest {
    private interface Binder {
        fun bindLong(index: Int, value: Long)
    }

    private class ConcreteStatement {
        fun bindLong(index: Int, value: Long) = Unit
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
            "INSERT OR REPLACE INTO q " +
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

    /**
     * Mirrors the r25 GMMP shape G(yb4,Object): yb4 is opaque to GoneSmart
     * and must not be required to be an interface. Nullable here models the
     * Java-generated Room adapter accepting a null fake statement until the
     * erased entity bridge performs its cast.
     */
    private class ConcreteStatementQueueAdapter {
        fun M(): String =
            "UPDATE q SET queue_position = ? WHERE queue_id = ?"

        fun G(statement: ConcreteStatement?, value: Any) {
            val row = value as QueueRow
            statement?.bindLong(1, row.position.toLong())
            statement?.bindLong(2, row.queueId)
        }
    }

    private class OtherQueueAdapter {
        fun M(): String =
            "DELETE FROM q WHERE queue_id = ?"

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

    private class ConcreteStatementQueueDao {
        @Suppress("unused")
        private val update = ConcreteStatementQueueAdapter()
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
        assertEquals(true, result?.evidence?.contains("owned-dml"))
    }

    @Test fun concreteStatementContractStillExposesErasedEntityCast() {
        val result = NativeQueueEntityAdapterTypeResolver.resolve(
            ConcreteStatementQueueDao()
        )

        assertEquals(QueueRow::class.java, result?.modelClass)
        assertEquals(true, result?.evidence?.contains("null-statement"))
        assertEquals(true, result?.evidence?.contains("owned-dml"))
    }

    @Test fun differentQueueAdapterEntityTypesFailClosed() {
        assertNull(
            NativeQueueEntityAdapterTypeResolver.resolve(AmbiguousDao())
        )
    }
}
