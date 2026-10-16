package io.github.alagga.gonesmart

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

internal object RecommendationScoring {
    /**
     * Independent providers confirming the same logical track are stronger
     * evidence than repeated rows from one provider. Provider/seed duplicate
     * suppression happens in RecommendationAggregator before this multiplier.
     */
    fun corroborationMultiplier(providerCount: Int): Double {
        val additionalProviders = (providerCount - 1).coerceIn(0, 2)
        return 1.0 + (additionalProviders * 0.12)
    }
}

internal object PlaylistAffinityScoring {
    private const val REFERENCE_PLAYLIST_SIZE = 25.0
    private const val BALANCED_MIN_PLAYLIST_WEIGHT = 0.08
    private const val STRONG_MIN_PLAYLIST_WEIGHT = 0.03
    private const val MAX_SCORE = 0.95

    fun playlistWeight(
        playlistSize: Int,
        weighting: PlaylistSizeWeighting = PlaylistSizeWeighting.BALANCED
    ): Double {
        if (playlistSize <= 1) return 0.0
        if (weighting == PlaylistSizeWeighting.OFF) return 1.0

        val denominator = max(REFERENCE_PLAYLIST_SIZE, playlistSize.toDouble())
        val ratio = REFERENCE_PLAYLIST_SIZE / denominator
        return when (weighting) {
            PlaylistSizeWeighting.OFF -> 1.0
            PlaylistSizeWeighting.BALANCED ->
                sqrt(ratio).coerceAtLeast(BALANCED_MIN_PLAYLIST_WEIGHT)
            PlaylistSizeWeighting.STRONG ->
                ratio.pow(0.75).coerceAtLeast(STRONG_MIN_PLAYLIST_WEIGHT)
        }
    }

    fun score(
        sharedPlaylistSizes: Collection<Int>,
        weighting: PlaylistSizeWeighting = PlaylistSizeWeighting.BALANCED
    ): Double {
        if (sharedPlaylistSizes.isEmpty()) return 0.0
        val evidence = sharedPlaylistSizes.sumOf { playlistWeight(it, weighting) }
        if (evidence <= 0.0) return 0.0

        // Saturating evidence: one focused playlist is useful, multiple
        // independent playlists become much stronger without exploding.
        val base = 1.0 - exp(-evidence)
        val corroboration = 1.0 +
            ((sharedPlaylistSizes.size - 1).coerceIn(0, 3) * 0.08)
        return (base * corroboration).coerceIn(0.0, MAX_SCORE)
    }
}
