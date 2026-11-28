package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQuickNavSourcePolicyTest {
    @Test fun acceptsAlternatingMetadataAndSeparatorWithoutClassName() {
        assertTrue(NativeQuickNavSourcePolicy.alternates(3, 0, 1, 0))
        assertTrue(NativeQuickNavSourcePolicy.alternates(5, 11, 4, 11))
    }

    @Test fun rejectsIncompleteAndUnrelatedRecyclerData() {
        assertFalse(NativeQuickNavSourcePolicy.alternates(1, 0, 1, 0))
        assertFalse(NativeQuickNavSourcePolicy.alternates(4, 0, 1, 0))
        assertFalse(NativeQuickNavSourcePolicy.alternates(3, 0, 0, 0))
        assertFalse(NativeQuickNavSourcePolicy.alternates(3, 0, 1, 2))
        assertFalse(NativeQuickNavSourcePolicy.alternates(3, null, 1, null))
    }
}
