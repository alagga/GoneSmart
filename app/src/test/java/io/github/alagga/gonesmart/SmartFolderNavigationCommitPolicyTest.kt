package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFolderNavigationCommitPolicyTest {
    @Test fun onlyNewProjectionCommitMayHideTheReadyPage() {
        val p = SmartFolderNavigationCommitPolicy
        assertTrue(p.shouldMaskAtCommit(true, true, false))
        assertTrue(p.shouldMaskAtCommit(true, false, true))
        assertFalse(p.shouldMaskAtCommit(true, false, false))
        assertFalse(p.shouldMaskAtCommit(false, true, true))
    }

    @Test fun scrollResetMustFinishBeforeDrawCanReveal() {
        val p = SmartFolderNavigationCommitPolicy
        assertFalse(p.mayRevealAtDraw(true, false, false, false))
        assertFalse(p.mayRevealAtDraw(true, false, true, true))
        assertFalse(p.mayRevealAtDraw(false, false, true, false))
        assertFalse(p.mayRevealAtDraw(true, true, true, false))
        assertTrue(p.mayRevealAtDraw(true, false, true, false))
    }
}
