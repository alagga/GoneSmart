package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQuickNavForwardPolicyTest {
    @Test fun hiddenRootAndNestedEntryAreForwardAdditions() {
        assertTrue(NativeQuickNavDiff.between(emptyList(), listOf("root", "a")).isForward)
        assertTrue(NativeQuickNavDiff.between(listOf("root", "a"), listOf("root", "a", "b")).isForward)
    }
    @Test fun backSiblingAndUnchangedPathsDoNotReplayAdds() {
        assertFalse(NativeQuickNavDiff.between(listOf("root", "a", "b"), listOf("root", "a")).isForward)
        assertFalse(NativeQuickNavDiff.between(listOf("root", "a"), listOf("root", "b")).isForward)
        assertFalse(NativeQuickNavDiff.between(listOf("root", "a"), listOf("root", "a")).isForward)
    }
}
