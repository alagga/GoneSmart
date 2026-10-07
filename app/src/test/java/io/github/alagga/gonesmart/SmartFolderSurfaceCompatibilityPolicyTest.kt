package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFolderSurfaceCompatibilityPolicyTest {
    @Test fun nativeListIsMaskedOnlyAfterCompleteVerifiedTakeover() {
        assertTrue(
            SmartFolderSurfaceCompatibilityPolicy.canMaskNativeList(
                bindingsReady = true,
                adapterPresent = true,
                adapterMatchesBindings = true
            )
        )
        assertFalse(
            SmartFolderSurfaceCompatibilityPolicy.canMaskNativeList(
                bindingsReady = false,
                adapterPresent = true,
                adapterMatchesBindings = true
            )
        )
        assertFalse(
            SmartFolderSurfaceCompatibilityPolicy.canMaskNativeList(
                bindingsReady = true,
                adapterPresent = false,
                adapterMatchesBindings = false
            )
        )
        assertFalse(
            SmartFolderSurfaceCompatibilityPolicy.canMaskNativeList(
                bindingsReady = true,
                adapterPresent = true,
                adapterMatchesBindings = false
            )
        )
    }
}
