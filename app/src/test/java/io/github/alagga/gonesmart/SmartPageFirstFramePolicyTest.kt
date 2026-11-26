package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPageFirstFramePolicyTest {
    @Test fun nativeSmartPageWithOurMaskCanStillCompleteProjection() {
        assertTrue(SmartPageFirstFramePolicy.mayBeFront(0f, true))
        assertTrue(SmartPageFirstFramePolicy.mayBeFront(1f, false))
    }

    @Test fun arbitraryInvisibleOrTransparentFragmentsDoNotCountAsFront() {
        assertFalse(SmartPageFirstFramePolicy.mayBeFront(0f, false))
        assertFalse(SmartPageFirstFramePolicy.mayBeFront(.005f, false))
    }
}
