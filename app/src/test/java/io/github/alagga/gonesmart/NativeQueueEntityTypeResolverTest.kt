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

    // R8 can obscure the embedded model enough that its queue identity is not
    // obvious from static constructor/field counts. It is still safe to
    // nominate the one custom relation component because the mutation bridge
    // performs exact live Cursor correlation before selecting any writer.
    private class OpaqueEmbeddedEntity(
        val payload: String
    )

    private class OpaqueRelationWrapper(
        val entity: OpaqueEmbeddedEntity,
        val label: String,
        val extra: Any
    )

    private open class OpaqueWrappedQueueDaoBase {
        fun queueSpecific(values: Array<OpaqueRelationWrapper>) = values.size
    }

    private class OpaqueWrappedQueueDao : OpaqueWrappedQueueDaoBase()

    @Test fun uniqueCustomRelationComponentCanBeNominatedBeforeRuntimeCorrelation() {
        val methods = GmmpReflectionPolicy.callableMethods(
            OpaqueWrappedQueueDao::class.java
        )
        assertEquals(
            OpaqueEmbeddedEntity::class.java,
            NativeQueueEntityTypeResolver.resolve(
                OpaqueWrappedQueueDao::class.java,
                methods
            )
        )
    }

    private class AmbiguousNestedA
    private class AmbiguousNestedB

    private class AmbiguousRelationWrapper(
        val first: AmbiguousNestedA,
        val second: AmbiguousNestedB,
        val label: String
    )

    private open class AmbiguousRelationDaoBase {
        fun queueSpecific(values: Array<AmbiguousRelationWrapper>) = values.size
    }

    private class AmbiguousRelationDao : AmbiguousRelationDaoBase()

    @Test fun multipleCustomRelationComponentsFailClosed() {
        val methods = GmmpReflectionPolicy.callableMethods(
            AmbiguousRelationDao::class.java
        )
        // No unique embedded component can be nominated, so the nearest
        // wrapper witness itself remains the only safe type witness.
        assertEquals(
            AmbiguousRelationWrapper::class.java,
            NativeQueueEntityTypeResolver.resolve(
                AmbiguousRelationDao::class.java,
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
        assertNull(
            NativeQueueEntityTypeResolver.resolve(
                AmbiguousDao::class.java,
                GmmpReflectionPolicy.callableMethods(
                    AmbiguousDao::class.java
                )
            )
        )
    }
}
