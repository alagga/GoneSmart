package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQueueStateAccessorPolicyTest {
    private open class InheritedState {
        @Suppress("UNUSED_PARAMETER")
        fun inheritedWrite(value: Int) = Unit
    }

    private class PreferredState(
        private var pointer: Int
    ) : InheritedState() {
        fun current(): Int = pointer
        fun writeCurrent(value: Int) {
            pointer = value
        }
    }

    private class AmbiguousState(
        private var pointer: Int
    ) {
        fun current(): Int = pointer
        fun first(value: Int) {
            pointer = value
        }
        fun second(value: Int) {
            pointer = value
        }
    }

    private class UnrelatedState(
        private var coincidental: Int
    ) {
        fun current(): Int = coincidental
        fun write(value: Int) {
            coincidental = value
        }
    }

    @Test fun directlyOwnedSetterWinsOverInheritedSetter() {
        val host = PreferredState(19)
        val analysis = NativeQueueStateAccessorPolicy.analyze(host, 19)
        val selection = analysis.methodSelection()

        assertTrue(analysis.hasReadEvidence)
        assertNotNull(selection)
        assertEquals("current", selection!!.getter.name)
        assertEquals("writeCurrent", selection.setter.name)
        assertEquals(PreferredState::class.java, selection.setter.declaringClass)
        assertTrue(
            analysis.numericWriters.any {
                it.name == "inheritedWrite" &&
                    it.declaringClass == InheritedState::class.java
            }
        )

        selection.setter.invoke(host, 7)
        assertEquals(7, (selection.getter.invoke(host) as Number).toInt())
    }

    @Test fun multipleDirectSettersFailClosed() {
        val host = AmbiguousState(19)
        val analysis = NativeQueueStateAccessorPolicy.analyze(host, 19)

        assertTrue(analysis.hasReadEvidence)
        assertNull(analysis.methodSelection())
        assertEquals(2, analysis.directSetters.size)
    }

    @Test fun nonMatchingHostHasNoReadEvidence() {
        val host = UnrelatedState(1)
        val analysis = NativeQueueStateAccessorPolicy.analyze(host, 19)

        assertFalse(analysis.hasReadEvidence)
        assertNull(analysis.methodSelection())
    }
}
