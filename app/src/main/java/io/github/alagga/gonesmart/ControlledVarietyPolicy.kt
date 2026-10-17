package io.github.alagga.gonesmart

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

internal data class ControlledVarietyResult<T>(
    val items: List<T>,
    val bucketCount: Int,
    val shuffledBuckets: Int,
    val largestBucket: Int
)

/**
 * Adds bounded variety only among candidates whose final ranking scores are
 * already close. This never adds random noise to the score itself, so a clear
 * quality gap remains a hard ordering boundary.
 */
internal object ControlledVarietyPolicy {
    fun <T> reorder(
        items: List<T>,
        mode: RecommendationVariety,
        scoreOf: (T) -> Double,
        random: Random = Random.Default
    ): ControlledVarietyResult<T> {
        if (items.size < 2 || mode == RecommendationVariety.OFF) {
            return ControlledVarietyResult(
                items = items,
                bucketCount = if (items.isEmpty()) 0 else items.size,
                shuffledBuckets = 0,
                largestBucket = if (items.isEmpty()) 0 else 1
            )
        }

        val (absoluteWindow, relativeWindow) = when (mode) {
            RecommendationVariety.OFF -> 0.0 to 0.0
            RecommendationVariety.BALANCED -> 0.020 to 0.030
            RecommendationVariety.HIGH -> 0.040 to 0.060
        }

        val output = ArrayList<T>(items.size)
        var index = 0
        var bucketCount = 0
        var shuffledBuckets = 0
        var largestBucket = 1

        while (index < items.size) {
            val leaderScore = scoreOf(items[index])
            val tolerance = max(absoluteWindow, abs(leaderScore) * relativeWindow)
            var endExclusive = index + 1
            while (endExclusive < items.size) {
                val candidateScore = scoreOf(items[endExclusive])
                if (leaderScore - candidateScore > tolerance + 0.0000001) break
                endExclusive += 1
            }

            val bucket = items.subList(index, endExclusive)
            bucketCount += 1
            largestBucket = max(largestBucket, bucket.size)
            if (bucket.size > 1) {
                output += bucket.shuffled(random)
                shuffledBuckets += 1
            } else {
                output += bucket
            }
            index = endExclusive
        }

        return ControlledVarietyResult(
            items = output,
            bucketCount = bucketCount,
            shuffledBuckets = shuffledBuckets,
            largestBucket = largestBucket
        )
    }
}
