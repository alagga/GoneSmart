package io.github.alagga.gonesmart

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledVarietyPolicyTest {
    private data class Candidate(val id: Int, val score: Double)

    @Test
    fun offPreservesDeterministicOrder() {
        val input = listOf(Candidate(1, 0.8), Candidate(2, 0.8), Candidate(3, 0.7))
        val result = ControlledVarietyPolicy.reorder(
            items = input,
            mode = RecommendationVariety.OFF,
            scoreOf = Candidate::score,
            random = Random(1)
        )
        assertEquals(input, result.items)
        assertEquals(0, result.shuffledBuckets)
    }

    @Test
    fun balancedNeverCrossesClearScoreCliff() {
        val input = listOf(
            Candidate(1, 0.80),
            Candidate(2, 0.79),
            Candidate(3, 0.78),
            Candidate(4, 0.60),
            Candidate(5, 0.59)
        )
        val result = ControlledVarietyPolicy.reorder(
            items = input,
            mode = RecommendationVariety.BALANCED,
            scoreOf = Candidate::score,
            random = Random(3)
        )
        assertEquals(setOf(1, 2, 3), result.items.take(3).map { it.id }.toSet())
        assertEquals(setOf(4, 5), result.items.drop(3).map { it.id }.toSet())
        assertTrue(result.shuffledBuckets >= 1)
    }

    @Test
    fun highUsesWiderNearTieBucketsThanBalanced() {
        val input = listOf(
            Candidate(1, 0.68),
            Candidate(2, 0.66),
            Candidate(3, 0.64),
            Candidate(4, 0.50)
        )
        val balanced = ControlledVarietyPolicy.reorder(
            input,
            RecommendationVariety.BALANCED,
            Candidate::score,
            Random(7)
        )
        val high = ControlledVarietyPolicy.reorder(
            input,
            RecommendationVariety.HIGH,
            Candidate::score,
            Random(7)
        )
        assertTrue(high.largestBucket > balanced.largestBucket)
        assertEquals(input.map { it.id }.toSet(), high.items.map { it.id }.toSet())
    }
}
