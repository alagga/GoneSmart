package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpEqualizerObservationResolverTest {

    private class SemanticEqState {
        @Suppress("unused")
        private val gains = DoubleArray(10)

        @Suppress("unused")
        fun pre(value: Double) = Unit

        @Suppress("unused")
        fun band(index: Int, value: Double) = Unit

        @Suppress("unused")
        fun enabled(value: Boolean) = Unit

        @Suppress("unused")
        fun bandCount(): Int = 10

        @Suppress("unused")
        fun gain(index: Int): Double = gains[index]
    }

    private class SameShapeEqState {
        @Suppress("unused")
        private val gains = DoubleArray(10)

        @Suppress("unused")
        fun a(value: Double) = Unit

        @Suppress("unused")
        fun b(index: Int, value: Double) = Unit

        @Suppress("unused")
        fun c(value: Boolean) = Unit

        @Suppress("unused")
        fun d(): Int = 10

        @Suppress("unused")
        fun e(index: Int): Double = gains[index]
    }

    private class WrongShape {
        @Suppress("unused")
        private val gains = DoubleArray(10)

        @Suppress("unused")
        fun pre(value: Double) = Unit
    }

    @Test
    fun classifiesEqualizerBySemanticShapeNotMethodNames() {
        val mapping = GmmpEqualizerObservationResolver.classify(SemanticEqState::class.java)!!

        assertEquals(Double::class.javaPrimitiveType, mapping.preSetter.parameterTypes.single())
        assertEquals(2, mapping.bandSetter.parameterCount)
        assertEquals(Boolean::class.javaPrimitiveType, mapping.enabledSetter.parameterTypes.single())
        assertTrue(mapping.score >= 6)
    }

    @Test
    fun rejectsIncompleteShape() {
        assertNull(GmmpEqualizerObservationResolver.classify(WrongShape::class.java))
    }

    @Test
    fun dynamicResolutionFailsClosedOnEqualCandidates() {
        val resolved = GmmpEqualizerObservationResolver.resolveDynamic(
            sequenceOf(SemanticEqState::class.java, SameShapeEqState::class.java)
        )
        assertNull(resolved)
    }
}
