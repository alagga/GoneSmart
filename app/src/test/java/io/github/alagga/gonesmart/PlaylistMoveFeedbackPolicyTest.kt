package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMoveFeedbackPolicyTest {
    @Test fun singleOrMultipleMoveSuccessNeedsNoRedundantPopup() {
        // The completion callback is shared across every batch size.
        assertFalse(PlaylistMoveFeedbackPolicy.shouldShowToast(true))
    }

    @Test fun actualMoveFailureRemainsVisible() {
        assertTrue(PlaylistMoveFeedbackPolicy.shouldShowToast(false))
    }
}
