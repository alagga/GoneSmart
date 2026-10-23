package io.github.alagga.gonesmart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * User-started foreground diagnostic capture. The service can continue while
 * the companion screen is closed and while GMMP is restarted. Android can
 * still stop it under system pressure: this is not a guaranteed boot recorder.
 */
class GoneSmartLogCaptureService : Service() {
    companion object {
        private const val TAG = "GoneSmartDiagnostics"
        private const val CHANNEL = "gonesmart_debug_capture"
        private const val NOTIFICATION_ID = 2405
        const val ACTION_START = "io.github.alagga.gonesmart.DIAGNOSTICS_START"
        const val ACTION_STOP = "io.github.alagga.gonesmart.DIAGNOSTICS_STOP"
    }

    @Volatile
    private var running = false
    private var logcatProcess: Process? = null
    private var readThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "GoneSmart diagnostic recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible while GMMP/GoneSmart diagnostic logs are being recorded."
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GoneSmartDiagnosticCapture.setRecordingRequested(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!GoneSmartDiagnosticCapture.recordingRequested(this) ||
            !GoneSmartDiagnosticCapture.hasLogAccess(this)
        ) {
            GoneSmartDiagnosticCapture.setRecordingRequested(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!running) startCapture()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GoneSmartLogCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("GoneSmart diagnostic recording")
            .setContentText("Recording GMMP + GoneSmart logs (bounded storage)")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun startCapture() {
        running = true
        readThread = Thread({
            try {
                val command = GoneSmartDiagnosticCapture.liveLogcatCommand(this)
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                logcatProcess = process
                if (!running) {
                    // Stop may race with process creation on a fast UI toggle.
                    process.destroy()
                    return@Thread
                }
                GoneSmartRollingLogWriter(GoneSmartDiagnosticCapture.rollingDir(this)).use { writer ->
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!running) break
                            writer.append(line)
                        }
                    }
                }
                if (running) {
                    Log.w(TAG, "Live logcat exited unexpectedly: ${process.waitFor()}")
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Live capture failed", e)
            } finally {
                if (running) {
                    running = false
                    GoneSmartDiagnosticCapture.setRecordingRequested(this, false)
                    stopSelf()
                }
            }
        }, "GoneSmartLogcatCapture").apply {
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        running = false
        logcatProcess?.destroy()
        logcatProcess = null
        readThread?.interrupt()
        readThread = null
        super.onDestroy()
    }
}
