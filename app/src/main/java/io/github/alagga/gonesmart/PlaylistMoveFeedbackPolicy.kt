package io.github.alagga.gonesmart

/** One result policy for BOTH single and multi playlist moves. */
internal object PlaylistMoveFeedbackPolicy {
    enum class Feedback {
        SUCCESS,
        ERROR
    }

    fun feedbackFor(success: Boolean): Feedback =
        if (success) Feedback.SUCCESS else Feedback.ERROR
}
