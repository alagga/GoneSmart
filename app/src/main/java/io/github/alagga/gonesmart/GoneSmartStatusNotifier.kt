package io.github.alagga.gonesmart

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class GoneSmartStatusNotifier {

    companion object {

        private const val TAG =
            "GoneSmart"
    }

    fun show(
        message: String
    ) {

        val application =
            getCurrentApplication()
                ?: return

        Handler(
            Looper.getMainLooper()
        ).post {

            Toast.makeText(
                application,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showDelayed(
        message: String,
        delayMs: Long,
        shouldShow: () -> Boolean
    ) {

        val application =
            getCurrentApplication()
                ?: return

        Handler(
            Looper.getMainLooper()
        ).postDelayed(
            {
                if (
                    shouldShow()
                ) {

                    Toast.makeText(
                        application,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            delayMs
        )
    }

    private fun getCurrentApplication(): Application? {

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

        } catch (throwable: Throwable) {

            Log.w(
                TAG,
                "Unable to obtain application for status message",
                throwable
            )

            null
        }
    }
}
