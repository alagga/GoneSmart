package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQueueObservedStateBindingTest {
    private class GoodState(initial: Int) {
        private var value: Int = initial

        fun current(): Int = value

        fun setCurrent(next: Int) {
            value = next
        }
    }

    private class AmbiguousWriterState(initial: Int) {
        private var value: Int = initial

        fun current(): Int = value

        fun setCurrent(next: Int) {
            value = next
        }

        fun otherWriter(next: Int) {
            value = next
        }
    }

    private class WrongReadState(initial: Int) {
        private var value: Int = initial

        fun current(): Int = value

        fun setCurrent(@Suppress("UNUSED_PARAMETER") next: Int) = Unit
    }

    @Test
    fun acceptsUniqueWriterWithSameHostReadback() {
        val state = GoodState(8)
        val writer = GoodState::class.java.getDeclaredMethod(
            "setCurrent",
            Int::class.javaPrimitiveType
        )
        writer.invoke(state, 9)

        val match = NativeQueueObservedStateBinding.resolve(
            host = state,
            observedWriter = writer,
            observedValue = 9
        )

        assertNotNull(match)
        assertEquals(9, match?.reader?.read(state))
    }

    @Test
    fun rejectsHostWithMultipleDirectWriters() {
        val state = AmbiguousWriterState(8)
        val writer = AmbiguousWriterState::class.java.getDeclaredMethod(
            "setCurrent",
            Int::class.javaPrimitiveType
        )
        writer.invoke(state, 9)

        assertNull(
            NativeQueueObservedStateBinding.resolve(
                host = state,
                observedWriter = writer,
                observedValue = 9
            )
        )
    }

    @Test
    fun rejectsWriterWhenSameHostReadbackDoesNotMatchArgument() {
        val state = WrongReadState(8)
        val writer = WrongReadState::class.java.getDeclaredMethod(
            "setCurrent",
            Int::class.javaPrimitiveType
        )
        writer.invoke(state, 9)

        assertNull(
            NativeQueueObservedStateBinding.resolve(
                host = state,
                observedWriter = writer,
                observedValue = 9
            )
        )
    }
}
