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

    @Test fun masksTransientNativeRootWhenProjectionDiffers() {
        assertTrue(
            SmartNativeSubmissionPolicy.shouldMaskNativeRootRefresh(
                currentIsRoot = false,
                otherLocations = false,
                groupRootPlaylists = false
            )
        )
        assertTrue(
            SmartNativeSubmissionPolicy.shouldMaskNativeRootRefresh(
                currentIsRoot = true,
                otherLocations = true,
                groupRootPlaylists = true
            )
        )
        assertTrue(
            SmartNativeSubmissionPolicy.shouldMaskNativeRootRefresh(
                currentIsRoot = true,
                otherLocations = false,
                groupRootPlaylists = true
            )
        )
        assertFalse(
            SmartNativeSubmissionPolicy.shouldMaskNativeRootRefresh(
                currentIsRoot = true,
                otherLocations = false,
                groupRootPlaylists = false
            )
        )
    }

    @Test fun suppressesTransientRootOnlyAfterProjectionIsVisible() {
        assertTrue(
            SmartNativeSubmissionPolicy.shouldSuppressNativeRootRefresh(
                projectionPrepared = true,
                nativeContentReady = true,
                currentIsRoot = false,
                otherLocations = false,
                groupRootPlaylists = false
            )
        )
        assertTrue(
            SmartNativeSubmissionPolicy.shouldSuppressNativeRootRefresh(
                projectionPrepared = true,
                nativeContentReady = true,
                currentIsRoot = true,
                otherLocations = false,
                groupRootPlaylists = true
            )
        )
        assertFalse(
            SmartNativeSubmissionPolicy.shouldSuppressNativeRootRefresh(
                projectionPrepared = false,
                nativeContentReady = true,
                currentIsRoot = false,
                otherLocations = false,
                groupRootPlaylists = false
            )
        )
        assertFalse(
            SmartNativeSubmissionPolicy.shouldSuppressNativeRootRefresh(
                projectionPrepared = true,
                nativeContentReady = false,
                currentIsRoot = false,
                otherLocations = false,
                groupRootPlaylists = false
            )
        )
        assertFalse(
            SmartNativeSubmissionPolicy.shouldSuppressNativeRootRefresh(
                projectionPrepared = true,
                nativeContentReady = true,
                currentIsRoot = true,
                otherLocations = false,
                groupRootPlaylists = false
            )
        )
    }

    @Test fun waitsForCommittedNativeProjectionBeforeReveal() {
        val expected = setOf("/folder/a.spl", "/folder/b.spl")
        assertFalse(
            SmartNativeSubmissionPolicy.projectionReady(
                expectedCount = 2,
                adapterCount = 4,
                visiblePaths = listOf("/root/a.spl"),
                expectedPaths = expected
            )
        )
        assertFalse(
            SmartNativeSubmissionPolicy.projectionReady(
                expectedCount = 2,
                adapterCount = 2,
                visiblePaths = listOf("/root/a.spl"),
                expectedPaths = expected
            )
        )
        assertTrue(
            SmartNativeSubmissionPolicy.projectionReady(
                expectedCount = 2,
                adapterCount = 2,
                visiblePaths = listOf("/folder/a.spl"),
                expectedPaths = expected
            )
        )
        assertTrue(
            SmartNativeSubmissionPolicy.projectionReady(
                expectedCount = 0,
                adapterCount = 0,
                visiblePaths = emptyList(),
                expectedPaths = emptySet()
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
