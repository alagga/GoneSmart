package io.github.alagga.gonesmart

enum class RecommendationCapability {
    TRACK_SIMILARITY,
    ARTIST_SIMILARITY,
    LOCAL_AFFINITY
}

enum class RecommendationAuthentication {
    NONE,
    APPLICATION_KEY,
    USER_OPTIONAL,
    CLIENT_CREDENTIALS
}

data class RecommendationProviderDescriptor(
    val id: RecommendationProvider,
    val capabilities: Set<RecommendationCapability>,
    val authentication: RecommendationAuthentication,
    val networkRequired: Boolean,
    val requestBudgetPerSeed: Int,
    val experimental: Boolean = false
)

interface RecommendationProviderSource {
    val descriptor: RecommendationProviderDescriptor
}

object RecommendationProviderRegistry {
    private val descriptors = mapOf(
        RecommendationProvider.LISTENBRAINZ to RecommendationProviderDescriptor(
            id = RecommendationProvider.LISTENBRAINZ,
            capabilities = setOf(
                RecommendationCapability.TRACK_SIMILARITY,
                RecommendationCapability.ARTIST_SIMILARITY
            ),
            authentication = RecommendationAuthentication.NONE,
            networkRequired = true,
            requestBudgetPerSeed = 8
        ),
        RecommendationProvider.LASTFM to RecommendationProviderDescriptor(
            id = RecommendationProvider.LASTFM,
            capabilities = setOf(
                RecommendationCapability.TRACK_SIMILARITY,
                RecommendationCapability.ARTIST_SIMILARITY
            ),
            authentication = RecommendationAuthentication.APPLICATION_KEY,
            networkRequired = true,
            requestBudgetPerSeed = 8
        ),
        RecommendationProvider.LOCAL_PLAYLIST to RecommendationProviderDescriptor(
            id = RecommendationProvider.LOCAL_PLAYLIST,
            capabilities = setOf(RecommendationCapability.LOCAL_AFFINITY),
            authentication = RecommendationAuthentication.NONE,
            networkRequired = false,
            requestBudgetPerSeed = 0
        )
    )

    fun require(id: RecommendationProvider): RecommendationProviderDescriptor =
        descriptors[id] ?: error("No provider descriptor for $id")

    fun all(): List<RecommendationProviderDescriptor> = descriptors.values.toList()
}
