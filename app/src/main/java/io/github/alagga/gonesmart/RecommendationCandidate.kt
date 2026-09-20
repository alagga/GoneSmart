package io.github.alagga.gonesmart

enum class RecommendationProvider {
    LISTENBRAINZ,
    LASTFM
}

data class RecommendationContribution(
    val provider: RecommendationProvider,
    val seed: RecommendationSeed,
    val providerSimilarity: Double,
    val seedWeight: Double,
    val identityConfidence: Double,
    val weightedScore: Double
)

data class RecommendationCandidate(
    val artist: String,
    val title: String,
    val mbids: Set<String>,
    val contributions: List<RecommendationContribution>,
    val totalScore: Double
)