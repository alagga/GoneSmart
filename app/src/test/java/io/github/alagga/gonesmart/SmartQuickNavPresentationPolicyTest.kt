package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartQuickNavPresentationPolicyTest {
    @Test fun pendingRowsDoNotHideAnAlreadyPreparedHeader() {
        assertTrue(SmartQuickNavPresentationPolicy.chromeReady(true, false))
        assertTrue(SmartQuickNavPresentationPolicy.chromeReady(true, true))
    }
    @Test fun initialLoadingOnlyDoesNotExposeUnpreparedContent() {
        assertTrue(SmartQuickNavPresentationPolicy.chromeReady(false, true))
        assertFalse(SmartQuickNavPresentationPolicy.chromeReady(false, false))
    }
}
