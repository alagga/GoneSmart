package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistMoveFeedbackPolicyTest {
    @Test fun singleAndMultipleMoveSuccessGetsOneConfirmation() {
        assertEquals(
            PlaylistMoveFeedbackPolicy.Feedback.SUCCESS,
            PlaylistMoveFeedbackPolicy.feedbackFor(true)
        )
    }

    @Test fun actualMoveFailureRemainsVisibleAsError() {
        assertEquals(
            PlaylistMoveFeedbackPolicy.Feedback.ERROR,
            PlaylistMoveFeedbackPolicy.feedbackFor(false)
        )
    }
}
