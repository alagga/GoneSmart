package io.github.alagga.gonesmart

internal object TrackMixNativeFallbackPolicy {
    fun selectNativeTrackIds(
        rawTrackIds: List<Long>,
        selectedTrackId: Long,
        requested: Int
    ): List<Long> {
        if (requested <= 0) return emptyList()
        return rawTrackIds.asSequence()
            .filter { it > 0L && it != selectedTrackId }
            .distinct()
            .take(requested)
            .toList()
    }

    fun expectedQueueIds(
        selectedTrackId: Long,
        fallbackTrackIds: List<Long>
    ): List<Long> = listOf(selectedTrackId) + fallbackTrackIds

    fun preservesPreloadedNext(
        primedTrackId: Long?,
        fallbackTrackIds: List<Long>
    ): Boolean =
        fallbackTrackIds.isEmpty() || primedTrackId == fallbackTrackIds.first()
}
