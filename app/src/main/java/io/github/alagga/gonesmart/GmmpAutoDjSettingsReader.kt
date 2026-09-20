package io.github.alagga.gonesmart

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.ceil
import kotlin.math.max


data class GmmpAutoDjSettings(
    val initialQueueSize: Int,
    val upcomingTrackCount: Int,
    val source: String
)


data class SmartPoolSizing(
    val targetSize: Int,
    val lowWaterMark: Int,
    val minimumConsumedBeforeRefill: Int
)


class GmmpAutoDjSettingsReader {

    companion object {

        private const val TAG =
            "GoneSmart"

        /*
         * These are the actual GMMP preference keys found
         * in the current GMMP APK.
         */
        private const val KEY_INITIAL_SIZE =
            "autoDj_initialSize"

        private const val KEY_UPCOMING_TRACKS =
            "autoDj_upcomingTracks"

        private const val FALLBACK_INITIAL_SIZE =
            5

        private const val FALLBACK_UPCOMING_TRACKS =
            1

        private const val MIN_POOL_SIZE =
            12

        private const val MAX_POOL_SIZE =
            30
    }

    fun read(): GmmpAutoDjSettings {

        val application =
            currentApplication()
                ?: return fallback(
                    "application unavailable"
                )

        val preferenceNames =
            linkedSetOf<String>()

        preferenceNames +=
            "${application.packageName}_preferences"

        /*
         * Keep this discovery path because GMMP may move a
         * preference to another SharedPreferences file in a
         * later build. Reading app-private files here is still
         * inside the GMMP process, so it also remains compatible
         * with the planned LSPatch architecture.
         */
        try {

            val sharedPreferencesDirectory =
                File(
                    application.applicationInfo.dataDir,
                    "shared_prefs"
                )

            sharedPreferencesDirectory
                .listFiles()
                ?.filter {
                    it.isFile &&
                        it.name.endsWith(
                            ".xml",
                            ignoreCase = true
                        )
                }
                ?.forEach { file ->

                    preferenceNames +=
                        file.name
                            .removeSuffix(
                                ".xml"
                            )
                }

        } catch (_: Throwable) {
            /* Best-effort discovery only. */
        }

        for (
            preferenceName in
            preferenceNames
        ) {

            try {

                val preferences =
                    application
                        .getSharedPreferences(
                            preferenceName,
                            Context.MODE_PRIVATE
                        )

                val all =
                    preferences.all

                val initialSize =
                    parseInt(
                        all[KEY_INITIAL_SIZE]
                    )

                val upcomingTracks =
                    parseInt(
                        all[KEY_UPCOMING_TRACKS]
                    )

                if (
                    initialSize != null ||
                    upcomingTracks != null
                ) {

                    return GmmpAutoDjSettings(
                        initialQueueSize =
                            sanitize(
                                initialSize
                                    ?: FALLBACK_INITIAL_SIZE,
                                FALLBACK_INITIAL_SIZE
                            ),
                        upcomingTrackCount =
                            sanitize(
                                upcomingTracks
                                    ?: FALLBACK_UPCOMING_TRACKS,
                                FALLBACK_UPCOMING_TRACKS
                            ),
                        source =
                            "SharedPreferences:$preferenceName"
                    )
                }

            } catch (_: Throwable) {
                /* Try the next preference file. */
            }
        }

        return fallback(
            "GMMP preference keys not found"
        )
    }

    fun calculatePoolSizing(
        settings: GmmpAutoDjSettings
    ): SmartPoolSizing {

        /*
         * The visible GMMP upcoming queue and the internal
         * GoneSmart recommendation pool serve different jobs.
         * The pool should therefore be several times larger.
         *
         * With the user's current GMMP settings (initial=5,
         * upcoming=1), this produces a target of 20 and a
         * low-water mark of 6.
         */
        val target =
            max(
                MIN_POOL_SIZE,
                max(
                    settings.initialQueueSize * 4,
                    settings.upcomingTrackCount * 8
                )
            )
                .coerceAtMost(
                    MAX_POOL_SIZE
                )

        val lowWater =
            max(
                settings.upcomingTrackCount * 2,
                ceil(
                    target * 0.30
                ).toInt()
            )
                .coerceIn(
                    1,
                    max(
                        1,
                        target - 1
                    )
                )

        val minimumConsumedBeforeRefill =
            max(
                3,
                settings.upcomingTrackCount * 2
            )
                .coerceAtMost(
                    max(
                        1,
                        target
                    )
                )

        return SmartPoolSizing(
            targetSize = target,
            lowWaterMark = lowWater,
            minimumConsumedBeforeRefill =
                minimumConsumedBeforeRefill
        )
    }

    private fun fallback(
        reason: String
    ): GmmpAutoDjSettings {

        Log.w(
            TAG,
            "GMMP Auto-DJ settings fallback | $reason"
        )

        return GmmpAutoDjSettings(
            initialQueueSize =
                FALLBACK_INITIAL_SIZE,
            upcomingTrackCount =
                FALLBACK_UPCOMING_TRACKS,
            source =
                "fallback"
        )
    }

    private fun sanitize(
        value: Int,
        fallback: Int
    ): Int {

        return if (
            value > 0
        ) {

            value

        } else {

            fallback
        }
    }

    private fun parseInt(
        value: Any?
    ): Int? {

        return when (
            value
        ) {

            is Int ->
                value

            is Long ->
                value.toInt()

            is Short ->
                value.toInt()

            is Byte ->
                value.toInt()

            is Float ->
                value.toInt()

            is Double ->
                value.toInt()

            is String ->
                value
                    .trim()
                    .toIntOrNull()

            else ->
                null
        }
    }

    private fun currentApplication(): Application? {

        return try {

            val activityThreadClass =
                Class.forName(
                    "android.app.ActivityThread"
                )

            val currentApplicationMethod =
                activityThreadClass
                    .getDeclaredMethod(
                        "currentApplication"
                    )

            currentApplicationMethod.isAccessible =
                true

            currentApplicationMethod.invoke(
                null
            ) as? Application

        } catch (_: Throwable) {

            null
        }
    }
}
