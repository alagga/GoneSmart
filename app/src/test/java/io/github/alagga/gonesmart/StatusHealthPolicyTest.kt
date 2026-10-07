package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusHealthPolicyTest {
    @Test
    fun gmmpUsesRedAmberGreenLifecycle() {
        assertEquals(StatusHealthPolicy.Tone.RED, StatusHealthPolicy.gmmp(false, false))
        assertEquals(StatusHealthPolicy.Tone.AMBER, StatusHealthPolicy.gmmp(true, false))
        assertEquals(StatusHealthPolicy.Tone.GREEN, StatusHealthPolicy.gmmp(true, true))
    }

    @Test
    fun frameworkDistinguishesMissingUnsupportedAndSupported() {
        assertEquals(StatusHealthPolicy.Tone.RED, StatusHealthPolicy.framework(false, null))
        assertEquals(StatusHealthPolicy.Tone.AMBER, StatusHealthPolicy.framework(true, 100))
        assertEquals(StatusHealthPolicy.Tone.GREEN, StatusHealthPolicy.framework(true, 101))
        assertEquals(StatusHealthPolicy.Tone.GREEN, StatusHealthPolicy.framework(true, 102))
        assertEquals(StatusHealthPolicy.Tone.AMBER, StatusHealthPolicy.framework(true, 103))
    }

    @Test
    fun runtimeAndCompatibilityUseIndependentHealth() {
        assertEquals(
            StatusHealthPolicy.Tone.GREEN,
            StatusHealthPolicy.runtime(true, true, true, true, GoneSmartRuntimeContract.MODE_SMART)
        )
        assertEquals(
            StatusHealthPolicy.Tone.GREEN,
            StatusHealthPolicy.runtime(true, true, true, true, GoneSmartRuntimeContract.MODE_NONE)
        )
        assertEquals(
            StatusHealthPolicy.Tone.AMBER,
            StatusHealthPolicy.runtime(true, true, true, true, GoneSmartRuntimeContract.MODE_FALLBACK)
        )
        assertEquals(
            StatusHealthPolicy.Tone.RED,
            StatusHealthPolicy.runtime(true, true, true, true, GoneSmartRuntimeContract.MODE_STOPPED)
        )
        assertEquals(
            StatusHealthPolicy.Tone.GREEN,
            StatusHealthPolicy.compatibility(GmmpCompatibilityPolicy.State.TESTED)
        )
        assertEquals(
            StatusHealthPolicy.Tone.AMBER,
            StatusHealthPolicy.compatibility(GmmpCompatibilityPolicy.State.UNTESTED)
        )
        assertEquals(
            StatusHealthPolicy.Tone.RED,
            StatusHealthPolicy.compatibility(GmmpCompatibilityPolicy.State.UNKNOWN)
        )
    }

    @Test
    fun overallUsesMostSevereChildTone() {
        assertEquals(
            StatusHealthPolicy.Tone.GREEN,
            StatusHealthPolicy.overall(
                StatusHealthPolicy.Tone.GREEN,
                StatusHealthPolicy.Tone.GREEN
            )
        )
        assertEquals(
            StatusHealthPolicy.Tone.AMBER,
            StatusHealthPolicy.overall(
                StatusHealthPolicy.Tone.GREEN,
                StatusHealthPolicy.Tone.AMBER,
                StatusHealthPolicy.Tone.GREEN
            )
        )
        assertEquals(
            StatusHealthPolicy.Tone.RED,
            StatusHealthPolicy.overall(
                StatusHealthPolicy.Tone.AMBER,
                StatusHealthPolicy.Tone.RED,
                StatusHealthPolicy.Tone.GREEN
            )
        )
    }
}
