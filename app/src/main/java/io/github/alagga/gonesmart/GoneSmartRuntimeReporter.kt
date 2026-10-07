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

    /**
     * Add a high-level UI/playlist/flip event to the companion Logs tab.
     * Unlike report(), this never replaces Smart DJ's current status.
     * Only report completed/verified operations as completed; an attempted
     * action and a successful action are separate events.
     */
    fun reportEvent(category: String, message: String) {
        val application = currentApplication() ?: return
        val key = "$category|$message"
        val now = SystemClock.elapsedRealtime()
        val append = synchronized(this) {
            val duplicate = lastEventMessage == key &&
                lastEventElapsedMs != Long.MIN_VALUE &&
                now - lastEventElapsedMs < DUPLICATE_EVENT_WINDOW_MS
            if (!duplicate) {
                lastEventMessage = key
                lastEventElapsedMs = now
            }
            !duplicate
        }
        if (!append) return

        try {
            application.sendBroadcast(
                Intent(GoneSmartRuntimeContract.ACTION_RUNTIME_EVENT)
                    .setPackage(MODULE_PACKAGE)
                    .putExtra(
                        GoneSmartRuntimeContract.EXTRA_MODE,
                        GoneSmartRuntimeContract.MODE_NONE
                    )
                    .putExtra(
                        GoneSmartRuntimeContract.EXTRA_MESSAGE,
                        message
                    )
                    .putExtra(
                        GoneSmartRuntimeContract.EXTRA_EVENT,
                        true
                    )
                    .putExtra(
                        GoneSmartRuntimeContract.EXTRA_EVENT_ONLY,
                        true
                    )
                    .putExtra(
                        GoneSmartRuntimeContract.EXTRA_CATEGORY,
                        category
                    )
            )
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to publish $category event", throwable)
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
