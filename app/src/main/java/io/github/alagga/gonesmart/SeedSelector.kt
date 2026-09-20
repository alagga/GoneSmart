package io.github.alagga.gonesmart

enum class SeedType {
    CURRENT,
    HISTORY,
    UPCOMING,
    ANCHOR,
    GENERATED
}


data class RecommendationSeed(
    val track: TrackInfo,
    val type: SeedType,
    val recency: Int,
    val weightMultiplier: Double = 1.0
)


class SeedSelector(
    private val historyLimit: Int = 3,
    private val upcomingLimit: Int = 2,
    private val maxTotalSeeds: Int = 5
) {

    fun select(
        session: QueueSessionSnapshot
    ): List<RecommendationSeed> {

        val seeds =
            mutableListOf<RecommendationSeed>()

        val userAnchorIds =
            session
                .userAnchorTracks
                .map {
                    it.id
                }
                .toSet()

        val current =
            session.currentItem

        /*
         * A user-selected current track remains the strongest
         * signal. A GoneSmart-generated current track does NOT
         * replace the session anchors as the main direction.
         */
        if (
            current != null &&
            current.track.id in
            userAnchorIds
        ) {

            seeds +=
                RecommendationSeed(
                    track = current.track,
                    type = SeedType.CURRENT,
                    recency = 0,
                    weightMultiplier = 1.0
                )
        }

        session
            .pastItems
            .asSequence()
            .filter {
                it.track.id in
                    userAnchorIds
            }
            .sortedByDescending {
                it.queuePosition
            }
            .take(
                historyLimit
            )
            .forEachIndexed { index, item ->

                addDistinct(
                    seeds = seeds,
                    seed = RecommendationSeed(
                        track = item.track,
                        type = SeedType.HISTORY,
                        recency = index + 1,
                        weightMultiplier = 1.0
                    )
                )
            }

        if (
            seeds.size <
            maxTotalSeeds
        ) {

            session
                .manualUpcomingItems
                .asSequence()
                .sortedBy {
                    it.queuePosition
                }
                .take(
                    minOf(
                        upcomingLimit,
                        maxTotalSeeds -
                            seeds.size
                    )
                )
                .forEachIndexed { index, item ->

                    addDistinct(
                        seeds = seeds,
                        seed = RecommendationSeed(
                            track = item.track,
                            type = SeedType.UPCOMING,
                            recency = index + 1,
                            weightMultiplier = 1.0
                        )
                    )
                }
        }

        /*
         * Persistent session anchors fill any remaining slots.
         * This is what prevents a two-track Rock queue from
         * drifting into Hip-Hop/EDM just because GoneSmart's
         * first generated track became CURRENT.
         */
        session
            .userAnchorTracks
            .asReversed()
            .forEachIndexed { index, track ->

                if (
                    seeds.size >=
                    maxTotalSeeds
                ) {

                    return@forEachIndexed
                }

                addDistinct(
                    seeds = seeds,
                    seed = RecommendationSeed(
                        track = track,
                        type = SeedType.ANCHOR,
                        recency = index + 1,
                        weightMultiplier = 1.0
                    )
                )
            }

        /*
         * Generated tracks may nudge a later low-water refill,
         * but only weakly and only after the user anchors have
         * been represented.
         */
        if (
            seeds.size <
            maxTotalSeeds
        ) {

            val generatedCandidates =
                buildList {

                    if (
                        current != null &&
                        current.track.id !in
                        userAnchorIds
                    ) {

                        add(
                            current.track
                        )
                    }

                    addAll(
                        session
                            .recentGeneratedTracks
                            .asReversed()
                    )
                }

            generatedCandidates
                .forEachIndexed { index, track ->

                    if (
                        seeds.size >=
                        maxTotalSeeds
                    ) {

                        return@forEachIndexed
                    }

                    addDistinct(
                        seeds = seeds,
                        seed = RecommendationSeed(
                            track = track,
                            type = SeedType.GENERATED,
                            recency = index + 1,
                            weightMultiplier = 0.30
                        )
                    )
                }
        }

        return seeds
            .take(
                maxTotalSeeds
            )
    }

    private fun addDistinct(
        seeds: MutableList<RecommendationSeed>,
        seed: RecommendationSeed
    ) {

        if (
            seeds.none {
                it.track.id ==
                    seed.track.id
            }
        ) {

            seeds +=
                seed
        }
    }
}
