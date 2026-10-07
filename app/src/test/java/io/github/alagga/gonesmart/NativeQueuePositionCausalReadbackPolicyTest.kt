package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeQueuePositionCausalReadbackPolicyTest {
    private class MethodState {
        private var value = 8

        @Suppress("unused")
        fun current(): Int = value

        @Suppress("unused")
        fun write(next: Int) {
            value = next
        }
    }

    private class MethodAndFieldState {
        @JvmField
        var value: Int = 4

        @Suppress("unused")
        fun current(): Int = value

        @Suppress("unused")
        fun write(next: Int) {
            value = next
        }
    }

    private class AmbiguousMethodState {
        private var value = 2

        @Suppress("unused")
        fun first(): Int = value

        @Suppress("unused")
        fun second(): Int = value

        @Suppress("unused")
        fun write(next: Int) {
            value = next
        }
    }

    @Test
    fun `natural writer proves same-host getter without relying on name`() {
        val state = MethodState()
        val before = NativeQueuePositionCausalReadbackPolicy.snapshot(state)

        state.write(9)
        val readback = NativeQueuePositionCausalReadbackPolicy.select(
            state,
            before,
            9
        )

        assertNotNull(readback)
        assertEquals(9, readback!!.read(state))
        assertEquals(
            MethodState::class.java.name + ".method:current",
            readback.description
        )
    }

    @Test
    fun `direct getter is preferred over matching backing field`() {
        val state = MethodAndFieldState()
        val before = NativeQueuePositionCausalReadbackPolicy.snapshot(state)

        state.write(7)
        val readback = NativeQueuePositionCausalReadbackPolicy.select(
            state,
            before,
            7
        )

        assertNotNull(readback)
        assertEquals(7, readback!!.read(state))
        assertEquals(
            MethodAndFieldState::class.java.name + ".method:current",
            readback.description
        )
    }

    @Test
    fun `multiple changed direct getters fail closed`() {
        val state = AmbiguousMethodState()
        val before = NativeQueuePositionCausalReadbackPolicy.snapshot(state)

        state.write(6)

        assertNull(
            NativeQueuePositionCausalReadbackPolicy.select(
                state,
                before,
                6
            )
        )
    }

    @Test
    fun `unchanged value does not prove a writer`() {
        val state = MethodState()
        val before = NativeQueuePositionCausalReadbackPolicy.snapshot(state)

        assertNull(
            NativeQueuePositionCausalReadbackPolicy.select(
                state,
                before,
                8
            )
        )
    }
}