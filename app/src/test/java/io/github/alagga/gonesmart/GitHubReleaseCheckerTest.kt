package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubReleaseCheckerTest {
    @Test fun recognizesNewerPublishedRelease() {
        assertEquals(
            -1,
            GitHubReleaseChecker.compareVersions("0.3.2", "0.4.0")
        )
        assertEquals(
            -1,
            GitHubReleaseChecker.compareVersions("0.3.2", "v0.3.3")
        )
    }

    @Test fun stableTagAndBuildMetadataAreEquivalent() {
        assertEquals(
            0,
            GitHubReleaseChecker.compareVersions("v0.3.2", "0.3.2+build5")
        )
    }

    @Test fun developmentBuildMustNotSuggestDowngrade() {
        assertEquals(
            1,
            GitHubReleaseChecker.compareVersions("0.4.0-beta1", "0.3.2")
        )
        assertEquals(
            -1,
            GitHubReleaseChecker.compareVersions("0.4.0-beta1", "0.4.0")
        )
    }

    @Test fun unknownTagsDoNotTriggerAnUpdate() {
        assertEquals(
            null,
            GitHubReleaseChecker.compareVersions("testing", "0.4.0")
        )
    }
}
