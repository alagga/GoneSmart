package io.github.alagga.gonesmart

/**
 * User-facing recommendation policy. SMART stays deliberately conservative:
 * every stable provider is enabled and weights remain under GoneSmart's
 * control so defaults can improve without forcing users to retune settings.
 */
enum class RecommendationMode(val preferenceValue: String) {
    SMART("smart"),
    CUSTOM("custom");

    companion object {
        fun fromPreference(value: String?): RecommendationMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SMART
    }
}

enum class PlaylistSizeWeighting(val preferenceValue: String) {
    OFF("off"),
    BALANCED("balanced"),
    STRONG("strong");

    companion object {
        fun fromPreference(value: String?): PlaylistSizeWeighting =
            entries.firstOrNull { it.preferenceValue == value } ?: BALANCED
    }
}

enum class RecommendationVariety(val preferenceValue: String) {
    OFF("off"),
    BALANCED("balanced"),
    HIGH("high");

    companion object {
        fun fromPreference(value: String?): RecommendationVariety =
            entries.firstOrNull { it.preferenceValue == value } ?: BALANCED
    }
}

data class RecommendationProviderPolicy(
    val mode: RecommendationMode,
    val enabledProviders: Set<RecommendationProvider>,
    val providerWeights: Map<RecommendationProvider, Double>,
    val playlistSizeWeighting: PlaylistSizeWeighting,
    val variety: RecommendationVariety
) {
    fun isEnabled(provider: RecommendationProvider): Boolean =
        provider in enabledProviders

    fun weight(provider: RecommendationProvider): Double =
        providerWeights[provider]
            ?.coerceIn(MIN_PROVIDER_WEIGHT, MAX_PROVIDER_WEIGHT)
            ?: 1.0

    companion object {
        const val MIN_PROVIDER_WEIGHT = 0.25
        const val MAX_PROVIDER_WEIGHT = 1.50

        const val SMART_LISTENBRAINZ_WEIGHT = 1.00
        const val SMART_LASTFM_WEIGHT = 0.95
        const val SMART_LOCAL_PLAYLIST_WEIGHT = 1.00

        fun from(options: GoneSmartOptions): RecommendationProviderPolicy {
            if (options.recommendationMode == RecommendationMode.SMART) {
                return RecommendationProviderPolicy(
                    mode = RecommendationMode.SMART,
                    enabledProviders = RecommendationProvider.entries.toSet(),
                    providerWeights = mapOf(
                        RecommendationProvider.LISTENBRAINZ to SMART_LISTENBRAINZ_WEIGHT,
                        RecommendationProvider.LASTFM to SMART_LASTFM_WEIGHT,
                        RecommendationProvider.LOCAL_PLAYLIST to SMART_LOCAL_PLAYLIST_WEIGHT
                    ),
                    playlistSizeWeighting = PlaylistSizeWeighting.BALANCED,
                    variety = RecommendationVariety.BALANCED
                )
            }

            val enabled = buildSet {
                if (options.listenBrainzProviderEnabled) add(RecommendationProvider.LISTENBRAINZ)
                if (options.lastFmProviderEnabled) add(RecommendationProvider.LASTFM)
                if (options.localPlaylistProviderEnabled) add(RecommendationProvider.LOCAL_PLAYLIST)
            }

            return RecommendationProviderPolicy(
                mode = RecommendationMode.CUSTOM,
                enabledProviders = enabled,
                providerWeights = mapOf(
                    RecommendationProvider.LISTENBRAINZ to options.listenBrainzProviderWeight,
                    RecommendationProvider.LASTFM to options.lastFmProviderWeight,
                    RecommendationProvider.LOCAL_PLAYLIST to options.localPlaylistProviderWeight
                ),
                playlistSizeWeighting = options.playlistSizeWeighting,
                variety = options.recommendationVariety
            )
        }
    }
}
