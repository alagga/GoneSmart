package io.github.alagga.gonesmart

/**
 * An ordinary Auto-DJ refill may finish after GMMP has advanced playback.
 * A new playback session must never receive the old recommendations, while
 * an in-session manual queue append must not invalidate the pending refill.
 */
internal object DeferredAutoDjRefillPolicy {
    fun canResume(
        expectedSessionId: Long,
        observedSessionId: Long,
        originalEntryIds: Set<Long>,
        observedEntryIds: Set<Long>,
        recommendationSessionIsCurrent: Boolean,
        smartDjEnabled: Boolean
    ): Boolean =
        smartDjEnabled && recommendationSessionIsCurrent &&
            expectedSessionId == observedSessionId &&
            originalEntryIds.isNotEmpty() &&
            originalEntryIds.any { it in observedEntryIds }
}
