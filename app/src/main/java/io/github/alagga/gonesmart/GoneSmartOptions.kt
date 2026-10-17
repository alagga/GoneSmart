package io.github.alagga.gonesmart

import android.content.SharedPreferences

data class GoneSmartOptions(
    val enabled: Boolean = true,
    val multiPlaylistEnabled: Boolean = false,
    val playlistFoldersEnabled: Boolean = false,
    val smartPlaylistFoldersEnabled: Boolean = false,
    val smartMultiPlaylistEnabled: Boolean = true,
    val playlistBridgeEnabled: Boolean = true,
    val smartGroupRootPlaylists: Boolean = false,
    val groupExternalPlaylists: Boolean = true,
    val groupRootPlaylists: Boolean = true,
    val flipQueueEnabled: Boolean = false,
    val trackMixEnabled: Boolean = true,
    val recommendationMode: RecommendationMode = RecommendationMode.SMART,
    val listenBrainzProviderEnabled: Boolean = true,
    val lastFmProviderEnabled: Boolean = true,
    val localPlaylistProviderEnabled: Boolean = true,
    val listenBrainzProviderWeight: Double = RecommendationProviderPolicy.SMART_LISTENBRAINZ_WEIGHT,
    val lastFmProviderWeight: Double = RecommendationProviderPolicy.SMART_LASTFM_WEIGHT,
    val localPlaylistProviderWeight: Double = RecommendationProviderPolicy.SMART_LOCAL_PLAYLIST_WEIGHT,
    val playlistSizeWeighting: PlaylistSizeWeighting = PlaylistSizeWeighting.BALANCED,
    val recommendationVariety: RecommendationVariety = RecommendationVariety.BALANCED,
    val preferHigherRatedMatches: Boolean = true,
    val minimumRatingStars: Double = 0.0,
    val smartRatingEnabled: Boolean = false,
    val fallbackWithoutRatingRestrictions: Boolean = true,
    val excludeHalfStarTracks: Boolean = false,
    val preferStudioVersionsOverLive: Boolean = false,
    val matchQueueEra: Boolean = true,
    val favorRecentlyAddedTracks: Boolean = true,
    val preventBaseVersionDuplicates: Boolean = true,
    val preventRemixDuplicates: Boolean = false,
    val fallbackToNativeAutoDjWhenNoSuitableTracks: Boolean = true,
    val showStatusMessages: Boolean = true
) {
    companion object {
        fun fromPreferences(
            preferences: SharedPreferences?
        ): GoneSmartOptions {
            if (preferences == null) {
                return GoneSmartOptions()
            }

            fun providerWeight(key: String, default: Double): Double =
                preferences.getFloat(key, default.toFloat())
                    .toDouble()
                    .coerceIn(
                        RecommendationProviderPolicy.MIN_PROVIDER_WEIGHT,
                        RecommendationProviderPolicy.MAX_PROVIDER_WEIGHT
                    )

            return GoneSmartOptions(
                enabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_ENABLED,
                    true
                ),
                multiPlaylistEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST,
                    false
                ),
                playlistFoldersEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS,
                    false
                ),
                smartPlaylistFoldersEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_SMART_PLAYLIST_FOLDERS,
                    false
                ),
                smartMultiPlaylistEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_SMART_MULTI_PLAYLIST,
                    true
                ),
                playlistBridgeEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PLAYLIST_BRIDGE,
                    true
                ),
                smartGroupRootPlaylists = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_SMART_GROUP_ROOT_PLAYLISTS,
                    false
                ),
                groupExternalPlaylists = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_GROUP_EXTERNAL_PLAYLISTS,
                    true
                ),
                groupRootPlaylists = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_GROUP_ROOT_PLAYLISTS,
                    true
                ),
                flipQueueEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_FLIP_QUEUE,
                    false
                ),
                trackMixEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_TRACK_MIX,
                    true
                ),
                recommendationMode = RecommendationMode.fromPreference(
                    preferences.getString(
                        GoneSmartSettingsKeys.KEY_RECOMMENDATION_MODE,
                        RecommendationMode.SMART.preferenceValue
                    )
                ),
                listenBrainzProviderEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_ENABLED,
                    true
                ),
                lastFmProviderEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_ENABLED,
                    true
                ),
                localPlaylistProviderEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED,
                    true
                ),
                listenBrainzProviderWeight = providerWeight(
                    GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_WEIGHT,
                    RecommendationProviderPolicy.SMART_LISTENBRAINZ_WEIGHT
                ),
                lastFmProviderWeight = providerWeight(
                    GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_WEIGHT,
                    RecommendationProviderPolicy.SMART_LASTFM_WEIGHT
                ),
                localPlaylistProviderWeight = providerWeight(
                    GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_WEIGHT,
                    RecommendationProviderPolicy.SMART_LOCAL_PLAYLIST_WEIGHT
                ),
                playlistSizeWeighting = PlaylistSizeWeighting.fromPreference(
                    preferences.getString(
                        GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING,
                        PlaylistSizeWeighting.BALANCED.preferenceValue
                    )
                ),
                recommendationVariety = RecommendationVariety.fromPreference(
                    preferences.getString(
                        GoneSmartSettingsKeys.KEY_RECOMMENDATION_VARIETY,
                        RecommendationVariety.BALANCED.preferenceValue
                    )
                ),
                preferHigherRatedMatches = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED,
                    true
                ),
                minimumRatingStars = preferences.getFloat(
                    GoneSmartSettingsKeys.KEY_MINIMUM_RATING,
                    0.0f
                )
                    .toDouble()
                    .coerceIn(
                        0.0,
                        5.0
                    ),
                smartRatingEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_SMART_RATING,
                    false
                ),
                fallbackWithoutRatingRestrictions = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING,
                    true
                ),
                excludeHalfStarTracks = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_EXCLUDE_HALF_STAR,
                    false
                ),
                preferStudioVersionsOverLive = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PREFER_STUDIO,
                    false
                ),
                matchQueueEra = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_MATCH_QUEUE_ERA,
                    true
                ),
                favorRecentlyAddedTracks = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_FAVOR_RECENTLY_ADDED,
                    true
                ),
                preventBaseVersionDuplicates = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES,
                    true
                ),
                preventRemixDuplicates = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_PREVENT_REMIX_DUPLICATES,
                    false
                ),
                fallbackToNativeAutoDjWhenNoSuitableTracks = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES,
                    true
                ),
                showStatusMessages = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES,
                    true
                )
            )
        }
    }
}
