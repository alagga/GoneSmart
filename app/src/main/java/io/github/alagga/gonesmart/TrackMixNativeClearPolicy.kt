package io.github.alagga.gonesmart

/**
 * Postcondition for GMMP-owned Track Mix seed isolation.
 *
 * GMMP 4.2.1 may replace the queue_table row (and therefore queue_id) while
 * handling its public CLEAR_QUEUE command. The stable semantic identity is the
 * selected track remaining as the single CURRENT row; queue-entry identity is
 * deliberately not part of this contract.
 */
internal object TrackMixNativeClearPolicy {
    fun isIsolated(
        selectedTrackId: Long,
        queueSize: Int,
        currentTrackId: Long?,
        currentRows: Int
    ): Boolean =
        queueSize == 1 &&
            currentRows == 1 &&
            currentTrackId == selectedTrackId
}
