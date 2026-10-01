package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmmpReflectionPolicyTest {
    private abstract class AbstractContract {
        abstract fun select(count: Int): List<String>
    }

    private class ConcreteContract : AbstractContract() {
        override fun select(count: Int): List<String> =
            List(count.coerceAtLeast(0)) { "x" }
    }

    private class Ambiguous {
        fun first(count: Int): List<String> = emptyList()
        fun second(count: Int): List<String> = emptyList()
    }

    @Test
    fun ignoresAbstractContractAndFindsConcreteImplementation() {
        val method = GmmpReflectionPolicy.uniqueConcreteMethod(
            ConcreteContract::class.java
        ) {
            it.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType)
            ) &&
                List::class.java.isAssignableFrom(it.returnType)
        }

        assertEquals("select", method?.name)
    }

    @Test
    fun refusesAmbiguousStructuralCandidates() {
        val method = GmmpReflectionPolicy.uniqueConcreteMethod(
            Ambiguous::class.java
        ) {
            it.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType)
            ) &&
                List::class.java.isAssignableFrom(it.returnType)
        }

        assertNull(method)
    }
}
