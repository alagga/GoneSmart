package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjNativeMethodShapePolicyTest {
    private class NativeShape {
        fun refill(count: Int) { require(count >= 0) }
        fun selection(count: Int): List<Int> = listOf(count)
        fun wrongResult(count: Int): Boolean = count > 0
        fun wrongParameter(count: Long) { require(count >= 0L) }
        companion object {
            @JvmStatic fun staticRefill(count: Int) { require(count >= 0) }
        }
    }

    @Test fun `native refill must be concrete instance int to void`() {
        assertTrue(AutoDjNativeMethodShapePolicy.isRefill(
            NativeShape::class.java.getDeclaredMethod("refill", Int::class.javaPrimitiveType)
        ))
        assertFalse(AutoDjNativeMethodShapePolicy.isRefill(
            NativeShape::class.java.getDeclaredMethod("wrongResult", Int::class.javaPrimitiveType)
        ))
        assertFalse(AutoDjNativeMethodShapePolicy.isRefill(
            NativeShape::class.java.getDeclaredMethod("wrongParameter", Long::class.javaPrimitiveType)
        ))
        assertFalse(AutoDjNativeMethodShapePolicy.isRefill(
            NativeShape::class.java.getDeclaredMethod("staticRefill", Int::class.javaPrimitiveType)
        ))
    }

    @Test fun `native selection must be instance int to List`() {
        assertTrue(AutoDjNativeMethodShapePolicy.isSelection(
            NativeShape::class.java.getDeclaredMethod("selection", Int::class.javaPrimitiveType)
        ))
        assertFalse(AutoDjNativeMethodShapePolicy.isSelection(
            NativeShape::class.java.getDeclaredMethod("wrongResult", Int::class.javaPrimitiveType)
        ))
        assertFalse(AutoDjNativeMethodShapePolicy.isSelection(
            NativeShape::class.java.getDeclaredMethod("refill", Int::class.javaPrimitiveType)
        ))
    }
}
