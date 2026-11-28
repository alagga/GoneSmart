package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQuickNavGeometryRegressionTest {
    @Test fun rejectsScrolledAndAnimatedOffsets() {
        assertEquals(24, NativeQuickNavInsetPolicy.verifiedNativeStart(-414, 24, 288))
        assertEquals(24, NativeQuickNavInsetPolicy.verifiedNativeStart(null, 24, 288))
        assertNull(NativeQuickNavInsetPolicy.verifiedNativeStart(-414, null, 288))
        assertNull(NativeQuickNavInsetPolicy.verifiedNativeStart(-414, -414, 288))
        assertEquals(24, NativeQuickNavInsetPolicy.verifiedNativeStart(24, 0, 288))
    }
    @Test fun seedStorageThenNativeAppendDoesNotRetainPreviousPath() {
        val seed = listOf("root")
        val next = listOf("root", "new")
        assertEquals(NativeQuickNavDiff.Plan(1, 1, 0, 2),
            NativeQuickNavDiff.between(seed, next))
        assertEquals(NativeQuickNavDiff.Plan(1, 1, 2, 0),
            NativeQuickNavDiff.between(next, seed))
    }
}
