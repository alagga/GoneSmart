package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQueueEntityTypeResolverTest {
    private open class GenericEntity
    private class QueueEntity
    private class OtherEntity

    private open class GenericDao {
        fun generic(values: Array<GenericEntity>) = values.size
    }

    private open class QueueDaoBase : GenericDao() {
        fun queueSpecific(values: Array<QueueEntity>) = values.size
    }

    private class GeneratedQueueDao : QueueDaoBase() {
        fun erased(values: Array<Any>) = values.size
    }

    @Test fun nearestQueueSpecificArrayTypeBeatsGenericBaseContract() {
        val methods =
            GmmpReflectionPolicy.callableMethods(GeneratedQueueDao::class.java)
        assertEquals(
            QueueEntity::class.java,
            NativeQueueEntityTypeResolver.resolve(
                GeneratedQueueDao::class.java,
                methods
            )
        )
    }

    private class EmbeddedQueueEntity(
        val queueId: Long,
        val trackId: Long,
        val position: Int,
        val shuffle: Int
    )

    private class QueueRelationWrapper(
        val entity: EmbeddedQueueEntity,
        val label: String,
        val extra: Any
    )

    private open class WrappedQueueDaoBase {
        fun queueSpecific(values: Array<QueueRelationWrapper>) = values.size
    }

    private class WrappedQueueDao : WrappedQueueDaoBase()

    @Test fun uniqueNumericEntityInsideQueueWrapperIsUnwrapped() {
        val methods =
            GmmpReflectionPolicy.callableMethods(WrappedQueueDao::class.java)
        assertEquals(
            EmbeddedQueueEntity::class.java,
            NativeQueueEntityTypeResolver.resolve(
                WrappedQueueDao::class.java,
                methods
            )
        )
    }

    private open class AmbiguousBase {
        fun first(values: Array<QueueEntity>) = values.size
        fun second(values: Array<OtherEntity>) = values.size
    }

    private class AmbiguousDao : AmbiguousBase()

    @Test fun ambiguousNearestEntityTypesFailClosed() {
        val methods =
            GmmpReflectionPolicy.callableMethods(AmbiguousDao::class.java)
        assertNull(
            NativeQueueEntityTypeResolver.resolve(
                AmbiguousDao::class.java,
                methods
            )
        )
    }
}
