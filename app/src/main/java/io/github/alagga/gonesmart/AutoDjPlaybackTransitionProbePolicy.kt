package io.github.alagga.gonesmart

/**
 * Diagnostic-only classification for a verified native queue-tail CURRENT
 * transition. Android's AudioManager reports output activity, not decoder
 * validity; a negative sample is a suspicion, never playback authority.
 */
internal object AutoDjPlaybackTransitionProbePolicy {
    fun status(currentStillPlaying: Boolean, audioActive: Boolean?): String =
        when {
            !currentStillPlaying -> "current-changed-or-unavailable"
            audioActive == true -> "audio-active"
            audioActive == false -> "audio-inactive-suspected"
            else -> "audio-state-unavailable"
        }

    fun shouldRecheck(currentStillPlaying: Boolean, audioActive: Boolean?): Boolean =
        currentStillPlaying && audioActive == false
}
