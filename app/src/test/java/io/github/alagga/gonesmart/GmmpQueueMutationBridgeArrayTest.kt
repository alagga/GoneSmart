package io.github.alagga.gonesmart

import java.lang.reflect.Array
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GmmpQueueMutationBridgeArrayTest {
    private data class NativeRow(val id: Long)

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
}
