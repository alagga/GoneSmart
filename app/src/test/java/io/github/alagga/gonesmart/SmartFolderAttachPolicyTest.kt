package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFolderAttachPolicyTest {
    @Test fun onlyFrontSmartSurfaceMayBeMasked() {
        assertTrue(SmartFolderAttachPolicy.mayMaskNativeRows(true))
        assertFalse(SmartFolderAttachPolicy.mayMaskNativeRows(false))
    }

    @Test fun backgroundSmartListCannotSuppressNativeRootLifecycle() {
        assertTrue(
            SmartFolderAttachPolicy.mayControlNativeRootSubmission(true)
        )
        assertFalse(
            SmartFolderAttachPolicy.mayControlNativeRootSubmission(false)
        )
    }
}
