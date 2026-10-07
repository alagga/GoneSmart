package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpUpdateWarningPolicyTest {
    @Test
    fun missingVersionDoesNotShowWarning() {
        assertFalse(GmmpUpdateWarningPolicy.shouldShow(null, emptySet()))
        assertFalse(GmmpUpdateWarningPolicy.shouldShow("   ", emptySet()))
    }

    @Test
    fun installedVersionShowsUntilThatVersionIsAcknowledged() {
        assertTrue(GmmpUpdateWarningPolicy.shouldShow("4.2.1", emptySet()))
        assertFalse(GmmpUpdateWarningPolicy.shouldShow("4.2.1", setOf("4.2.1")))
    }

    @Test
    fun changingGmmpVersionShowsWarningAgain() {
        val acknowledged = GmmpUpdateWarningPolicy.withAcknowledged("4.2.1", emptySet())
        assertTrue(GmmpUpdateWarningPolicy.shouldShow("4.2.2", acknowledged))
    }

    @Test
    fun previouslyAcknowledgedVersionStaysAcknowledgedAfterOtherVersions() {
        val acknowledged =
            GmmpUpdateWarningPolicy.withAcknowledged(
                "4.2.2",
                GmmpUpdateWarningPolicy.withAcknowledged("4.2.1", emptySet())
            )
        assertFalse(GmmpUpdateWarningPolicy.shouldShow("4.2.1", acknowledged))
        assertFalse(GmmpUpdateWarningPolicy.shouldShow("4.2.2", acknowledged))
    }
}
