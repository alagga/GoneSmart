package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class GmmpCompatibilityPolicyTest {
    @Test
    fun classifiesMissingTestedAndUntestedVersions() {
        assertEquals(
            GmmpCompatibilityPolicy.State.UNKNOWN,
            GmmpCompatibilityPolicy.state(null)
        )
        assertEquals(
            GmmpCompatibilityPolicy.State.TESTED,
            GmmpCompatibilityPolicy.state("4.2.1")
        )
        assertEquals(
            GmmpCompatibilityPolicy.State.UNTESTED,
            GmmpCompatibilityPolicy.state("4.2.2")
        )
    }

    @Test
    fun legacyOverloadStillSupportsExplicitTargets() {
        assertEquals(
            GmmpCompatibilityPolicy.State.TESTED,
            GmmpCompatibilityPolicy.state("4.2.0", "4.2.0")
        )
    }
}
