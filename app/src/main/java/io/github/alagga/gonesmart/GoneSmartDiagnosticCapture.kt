package io.github.alagga.gonesmart

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Explicitly opt-in diagnostics. Never try to read another app's logcat
 * without the user granting READ_LOGS via adb, and never export system-wide
 * messages: logcat is restricted to the GMMP and GoneSmart Linux UIDs.
 */
object GoneSmartDiagnosticCapture {
    private const val TAG = "GoneSmartDiagnostics"
    private const val GMMP_PACKAGE = "gonemad.gmmp"
    private const val EXPORT_DIR = "diagnostics"
    private const val ROLLING_DIR = "diagnostic-capture"
    private const val PREFS = "gonesmart_diagnostics"
    private const val RECORDING_ENABLED = "recording_enabled"
    private const val MAX_EXPORTS = 5
    private const val MAX_SNAPSHOT_BYTES = 8_000_000
    private const val MAX_SNAPSHOT_LINES = 30_000
    const val ROLLING_FILES = 4
    const val ROLLING_FILE_BYTES = 1_500_000L

    const val ADB_COMMAND = "adb shell pm grant io.github.alagga.gonesmart android.permission.READ_LOGS"

    fun hasLogAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun recordingRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(RECORDING_ENABLED, false)

    fun setRecordingRequested(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(RECORDING_ENABLED, enabled)
            .apply()
    }

    /**
     * A snapshot does not require the recording service to have been running.
     * Android's circular logcat buffers may have already overwritten older
     * entries. The exported ZIP is kept inside the companion app until shared.
     */
    fun snapshot(context: Context, minutes: Int = 5): File {
        require(minutes in 1..30)
        require(hasLogAccess(context)) { "ADB log access is not granted." }
        val uids = permittedUids(context)
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(System.currentTimeMillis() - minutes * 60_000L))
        val command = listOf(
            "logcat", "-d", "-b", "main,system,crash",
            "-v", "threadtime", "--uid=$uids", "-T", stamp
        )
        val file = newExportFile(context, "last-${minutes}min")
        try {
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry("capture-info.txt"))
                zip.write(info(context, "Logcat snapshot, last $minutes minutes (as available)").toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("gmmp-gonesmart-logcat.txt"))
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                var lines = 0
                var bytes = 0
                var truncated = false
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val buffer = (line + "\n").toByteArray(Charsets.UTF_8)
                            if (lines >= MAX_SNAPSHOT_LINES || bytes + buffer.size > MAX_SNAPSHOT_BYTES) {
                                truncated = true
                                break
                            }
                            zip.write(buffer)
                            lines++
                            bytes += buffer.size
                        }
                    }
                    if (truncated) {
                        zip.write("\n[GoneSmart: snapshot truncated at safety limit]\n".toByteArray())
                        process.destroyForcibly()
                    }
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        throw IllegalStateException("Logcat did not finish in time.")
                    }
                    if (!truncated && process.exitValue() != 0) {
                        throw IllegalStateException("Logcat failed (exit ${process.exitValue()}); check ADB permission.")
                    }
                    if (lines == 0) {
                        throw IllegalStateException("No GMMP/GoneSmart lines remain in the Android log buffer.")
                    }
                } finally {
                    process.destroy()
                }
                zip.closeEntry()
            }
            pruneExports(context)
            return file
        } catch (error: Exception) {
            file.delete()
            Log.w(TAG, "Could not capture GMMP logs", error)
            throw error
        }
    }

    /** Share the bounded disk-backed trace independently of logcat ring retention. */
    fun exportRecording(context: Context): File {
        val source = rollingDir(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("segment-") && it.length() > 0 }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        require(source.isNotEmpty()) { "No recorded messages yet. Start recording first." }
        val file = newExportFile(context, "recording")
        try {
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry("capture-info.txt"))
                zip.write(info(context, "Continuous capture (last bounded segments)").toByteArray())
                zip.closeEntry()
                source.forEachIndexed { index, item ->
                    zip.putNextEntry(ZipEntry("gmmp-gonesmart-${index + 1}.txt"))
                    item.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            pruneExports(context)
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-files", file)

    fun rollingDir(context: Context): File =
        File(context.filesDir, ROLLING_DIR).apply { mkdirs() }

    /** No shell interpolation; every command argument is its own token. */
    fun liveLogcatCommand(context: Context): List<String> = listOf(
        "logcat", "-b", "main,system,crash",
        "-v", "threadtime", "--uid=${permittedUids(context)}",
        "-T", SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(System.currentTimeMillis() - 1_000L))
    )

    private fun permittedUids(context: Context): String {
        val gmmpUid = context.packageManager.getApplicationInfo(GMMP_PACKAGE, 0).uid
        return listOf(context.applicationInfo.uid, gmmpUid).distinct().joinToString(",")
    }

    private fun info(context: Context, mode: String): String = buildString {
        appendLine("GoneSmart diagnostics")
        appendLine("Mode: $mode")
        appendLine("Time: ${Date()}")
        appendLine("GoneSmart: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_REVISION})")
        appendLine("Android: ${Build.VERSION.SDK_INT}")
        appendLine("Source: Android logcat main/system/crash, limited to GMMP and GoneSmart UIDs")
        appendLine("CAUTION: logs can contain local file names and other personal information.")
        appendLine("The logcat circular buffer may not retain the full requested period.")
        appendLine("GMMP UID: ${runCatching { context.packageManager.getApplicationInfo(GMMP_PACKAGE, 0).uid }.getOrNull()}")
    }

    private fun newExportFile(context: Context, label: String): File {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return File(dir, "GoneSmart-${label}-$name.zip")
    }

    private fun pruneExports(context: Context) {
        File(context.cacheDir, EXPORT_DIR).listFiles()
            ?.filter { it.isFile && it.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_EXPORTS)
            ?.forEach { it.delete() }
    }
}

/** File rolling, not an unbounded in-memory backlog. */
internal class GoneSmartRollingLogWriter(private val directory: File) : java.io.Closeable {
    private var writer: OutputStreamWriter? = null
    private var segment: File? = null
    private var size = 0L
    private var lastFlush = 0L

    init {
        directory.mkdirs()
        rotate()
    }

    @Synchronized
    fun append(line: String) {
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        if (size + payload.size > GoneSmartDiagnosticCapture.ROLLING_FILE_BYTES) rotate()
        writer?.write(String(payload, Charsets.UTF_8))
        size += payload.size
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFlush >= 1_000L) {
            writer?.flush()
            lastFlush = now
        }
    }

    private fun rotate() {
        writer?.flush()
        writer?.close()
        val files = (0 until GoneSmartDiagnosticCapture.ROLLING_FILES).map {
            File(directory, "segment-$it.txt")
        }
        // On a service restart, only replace the oldest segment.
        segment = files.filterNot { it.exists() }.firstOrNull()
            ?: files.minByOrNull { it.lastModified() }
        writer = segment!!.outputStream().writer(Charsets.UTF_8)
        size = 0L
        segment!!.setLastModified(System.currentTimeMillis())
    }

    @Synchronized
    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }
}
