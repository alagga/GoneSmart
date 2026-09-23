package io.github.alagga.gonesmart

import android.content.SharedPreferences

data class GoneSmartOptions(
    val enabled: Boolean = true,
    val multiPlaylistEnabled: Boolean = false,
    val flipQueueEnabled: Boolean = false,
    val trackMixEnabled: Boolean = true,
    val preferHigherRatedMatches: Boolean = true,
    val minimumRatingStars: Double = 0.0,
    val smartRatingEnabled: Boolean = false,
    val fallbackWithoutRatingRestrictions: Boolean = true,
    val excludeHalfStarTracks: Boolean = false,
    val preferStudioVersionsOverLive: Boolean = false,
    val matchQueueEra: Boolean = true,
    val favorRecentlyAddedTracks: Boolean = true,
    val preventBaseVersionDuplicates: Boolean = true,
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

            return GoneSmartOptions(
                enabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_ENABLED,
                    true
                ),
                multiPlaylistEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST,
                    false
                ),
                flipQueueEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_FLIP_QUEUE,
                    false
                ),
                trackMixEnabled = preferences.getBoolean(
                    GoneSmartSettingsKeys.KEY_TRACK_MIX,
                    true
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
