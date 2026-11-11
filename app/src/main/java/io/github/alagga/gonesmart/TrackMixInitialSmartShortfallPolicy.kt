package io.github.alagga.gonesmart

/**
 * A valid partial Track Mix must not immediately be padded with native random
 * songs by the first AUTO_DJ command. This is bound to the original native
 * Current and its generated followers, never to a guessed time interval.
 */
internal object TrackMixInitialSmartShortfallPolicy {
    fun shouldDefer(
        expectedSessionId: Long,
        actualSessionId: Long,
        expectedCurrentEntryId: Long,
        actualCurrentEntryId: Long?,
        expectedGeneratedEntryIds: Set<Long>,
        actualUpcomingEntryIds: Set<Long>,
        receiverMatches: Boolean
    ): Boolean = receiverMatches &&
        expectedSessionId > 0L && expectedSessionId == actualSessionId &&
        expectedCurrentEntryId == actualCurrentEntryId &&
        expectedGeneratedEntryIds.isNotEmpty() &&
        expectedGeneratedEntryIds.all { it in actualUpcomingEntryIds }
}
