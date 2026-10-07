package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQueueEntityAdapterTypeResolverTest {
    private interface Binder {
        fun bindLong(index: Int, value: Long)
    }

    private interface NoiseBinder {
        fun ignore(value: Long)
    }

    private interface GenericNoise<T>

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

    private class ConcreteStatementQueueAdapter {
        fun M(): String =
            "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"

        fun G(statement: ConcreteStatement?, value: Any) {
            val row = value as QueueRow
            statement?.bindLong(1, row.position.toLong())
            statement?.bindLong(2, row.queueId)
        }
    }

    private abstract class GenericAdapter<T> {
        abstract fun M(): String
        abstract fun G(statement: ConcreteStatement?, value: T)
    }

    private class SyntheticBridgeQueueAdapter : GenericAdapter<QueueRow>() {
        override fun M(): String =
            "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"

        override fun G(statement: ConcreteStatement?, value: QueueRow) {
            statement?.bindLong(1, value.position.toLong())
            statement?.bindLong(2, value.queueId)
        }
    }

    private class SyntheticBridgeQueueAdapterWithGenericNoise :
        GenericAdapter<QueueRow>(), GenericNoise<OtherRow> {
        override fun M(): String =
            "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"

        override fun G(statement: ConcreteStatement?, value: QueueRow) {
            statement?.bindLong(1, value.position.toLong())
            statement?.bindLong(2, value.queueId)
        }
    }

    private open class InheritedBinderNoise {
        @Suppress("unused")
        fun G(binder: NoiseBinder, value: Any) {
            val row = value as OtherRow
            binder.ignore(row.id)
        }
    }

    private class QueueAdapterWithInheritedBinder : InheritedBinderNoise() {
        fun M(): String =
            "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"

        fun G(statement: ConcreteStatement?, value: Any) {
            val row = value as QueueRow
            statement?.bindLong(1, row.position.toLong())
            statement?.bindLong(2, row.queueId)
        }
    }

    private open class DuplicateSqlBase {
        open fun M(): String =
            "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"
    }

    private class DuplicateSqlQueueAdapter : DuplicateSqlBase() {
        override fun M(): String =
            "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"

        fun G(statement: ConcreteStatement?, value: Any) {
            val row = value as QueueRow
            statement?.bindLong(1, row.position.toLong())
            statement?.bindLong(2, row.queueId)
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

    private class NonQueueOnlyDao {
        @Suppress("unused")
        private val unrelated = NonQueueAdapter()
    }

    private class ConcreteStatementQueueDao {
        @Suppress("unused")
        private val update = ConcreteStatementQueueAdapter()
    }

    private class SyntheticBridgeQueueDao {
        @Suppress("unused")
        private val update = SyntheticBridgeQueueAdapter()
    }

    private class SyntheticBridgeWithGenericNoiseQueueDao {
        @Suppress("unused")
        private val update = SyntheticBridgeQueueAdapterWithGenericNoise()
    }

    private class InheritedBinderNoiseQueueDao {
        @Suppress("unused")
        private val update = QueueAdapterWithInheritedBinder()
    }

    private class DuplicateSqlQueueDao {
        @Suppress("unused")
        private val update = DuplicateSqlQueueAdapter()
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
        assertEquals(true, result?.evidence?.contains("queue-sql"))
    }

    @Test fun unrelatedOwnedDmlDoesNotClaimQueueEntityOwnership() {
        assertNull(
            NativeQueueEntityAdapterTypeResolver.resolve(NonQueueOnlyDao())
        )
    }

    @Test fun concreteStatementContractStillExposesErasedEntityCast() {
        val result = NativeQueueEntityAdapterTypeResolver.resolve(
            ConcreteStatementQueueDao()
        )

        assertEquals(QueueRow::class.java, result?.modelClass)
        assertEquals(true, result?.evidence?.contains("null-statement"))
        assertEquals(true, result?.evidence?.contains("queue-sql"))
    }

    @Test fun syntheticRoomBridgeDoesNotDisappearFromOwnedAdapterResolution() {
        val bridges = SyntheticBridgeQueueAdapter::class.java.declaredMethods
            .filter {
                (it.isSynthetic || it.isBridge) &&
                    it.name == "G" &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[1] == Any::class.java
            }
        assertTrue("expected JVM erased bridge", bridges.isNotEmpty())

        val result = NativeQueueEntityAdapterTypeResolver.resolve(
            SyntheticBridgeQueueDao()
        )
        assertEquals(QueueRow::class.java, result?.modelClass)
    }

    @Test fun genericSuperclassEntityBeatsUnrelatedGenericInterfaceNoise() {
        val result = NativeQueueEntityAdapterTypeResolver.resolve(
            SyntheticBridgeWithGenericNoiseQueueDao()
        )

        assertEquals(QueueRow::class.java, result?.modelClass)
        assertEquals(true, result?.evidence?.contains(":generic:"))
    }

    @Test fun adapterDeclaredBinderBeatsInheritedBinderNoise() {
        val result = NativeQueueEntityAdapterTypeResolver.resolve(
            InheritedBinderNoiseQueueDao()
        )

        assertEquals(QueueRow::class.java, result?.modelClass)
        assertEquals(true, result?.evidence?.contains("binder-cast"))
        assertEquals(true, result?.evidence?.contains("null-statement"))
    }

    @Test fun identicalOverriddenSqlIsOneSemanticQueueBoundary() {
        val result = NativeQueueEntityAdapterTypeResolver.resolve(
            DuplicateSqlQueueDao()
        )

        assertEquals(QueueRow::class.java, result?.modelClass)
        assertEquals(true, result?.evidence?.contains("queue-sql"))
    }

    @Test fun differentQueueAdapterEntityTypesFailClosed() {
        assertNull(
            NativeQueueEntityAdapterTypeResolver.resolve(AmbiguousDao())
        )
    }
}
