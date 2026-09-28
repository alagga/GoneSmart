package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeResourceIdPolicyTest {
    @Test fun rejectsNoIdAndGeneratedViewIds() {
        assertFalse(NativeResourceIdPolicy.canResolveEntryName(-1))
        assertFalse(NativeResourceIdPolicy.canResolveEntryName(0))
        assertFalse(NativeResourceIdPolicy.canResolveEntryName(1))
        assertFalse(NativeResourceIdPolicy.canResolveEntryName(4))
        assertFalse(NativeResourceIdPolicy.canResolveEntryName(0x00ffffff))
    }

    @Test fun acceptsFrameworkAndApplicationResourceIds() {
        assertTrue(NativeResourceIdPolicy.canResolveEntryName(0x01010000))
        assertTrue(NativeResourceIdPolicy.canResolveEntryName(0x7f010001))
    }
}
