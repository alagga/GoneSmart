package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQueuePositionSignalTest {
    private class DxLikeHost(position: Int) {
        @Suppress("unused")
        val o: Int = position

        fun duplicateRead(): Int = o
    }

    private class UrLikeHost {
        fun b(): Int = 1
    }

    private class Owner(position: Int) {
        @Suppress("unused")
        val p = DxLikeHost(position)

        @Suppress("unused")
        val t = UrLikeHost()
    }

    private class AmbiguousDxHost {
        @Suppress("unused")
        val a: Int = 2

        @Suppress("unused")
        val b: Int = 3
    }

    private class AmbiguousOwner {
        @Suppress("unused")
        val p = AmbiguousDxHost()
    }

    private class WrongOwner {
        @Suppress("unused")
        val t = UrLikeHost()
    }

    @Test
    fun preferredDxFieldWinsOverUnrelatedUrValue() {
        val reading = NativeQueuePositionSignal.read(Owner(7))
        assertEquals(7, reading?.value)
        assertEquals(true, reading?.source?.contains("field:p->") == true)
        assertEquals(true, reading?.source?.endsWith("field:o") == true)
    }

    @Test
    fun ambiguousDxHostWithoutVerifiedFieldFailsClosed() {
        assertNull(NativeQueuePositionSignal.read(AmbiguousOwner()))
    }

    @Test
    fun urOnlyOwnerIsNotAcceptedAsPlaybackPosition() {
        assertNull(NativeQueuePositionSignal.read(WrongOwner()))
    }
}
