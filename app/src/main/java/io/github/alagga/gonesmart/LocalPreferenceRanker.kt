package io.github.alagga.gonesmart

import android.util.Log
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

data class RatingThresholdProfile(
    val minimumRatingStars: Double,
    val smartMedianRatingStars: Double?,
    val effectiveRatingStars: Double,
    val smartSampleCount: Int
) {
    val active: Boolean
        get() = effectiveRatingStars > 0.0
}


class LocalPreferenceRanker(
    private val trackFamilyKeyBuilder:
        TrackFamilyKeyBuilder
) {

    companion object {

        private const val TAG =
            "GoneSmart"

        private const val MAX_RESULTS =
            30

        private const val MAX_RATING_BONUS =
            0.08

        private const val LIVE_PENALTY_MULTIPLIER =
            0.70

        private const val MAX_ERA_BONUS =
            0.12

        private const val ERA_WINDOW_YEARS =
            5.0

        private const val MAX_RECENT_ADDED_BONUS =
            0.08

        private const val RECENT_CONTEXT_DAYS =
            365.0

        private const val MILLIS_PER_DAY =
            86_400_000.0
    }

    fun rank(
        matches: List<LocalRecommendationMatch>,
        library: List<GmmpLibraryTrack>,
        seeds: List<RecommendationSeed>,
        duplicateContextTracks: List<TrackInfo>,
        options: GoneSmartOptions
    ): List<LocalRecommendationMatch> {

        if (
            matches.isEmpty()
        ) {

            return emptyList()
        }

        val libraryById =
            library
                .associateBy {
                    it.track.id
                }

        val ratingThresholdProfile =
            ratingThresholdProfile(
                seeds = seeds,
                library = library,
                options = options
            )

        val eraProfile =
            if (
                options.matchQueueEra
            ) {

                buildEraProfile(
                    seeds = seeds,
                    libraryById = libraryById
                )

            } else {

                null
            }

        val recentAddedProfile =
            if (
                options.favorRecentlyAddedTracks
            ) {

                buildRecentAddedProfile(
                    seeds = seeds,
                    libraryById = libraryById
                )

            } else {

                null
            }

        val hasLiveSeed =
            seeds
                .any {
                    trackFamilyKeyBuilder
                        .isLiveTrack(
                            it.track
                        )
                }

        val duplicateContextFamilyKeys =
            if (
                options.preventBaseVersionDuplicates
            ) {

                duplicateContextTracks
                    .flatMap {
                        trackFamilyKeyBuilder
                            .familyKeys(
                                it
                            )
                    }
                    .toSet()

            } else {

                emptySet()
            }

        Log.i(
            TAG,
            "PREFERENCE PROFILE | " +
                "minimumRating=${format(ratingThresholdProfile.minimumRatingStars)} | " +
                "smartMedian=${ratingThresholdProfile.smartMedianRatingStars?.let { format(it) } ?: "off"} | " +
                "effectiveRating=${format(ratingThresholdProfile.effectiveRatingStars)} | " +
                "smartRatingSamples=${ratingThresholdProfile.smartSampleCount} | " +
                "eraCenter=${eraProfile?.centerYear?.let { format(it) } ?: "none"} | " +
                "eraStrength=${eraProfile?.strength?.let { format(it) } ?: "none"} | " +
                "recentAddedStrength=${recentAddedProfile?.strength?.let { format(it) } ?: "none"} | " +
                "liveSeed=$hasLiveSeed"
        )

        var ratingThresholdExcluded =
            0

        var halfStarExcluded =
            0

        var queueDuplicatesRejected =
            0

        val adjusted =
            matches
                .mapNotNull { match ->

                    val candidateRatingStars =
                        ratingStarsForFilter(
                            match.libraryTrack
                        )

                    if (
                        ratingThresholdProfile.active &&
                        candidateRatingStars + 0.000001 <
                        ratingThresholdProfile.effectiveRatingStars
                    ) {

                        ratingThresholdExcluded +=
                            1

                        return@mapNotNull null
                    }

                    if (
                        options.excludeHalfStarTracks &&
                        match.libraryTrack.ratingRaw == 1
                    ) {

                        halfStarExcluded +=
                            1

                        return@mapNotNull null
                    }

                    if (
                        options.preventBaseVersionDuplicates
                    ) {

                        val candidateFamilyKeys =
                            trackFamilyKeyBuilder
                                .familyKeys(
                                    match.track
                                )

                        if (
                            candidateFamilyKeys.any {
                                it in duplicateContextFamilyKeys
                            }
                        ) {

                            queueDuplicatesRejected +=
                                1

                            Log.i(
                                TAG,
                                "DUPLICATE FAMILY REJECT | " +
                                    "trackId=${match.track.id} | " +
                                    "artist=${match.track.artist} | " +
                                    "title=${match.track.title}"
                            )

                            return@mapNotNull null
                        }
                    }

                    val ratingMultiplier =
                        ratingMultiplier(
                            libraryTrack = match.libraryTrack,
                            options = options
                        )

                    val liveMultiplier =
                        if (
                            options.preferStudioVersionsOverLive &&
                            !hasLiveSeed &&
                            trackFamilyKeyBuilder
                                .isLiveTrack(
                                    match.track
                                )
                        ) {

                            LIVE_PENALTY_MULTIPLIER

                        } else {

                            1.0
                        }

                    val eraMultiplier =
                        eraMultiplier(
                            libraryTrack = match.libraryTrack,
                            profile = eraProfile
                        )

                    val recentAddedMultiplier =
                        recentAddedMultiplier(
                            libraryTrack = match.libraryTrack,
                            profile = recentAddedProfile
                        )

                    val preferenceMultiplier =
                        ratingMultiplier *
                            liveMultiplier *
                            eraMultiplier *
                            recentAddedMultiplier

                    match.copy(
                        rankingScore =
                            match.finalScore *
                                preferenceMultiplier,
                        preferenceMultiplier =
                            preferenceMultiplier,
                        ratingMultiplier =
                            ratingMultiplier,
                        eraMultiplier =
                            eraMultiplier,
                        recentAddedMultiplier =
                            recentAddedMultiplier,
                        liveMultiplier =
                            liveMultiplier
                    )
                }

        val oneLocalTrackPerRecommendation =
            adjusted
                .groupBy {
                    it.recommendation
                }
                .values
                .mapNotNull {
                    chooseBest(
                        it
                    )
                }

        val sorted =
            oneLocalTrackPerRecommendation
                .sortedWith(
                    matchComparator()
                )

        val result =
            mutableListOf<LocalRecommendationMatch>()

        val occupiedTrackIds =
            mutableSetOf<Long>()

        val occupiedFamilyKeys =
            mutableSetOf<String>()

        var batchFamilyDuplicatesCollapsed =
            0

        for (
            match in
            sorted
        ) {

            if (
                result.size >=
                MAX_RESULTS
            ) {

                break
            }

            if (
                match.track.id in
                occupiedTrackIds
            ) {

                continue
            }

            val familyKeys =
                if (
                    options.preventBaseVersionDuplicates
                ) {

                    trackFamilyKeyBuilder
                        .familyKeys(
                            match.track
                        )

                } else {

                    emptySet()
                }

            if (
                familyKeys.isNotEmpty() &&
                familyKeys.any {
                    it in occupiedFamilyKeys
                }
            ) {

                batchFamilyDuplicatesCollapsed +=
                    1

                continue
            }

            result +=
                match

            occupiedTrackIds +=
                match.track.id

            occupiedFamilyKeys +=
                familyKeys
        }

        Log.i(
            TAG,
            "PREFERENCE FILTER | " +
                "ratingThresholdExcluded=$ratingThresholdExcluded | " +
                "halfStarExcluded=$halfStarExcluded | " +
                "queueDuplicatesRejected=$queueDuplicatesRejected | " +
                "sameRecommendationCollapsed=" +
                (adjusted.size -
                    oneLocalTrackPerRecommendation.size) +
                " | batchFamiliesCollapsed=$batchFamilyDuplicatesCollapsed | " +
                "remaining=${result.size}"
        )

        return result
            .take(
                MAX_RESULTS
            )
    }

    fun ratingThresholdProfile(
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        options: GoneSmartOptions
    ): RatingThresholdProfile {

        val minimumRating =
            options.minimumRatingStars
                .coerceIn(
                    0.0,
                    5.0
                )

        if (
            !options.smartRatingEnabled
        ) {

            return RatingThresholdProfile(
                minimumRatingStars = minimumRating,
                smartMedianRatingStars = null,
                effectiveRatingStars = minimumRating,
                smartSampleCount = 0
            )
        }

        val libraryById =
            library
                .associateBy {
                    it.track.id
                }

        val ratings =
            seeds
                .asSequence()
                .distinctBy {
                    it.track.id
                }
                .mapNotNull { seed ->
                    libraryById[seed.track.id]
                }
                .map { libraryTrack ->
                    ratingStarsForFilter(
                        libraryTrack
                    )
                }
                .sorted()
                .toList()

        val median =
            median(
                ratings
            )

        val effectiveRating =
            max(
                minimumRating,
                median ?: 0.0
            )

        return RatingThresholdProfile(
            minimumRatingStars = minimumRating,
            smartMedianRatingStars = median,
            effectiveRatingStars = effectiveRating,
            smartSampleCount = ratings.size
        )
    }

    private fun median(
        values: List<Double>
    ): Double? {

        if (
            values.isEmpty()
        ) {

            return null
        }

        val middle =
            values.size / 2

        return if (
            values.size % 2 == 1
        ) {

            values[middle]

        } else {

            (
                values[middle - 1] +
                    values[middle]
                ) / 2.0
        }
    }

    private fun ratingStarsForFilter(
        libraryTrack: GmmpLibraryTrack
    ): Double {

        return (
            libraryTrack.ratingRaw
                .coerceAtLeast(0)
                .toDouble() /
                2.0
            )
            .coerceIn(
                0.0,
                5.0
            )
    }

    private fun chooseBest(
        matches: List<LocalRecommendationMatch>
    ): LocalRecommendationMatch? {

        return matches
            .sortedWith(
                matchComparator()
            )
            .firstOrNull()
    }

    private fun matchComparator():
            Comparator<LocalRecommendationMatch> {

        return compareByDescending<LocalRecommendationMatch> {
            it.rankingScore
        }
            .thenByDescending {
                it.matchScore
            }
            .thenByDescending {
                it.ratingStars
                    ?: -1.0
            }
            .thenByDescending {
                it.versionScore
            }
            .thenBy {
                it.track.id
            }
    }

    private fun ratingMultiplier(
        libraryTrack: GmmpLibraryTrack,
        options: GoneSmartOptions
    ): Double {

        if (
            !options.preferHigherRatedMatches
        ) {

            return 1.0
        }

        val stars =
            libraryTrack.ratingStars
                ?: return 1.0

        return 1.0 +
            MAX_RATING_BONUS *
            (stars / 5.0)
                .coerceIn(
                    0.0,
                    1.0
                )
    }

    private fun eraMultiplier(
        libraryTrack: GmmpLibraryTrack,
        profile: EraProfile?
    ): Double {

        if (
            profile == null
        ) {

            return 1.0
        }

        val year =
            libraryTrack.year

        val currentYear =
            Calendar
                .getInstance()
                .get(
                    Calendar.YEAR
                )

        if (
            year < 1900 ||
            year >
            currentYear + 1
        ) {

            return 1.0
        }

        val distance =
            abs(
                year.toDouble() -
                    profile.centerYear
            )

        val closeness =
            (
                1.0 -
                    distance /
                    ERA_WINDOW_YEARS
                )
                .coerceIn(
                    0.0,
                    1.0
                )

        return 1.0 +
            MAX_ERA_BONUS *
            profile.strength *
            closeness
    }

    private fun recentAddedMultiplier(
        libraryTrack: GmmpLibraryTrack,
        profile: RecentAddedProfile?
    ): Double {

        if (
            profile == null
        ) {

            return 1.0
        }

        val dateAdded =
            libraryTrack.dateAddedEpochMs
                ?: return 1.0

        val ageDays =
            (
                System.currentTimeMillis() -
                    dateAdded
                ) /
                MILLIS_PER_DAY

        val candidateRecency =
            when {

                ageDays <= 90.0 ->
                    1.0

                ageDays <= 180.0 ->
                    0.80

                ageDays <= 365.0 ->
                    0.50

                ageDays <= 730.0 ->
                    0.20

                else ->
                    0.0
            }

        return 1.0 +
            MAX_RECENT_ADDED_BONUS *
            profile.strength *
            candidateRecency
    }

    private fun buildEraProfile(
        seeds: List<RecommendationSeed>,
        libraryById: Map<Long, GmmpLibraryTrack>
    ): EraProfile? {

        val currentYear =
            Calendar
                .getInstance()
                .get(
                    Calendar.YEAR
                )

        val samples =
            seeds
                .mapNotNull { seed ->

                    val libraryTrack =
                        libraryById[
                            seed.track.id
                        ]
                            ?: return@mapNotNull null

                    val year =
                        libraryTrack.year

                    if (
                        year < 1900 ||
                        year >
                        currentYear + 1
                    ) {

                        return@mapNotNull null
                    }

                    WeightedYear(
                        year = year.toDouble(),
                        weight = seedSignalWeight(
                            seed
                        )
                    )
                }

        if (
            samples.size < 2
        ) {

            return null
        }

        val totalWeight =
            samples
                .sumOf {
                    it.weight
                }

        if (
            totalWeight <= 0.0
        ) {

            return null
        }

        val centerYear =
            samples
                .sumOf {
                    it.year *
                        it.weight
                } /
                totalWeight

        val meanAbsoluteDeviation =
            samples
                .sumOf {
                    abs(
                        it.year -
                            centerYear
                    ) *
                        it.weight
                } /
                totalWeight

        val strength =
            (
                1.0 -
                    meanAbsoluteDeviation /
                    4.0
                )
                .coerceIn(
                    0.0,
                    1.0
                )

        if (
            strength < 0.30
        ) {

            return null
        }

        return EraProfile(
            centerYear = centerYear,
            strength = strength
        )
    }

    private fun buildRecentAddedProfile(
        seeds: List<RecommendationSeed>,
        libraryById: Map<Long, GmmpLibraryTrack>
    ): RecentAddedProfile? {

        val now =
            System.currentTimeMillis()

        val samples =
            seeds
                .mapNotNull { seed ->

                    val dateAdded =
                        libraryById[
                            seed.track.id
                        ]
                            ?.dateAddedEpochMs
                            ?: return@mapNotNull null

                    val ageDays =
                        (
                            now -
                                dateAdded
                            ) /
                            MILLIS_PER_DAY

                    WeightedAge(
                        ageDays = ageDays,
                        weight = seedSignalWeight(
                            seed
                        )
                    )
                }

        if (
            samples.size < 2
        ) {

            return null
        }

        val totalWeight =
            samples
                .sumOf {
                    it.weight
                }

        if (
            totalWeight <= 0.0
        ) {

            return null
        }

        val recentWeight =
            samples
                .filter {
                    it.ageDays <=
                        RECENT_CONTEXT_DAYS
                }
                .sumOf {
                    it.weight
                }

        val recentRatio =
            recentWeight /
                totalWeight

        if (
            recentRatio < 0.60
        ) {

            return null
        }

        return RecentAddedProfile(
            strength =
                recentRatio
                    .coerceIn(
                        0.0,
                        1.0
                    )
        )
    }

    private fun seedSignalWeight(
        seed: RecommendationSeed
    ): Double {

        val baseWeight =
            when (
                seed.type
            ) {

                SeedType.CURRENT ->
                    1.0

                SeedType.HISTORY ->
                    when (
                        seed.recency
                    ) {

                        1 -> 0.85
                        2 -> 0.72
                        3 -> 0.60
                        else -> 0.50
                    }

                SeedType.UPCOMING ->
                    when (
                        seed.recency
                    ) {

                        1 -> 0.75
                        else -> 0.65
                    }

                SeedType.ANCHOR ->
                    0.85

                SeedType.GENERATED ->
                    1.0
            }

        return (
            baseWeight *
                seed.weightMultiplier
            )
            .coerceIn(
                0.0,
                1.0
            )
    }

    private fun format(
        value: Double
    ): String {

        return String.format(
            java.util.Locale.US,
            "%.3f",
            value
        )
    }

    private data class EraProfile(
        val centerYear: Double,
        val strength: Double
    )

    private data class RecentAddedProfile(
        val strength: Double
    )

    private data class WeightedYear(
        val year: Double,
        val weight: Double
    )

    private data class WeightedAge(
        val ageDays: Double,
        val weight: Double
    )
}
