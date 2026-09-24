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
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_EXTERNAL_PLAYLISTS, local.groupExternalPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_ROOT_PLAYLISTS, local.groupRootPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_FLIP_QUEUE, local.flipQueueEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_TRACK_MIX, local.trackMixEnabled)
            .putBoolean(
                GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED,
                local.preferHigherRatedMatches
            )
            .putFloat(
                GoneSmartSettingsKeys.KEY_MINIMUM_RATING,
                local.minimumRatingStars.toFloat()
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_SMART_RATING,
                local.smartRatingEnabled
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING,
                local.fallbackWithoutRatingRestrictions
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_EXCLUDE_HALF_STAR,
                local.excludeHalfStarTracks
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_PREFER_STUDIO,
                local.preferStudioVersionsOverLive
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_MATCH_QUEUE_ERA,
                local.matchQueueEra
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_FAVOR_RECENTLY_ADDED,
                local.favorRecentlyAddedTracks
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES,
                local.preventBaseVersionDuplicates
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES,
                local.fallbackToNativeAutoDjWhenNoSuitableTracks
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES,
                local.showStatusMessages
            )
            .apply()
    }

    fun read(): GoneSmartOptions {
        val remote = remotePreferences()
        if (
            remote != null &&
            remote.getBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, false)
        ) {
            copyRemoteToLocal(remote)
        }
        return readLocal()
    }

    fun setBoolean(
        key: String,
        value: Boolean
    ) {
        val editor = localPreferences.edit()
            .putBoolean(key, value)
        // A later explicit user choice overrides Track Mix's queued
        // auto-enable command if the Xposed service was not yet bound.
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

    fun setFloat(
        key: String,
        value: Float
    ) {
        localPreferences.edit()
            .putFloat(key, value)
            .apply()

        remotePreferences()
            ?.edit()
            ?.putBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, true)
            ?.putFloat(key, value)
            ?.apply()
    }

    private fun readLocal(): GoneSmartOptions {
        return GoneSmartOptions.fromPreferences(localPreferences)
    }

    private fun remotePreferences(): SharedPreferences? {
        return try {
            GoneSmartApplication.xposedService
                ?.getRemotePreferences(GoneSmartSettingsKeys.GROUP)
        } catch (_: Throwable) {
            null
        }
    }

    private fun copyRemoteToLocal(remote: SharedPreferences) {
        val options = GoneSmartOptions.fromPreferences(remote)
        localPreferences.edit()
            .putBoolean(GoneSmartSettingsKeys.KEY_ENABLED, options.enabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST, options.multiPlaylistEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS, options.playlistFoldersEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_EXTERNAL_PLAYLISTS, options.groupExternalPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_GROUP_ROOT_PLAYLISTS, options.groupRootPlaylists)
            .putBoolean(GoneSmartSettingsKeys.KEY_FLIP_QUEUE, options.flipQueueEnabled)
            .putBoolean(GoneSmartSettingsKeys.KEY_TRACK_MIX, options.trackMixEnabled)
            .putBoolean(
                GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED,
                options.preferHigherRatedMatches
            )
            .putFloat(
                GoneSmartSettingsKeys.KEY_MINIMUM_RATING,
                options.minimumRatingStars.toFloat()
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_SMART_RATING,
                options.smartRatingEnabled
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING,
                options.fallbackWithoutRatingRestrictions
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_EXCLUDE_HALF_STAR,
                options.excludeHalfStarTracks
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_PREFER_STUDIO,
                options.preferStudioVersionsOverLive
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_MATCH_QUEUE_ERA,
                options.matchQueueEra
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_FAVOR_RECENTLY_ADDED,
                options.favorRecentlyAddedTracks
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES,
                options.preventBaseVersionDuplicates
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES,
                options.fallbackToNativeAutoDjWhenNoSuitableTracks
            )
            .putBoolean(
                GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES,
                options.showStatusMessages
            )
            .apply()
    }
}
