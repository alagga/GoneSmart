package io.github.alagga.gonesmart

import android.app.Activity
import android.app.Application
import android.content.Context
import java.lang.ref.WeakReference
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class GoneSmartStatusNotifier {

    companion object {

        private const val TAG =
            "GoneSmart"
    }

    @Volatile
    private var currentHostActivity = WeakReference<Activity>(null)

    /** Retain only a WEAK reference to GMMP's current app-locale Activity. */
    fun attachHostActivity(activity: Activity) {
        currentHostActivity = WeakReference(activity)
    }

    private fun liveHostContext(fallback: Application): Context {
        val host = currentHostActivity.get()
        return if (host != null && !host.isFinishing && !host.isDestroyed) {
            host
        } else fallback
    }

    /** GMMP-hosted Toast; never display English companion diagnostics here. */
    fun showNative(noticeKey: String) {
        val application = getCurrentApplication() ?: return
        Handler(Looper.getMainLooper()).post {
            val host = liveHostContext(application)
            Toast.makeText(
                host,
                NativeGmmpUiText.smartDjNotice(host, noticeKey),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showNativeDelayed(
        noticeKey: String,
        delayMs: Long,
        shouldShow: () -> Boolean
    ) {
        val application = getCurrentApplication() ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            if (shouldShow()) {
                val host = liveHostContext(application)
                Toast.makeText(
                    host,
                    NativeGmmpUiText.smartDjNotice(host, noticeKey),
                    Toast.LENGTH_LONG
                ).show()
            }
        }, delayMs)
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
