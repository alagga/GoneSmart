package io.github.alagga.gonesmart

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class GoneSmartRuntimeReporter {
    companion object {
        private const val TAG = "GoneSmart"
        private const val MODULE_PACKAGE = "io.github.alagga.gonesmart"
        private const val DUPLICATE_EVENT_WINDOW_MS = 10_000L
    }

    @Volatile
    private var lastEventMessage: String? = null

    @Volatile
    private var lastEventElapsedMs: Long = Long.MIN_VALUE

    fun report(
        mode: String,
        message: String,
        appendEvent: Boolean = true
    ) {
        val application = currentApplication() ?: return
        val now = SystemClock.elapsedRealtime()
        val shouldAppend =
            appendEvent &&
                !(
                    lastEventMessage == message &&
                        lastEventElapsedMs != Long.MIN_VALUE &&
                        now - lastEventElapsedMs < DUPLICATE_EVENT_WINDOW_MS
                    )

        if (shouldAppend) {
            lastEventMessage = message
            lastEventElapsedMs = now
        }

        try {
            val intent = Intent(GoneSmartRuntimeContract.ACTION_RUNTIME_EVENT)
                .setPackage(MODULE_PACKAGE)
                .putExtra(GoneSmartRuntimeContract.EXTRA_MODE, mode)
                .putExtra(GoneSmartRuntimeContract.EXTRA_MESSAGE, message)
                .putExtra(GoneSmartRuntimeContract.EXTRA_EVENT, shouldAppend)

            application.sendBroadcast(intent)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to publish GoneSmart runtime event", throwable)
        }
    }

    private fun currentApplication(): Application? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            method.invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
    }
}
