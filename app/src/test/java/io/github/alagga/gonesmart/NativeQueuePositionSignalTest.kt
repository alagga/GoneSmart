package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQueuePositionSignalTest {
    private class PositionHost(private val position: Int) {
        fun current(): Int = position
    }

    private class FastOwner(position: Int) {
        @Suppress("unused")
        val t = PositionHost(position)
    }

    private class AmbiguousHost {
        fun first(): Int = 2
        fun second(): Int = 2
    }

    private class AmbiguousOwner {
        @Suppress("unused")
        val t = AmbiguousHost()
    }

    private class FallbackOwner(position: Int) {
        @Suppress("unused")
        val state = PositionHost(position)
    }

    @Test
    fun preferredStateFieldResolvesUniqueIntGetter() {
        val reading = NativeQueuePositionSignal.read(FastOwner(7))
        assertEquals(7, reading?.value)
        assertEquals(true, reading?.source?.startsWith("field:t->") == true)
    }

    @Test
    fun ambiguousPreferredHostFailsClosed() {
        assertNull(NativeQueuePositionSignal.read(AmbiguousOwner()))
    }

    @Test
    fun structuralFallbackRequiresUniqueEligibleHost() {
        val reading = NativeQueuePositionSignal.read(FallbackOwner(11))
        assertEquals(11, reading?.value)
        assertEquals(true, reading?.source?.startsWith("field:state->") == true)
    }
}
