package io.github.alagga.gonesmart

import android.content.Context
import android.content.SharedPreferences

class GoneSmartSettingsRepository(
    context: Context
) {
    companion object {
        private const val LOCAL_PREFS = "gonesmart_ui_settings"
    }

    private val localPreferences =
        context.applicationContext.getSharedPreferences(
            LOCAL_PREFS,
            Context.MODE_PRIVATE
        )

    fun synchronizeWithFramework() {
        val remote = remotePreferences() ?: return

        if (remote.getBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, false)) {
            copyRemoteToLocal(remote)
            return
        }

        val local = readLocal()
        remote.edit()
            .putBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, true)
            .putBoolean(GoneSmartSettingsKeys.KEY_ENABLED, local.enabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST, local.multiPlaylistEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS, local.playlistFoldersEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_PLAYLIST_FOLDERS, local.smartPlaylistFoldersEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_MULTI_PLAYLIST, local.smartMultiPlaylistEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PLAYLIST_BRIDGE, local.playlistBridgeEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_GROUP_ROOT_PLAYLISTS, local.smartGroupRootPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_EXTERNAL_PLAYLISTS, local.groupExternalPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_ROOT_PLAYLISTS, local.groupRootPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_FLIP_QUEUE, local.flipQueueEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_TRACK_MIX, local.trackMixEnabled)
            .putString(GoneSmartSettingsKeys.KEY_RECOMMENDATION_MODE, local.recommendationMode.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_ENABLED, local.listenBrainzProviderEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_ENABLED, local.lastFmProviderEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED, local.localPlaylistProviderEnabled)
            .putFloat(GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_WEIGHT, local.listenBrainzProviderWeight.toFloat())
            .putFloat(GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_WEIGHT, local.lastFmProviderWeight.toFloat())
            .putFloat(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_WEIGHT, local.localPlaylistProviderWeight.toFloat())
            .putString(GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING, local.playlistSizeWeighting.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, local.preferHigherRatedMatches)
            .putFloat(GoneSmartSettingsKeys.KEY_MINIMUM_RATING, local.minimumRatingStars.toFloat())
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_RATING, local.smartRatingEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING, local.fallbackWithoutRatingRestrictions)
            .putBoolean(GoneSmartSettingsKeys.KEY_EXCLUDE_HALF_STAR, local.excludeHalfStarTracks)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_STUDIO, local.preferStudioVersionsOverLive)
            .putBoolean(GoneSmartSettingsKeys.KEY_MATCH_QUEUE_ERA, local.matchQueueEra)
            .putBoolean(GoneSmartSettingsKeys.KEY_FAVOR_RECENTLY_ADDED, local.favorRecentlyAddedTracks)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, local.preventBaseVersionDuplicates)
            .putBoolean(GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES, local.fallbackToNativeAutoDjWhenNoSuitableTracks)
            .putBoolean(GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES, local.showStatusMessages)
            .apply()
    }

    fun read(): GoneSmartOptions {
        val remote = remotePreferences()
        if (remote != null && remote.getBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, false)) {
            copyRemoteToLocal(remote)
        }
        return readLocal()
    }

    fun setBoolean(key: String, value: Boolean) {
        val editor = localPreferences.edit().putBoolean(key, value)
        if (key == GoneSmartSettingsKeys.KEY_ENABLED && !value) {
            editor.remove("pending_track_mix_enable")
        }
        editor.apply()

        remotePreferences()
            ?.edit()
            ?.putBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, true)
            ?.putBoolean(key, value)
            ?.apply()
    }

    fun setFloat(key: String, value: Float) {
        localPreferences.edit().putFloat(key, value).apply()
        remotePreferences()
            ?.edit()
            ?.putBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, true)
            ?.putFloat(key, value)
            ?.apply()
    }

    fun setString(key: String, value: String) {
        localPreferences.edit().putString(key, value).apply()
        remotePreferences()
            ?.edit()
            ?.putBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, true)
            ?.putString(key, value)
            ?.apply()
    }

    private fun readLocal(): GoneSmartOptions =
        GoneSmartOptions.fromPreferences(localPreferences)

    private fun remotePreferences(): SharedPreferences? = try {
        GoneSmartApplication.xposedService
            ?.getRemotePreferences(GoneSmartSettingsKeys.GROUP)
    } catch (_: Throwable) {
        null
    }

    private fun copyRemoteToLocal(remote: SharedPreferences) {
        val options = GoneSmartOptions.fromPreferences(remote)
        localPreferences.edit()
            .putBoolean(GoneSmartSettingsKeys.KEY_ENABLED, options.enabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST, options.multiPlaylistEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS, options.playlistFoldersEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_PLAYLIST_FOLDERS, options.smartPlaylistFoldersEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_MULTI_PLAYLIST, options.smartMultiPlaylistEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PLAYLIST_BRIDGE, options.playlistBridgeEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_GROUP_ROOT_PLAYLISTS, options.smartGroupRootPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_EXTERNAL_PLAYLISTS, options.groupExternalPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_ROOT_PLAYLISTS, options.groupRootPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_FLIP_QUEUE, options.flipQueueEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_TRACK_MIX, options.trackMixEnabled)
            .putString(GoneSmartSettingsKeys.KEY_RECOMMENDATION_MODE, options.recommendationMode.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_ENABLED, options.listenBrainzProviderEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_ENABLED, options.lastFmProviderEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED, options.localPlaylistProviderEnabled)
            .putFloat(GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_WEIGHT, options.listenBrainzProviderWeight.toFloat())
            .putFloat(GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_WEIGHT, options.lastFmProviderWeight.toFloat())
            .putFloat(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_WEIGHT, options.localPlaylistProviderWeight.toFloat())
            .putString(GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING, options.playlistSizeWeighting.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)
            .putFloat(GoneSmartSettingsKeys.KEY_MINIMUM_RATING, options.minimumRatingStars.toFloat())
            .putBoolean(GoneSmartSettingsKeys.KEY_SMART_RATING, options.smartRatingEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING, options.fallbackWithoutRatingRestrictions)
            .putBoolean(GoneSmartSettingsKeys.KEY_EXCLUDE_HALF_STAR, options.excludeHalfStarTracks)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_STUDIO, options.preferStudioVersionsOverLive)
            .putBoolean(GoneSmartSettingsKeys.KEY_MATCH_QUEUE_ERA, options.matchQueueEra)
            .putBoolean(GoneSmartSettingsKeys.KEY_FAVOR_RECENTLY_ADDED, options.favorRecentlyAddedTracks)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, options.preventBaseVersionDuplicates)
            .putBoolean(GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES, options.fallbackToNativeAutoDjWhenNoSuitableTracks)
            .putBoolean(GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES, options.showStatusMessages)
            .apply()
    }
}
