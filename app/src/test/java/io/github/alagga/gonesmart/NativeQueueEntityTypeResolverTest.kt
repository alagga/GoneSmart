package io.github.alagga.gonesmart

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

    @Test fun nearestCustomArrayContractDoesNotNominateMutationEntity() {
        val methods =
            GmmpReflectionPolicy.callableMethods(GeneratedQueueDao::class.java)
        assertNull(
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

    @Test fun constructorComponentsCannotReintroduceAnEntityHint() {
        val methods =
            GmmpReflectionPolicy.callableMethods(WrappedQueueDao::class.java)
        assertNull(
            NativeQueueEntityTypeResolver.resolve(
                WrappedQueueDao::class.java,
                methods
            )
        )
    }

    // Mirrors the proven 4.2.1 shape Predicate(Column, operator, value).
    // Neither the predicate nor its custom column component is a Queue row.
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

    @Test fun predicateWitnessReturnsNoEntityHint() {
        val methods = GmmpReflectionPolicy.callableMethods(
            PredicateDao::class.java
        )
        assertNull(
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

    @Test fun ambiguousArrayContractsAlsoFailClosed() {
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
