package io.github.alagga.gonesmart

/**
 * Plans a bounded native MusicService playback source before GMMP materializes
 * queue rows. The target must occur exactly once; ambiguity always fails closed.
 */
internal object TrackMixNativePlayWindowPolicy {
    data class Plan(
        val startIndex: Int,
        val endExclusive: Int
    )

    fun plan(
        trackIds: List<Long>,
        targetTrackId: Long,
        initialSize: Int
    ): Plan? {
        if (trackIds.isEmpty() || initialSize <= 0 || targetTrackId <= 0L) return null
        val matches = trackIds.indices.filter { trackIds[it] == targetTrackId }
        if (matches.size != 1) return null
        val start = matches.single()
        return Plan(
            startIndex = start,
            endExclusive = minOf(trackIds.size, start + initialSize)
        )
    }
}
