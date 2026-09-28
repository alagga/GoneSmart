package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartNativeSubmissionPolicyTest {
    @Test fun firstSnapshotIsAlwaysSubmitted() {
        assertTrue(
            SmartNativeSubmissionPolicy.shouldSubmit(
                false, emptyList(), emptyList()
            )
        )
    }

    @Test fun identicalSnapshotDoesNotRebindNativeRows() {
        val signature = listOf("/a.spl|1|10", "/b.spl|2|20")
        assertFalse(
            SmartNativeSubmissionPolicy.shouldSubmit(
                true, signature, signature.toList()
            )
        )
    }

    @Test fun changedOrderOrFileStateIsSubmitted() {
        assertTrue(
            SmartNativeSubmissionPolicy.shouldSubmit(
                true,
                listOf("/a.spl|1|10", "/b.spl|2|20"),
                listOf("/b.spl|2|20", "/a.spl|1|10")
            )
        )
        assertTrue(
            SmartNativeSubmissionPolicy.shouldSubmit(
                true,
                listOf("/a.spl|1|10"),
                listOf("/a.spl|3|10")
            )
        )
    }
}
