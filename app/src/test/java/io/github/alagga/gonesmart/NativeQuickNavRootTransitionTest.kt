package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQuickNavRootTransitionTest {
    @Test fun storageReturnAnimatesSuffixBeforeHiddenReset() {
        val old = listOf("root", "a", "b", "c")
        val root = listOf("root")
        assertEquals(NativeQuickNavDiff.Plan(1, 1, 6, 0),
            NativeQuickNavDiff.between(old, root))
        assertTrue(NativeQuickNavDiff.needsHiddenReset(
            NativeQuickNavDiff.itemCount(root.size)))
    }

    @Test fun interruptedRootReturnNeverRetainsPreviousFolder() {
        val next = listOf("root", "other")
        assertEquals(NativeQuickNavDiff.Plan(0, 0, 0, 3),
            NativeQuickNavDiff.between(emptyList(), next))
        assertEquals(NativeQuickNavDiff.Plan(1, 1, 0, 2),
            NativeQuickNavDiff.between(listOf("root"), next))
    }
}
