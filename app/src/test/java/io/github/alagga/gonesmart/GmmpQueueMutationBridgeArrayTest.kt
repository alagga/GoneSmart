package io.github.alagga.gonesmart

import java.lang.reflect.Array
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GmmpQueueMutationBridgeArrayTest {
    private data class NativeRow(val id: Long)

    private open class BaseOwner {
        @Suppress("UNUSED_PARAMETER")
        fun inherited(value: Int) = Unit
    }

    private class SingleWriterOwner : BaseOwner() {
        @Suppress("UNUSED_PARAMETER")
        fun currentPosition(value: Int) = Unit
        fun unrelated() = Unit
    }

    private class AmbiguousWriterOwner {
        @Suppress("UNUSED_PARAMETER")
        fun first(value: Int) = Unit
        @Suppress("UNUSED_PARAMETER")
        fun second(value: Int) = Unit
    }

    @Test fun deleteArrayUsesVerifiedRuntimeEntityComponent() {
        val first = NativeRow(1L)
        val second = NativeRow(2L)
        val array = GmmpQueueMutationBridge.typedEntityArray(
            NativeRow::class.java,
            listOf(first, second)
        )

        assertEquals(NativeRow::class.java, array.javaClass.componentType)
        assertEquals(2, Array.getLength(array))
        assertSame(first, Array.get(array, 0))
        assertSame(second, Array.get(array, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun deleteArrayRejectsMixedRuntimeEntityTypes() {
        GmmpQueueMutationBridge.typedEntityArray(
            NativeRow::class.java,
            listOf(NativeRow(1L), "not-a-native-row")
        )
    }

    @Test fun ownerWriterRequiresOneDirectIntVoidMethod() {
        val writer = GmmpQueueMutationBridge.uniqueOwnedIntWriter(
            SingleWriterOwner()
        )

        assertNotNull(writer)
        assertEquals("currentPosition", writer!!.name)
        assertEquals(SingleWriterOwner::class.java, writer.declaringClass)
    }

    @Test fun ambiguousOwnerWritersFailClosed() {
        assertNull(
            GmmpQueueMutationBridge.uniqueOwnedIntWriter(
                AmbiguousWriterOwner()
            )
        )
    }
}
