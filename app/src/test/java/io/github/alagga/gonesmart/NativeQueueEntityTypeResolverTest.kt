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

    @Test fun nearestQueueSpecificArrayContractBeatsGenericBaseContract() {
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

    @Test fun constructorComponentsAreNotReinterpretedAsQueueEntities() {
        val methods =
            GmmpReflectionPolicy.callableMethods(WrappedQueueDao::class.java)
        assertEquals(
            QueueRelationWrapper::class.java,
            NativeQueueEntityTypeResolver.resolve(
                WrappedQueueDao::class.java,
                methods
            )
        )
    }

    // Mirrors the 4.2.1 predicate shape: Predicate(Column, operator, value).
    // The unique custom constructor component is a column descriptor, not an
    // embedded queue_table row and must therefore never replace the array
    // witness itself.
    private class ColumnDescriptor

    private class PredicateWitness(
        val column: ColumnDescriptor,
        val operator: String,
        val value: Any
    )

    private open class PredicateDaoBase {
        fun filtered(values: Array<PredicateWitness>) = values.size
    }

    private class PredicateDao : PredicateDaoBase()

    @Test fun predicateColumnDescriptorIsNotNominatedAsEntity() {
        val methods = GmmpReflectionPolicy.callableMethods(
            PredicateDao::class.java
        )
        assertEquals(
            PredicateWitness::class.java,
            NativeQueueEntityTypeResolver.resolve(
                PredicateDao::class.java,
                methods
            )
        )
    }

    private open class AmbiguousBase {
        fun first(values: Array<QueueEntity>) = values.size
        fun second(values: Array<OtherEntity>) = values.size
    }

    private class AmbiguousDao : AmbiguousBase()

    @Test fun ambiguousNearestArrayContractsFailClosed() {
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
