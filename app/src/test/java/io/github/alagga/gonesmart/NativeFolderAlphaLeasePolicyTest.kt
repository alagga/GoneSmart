package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeFolderAlphaLeasePolicyTest {
    @Test fun initialNativeOpacityIsRetained() {
        assertEquals(1f, NativeFolderAlphaLeasePolicy.original(1f, null), 0f)
        assertEquals(.75f, NativeFolderAlphaLeasePolicy.original(.75f, null), 0f)
    }

    @Test fun reMaskMustNotPromoteOurHiddenZeroToOriginal() {
        val originallyVisible = NativeFolderAlphaLeasePolicy.original(1f, null)
        assertEquals(
            originallyVisible,
            NativeFolderAlphaLeasePolicy.original(0f, originallyVisible),
            0f
        )
        assertEquals(1f, NativeFolderAlphaLeasePolicy.original(0f, null), 0f)
    }

    @Test fun corruptedValuesCannotKeepNativeRowsInvisible() {
        assertEquals(1f, NativeFolderAlphaLeasePolicy.original(
            Float.NaN, 0f
        ), 0f)
        assertEquals(.8f, NativeFolderAlphaLeasePolicy.original(
            0f, .8f
        ), 0f)
    }
}
