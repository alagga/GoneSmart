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
    private const val ROOT_CAPTURE_APPROVED = "root_capture_approved"
    private const val PREVIOUS_MAIN_BUFFER_BYTES = "previous_main_buffer_bytes"
    private const val MAX_EXPORTS = 5
    const val ROLLING_FILES = 4
    const val ROLLING_FILE_BYTES = 1_500_000L

    const val ADB_COMMAND = "adb shell pm grant io.github.alagga.gonesmart android.permission.READ_LOGS"

    fun hasLogAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun recordingRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(RECORDING_ENABLED, false)

    /**
     * Opt-in persisted only after a successful explicit root verification.
     * A plain ADB READ_LOGS grant never opts the user into running su.
     */
    fun rootCaptureApproved(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ROOT_CAPTURE_APPROVED, false)

    fun setRootCaptureApproved(context: Context, approved: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ROOT_CAPTURE_APPROVED, approved).apply()
    }

    fun setRecordingRequested(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(RECORDING_ENABLED, enabled)
            .apply()
    }

    /**
     * A one-shot logd metadata query. Unlike the recorder, this starts no
     * persistent process and reads no messages from other apps.
     */
    internal fun inspectLogcatBuffers(context: Context): Map<String, LogcatBufferPolicy.Buffer> {
        val output = runControlCommand(
            DiagnosticPermissionCommands.inspectLogcatBuffers(rootCaptureApproved(context))
        )
        return LogcatBufferPolicy.parse(output).takeIf { it.containsKey("main") }
            ?: throw IllegalStateException("Android did not report the main Logcat buffer size.")
    }

    fun previousMainBufferBytes(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(PREVIOUS_MAIN_BUFFER_BYTES, -1L)
            .takeIf(LogcatBufferPolicy::restorable)

    /** Called only after a separate, explicit in-app confirmation. */
    fun enlargeMainBuffer(context: Context): Pair<Long, Long> {
        require(rootCaptureApproved(context)) { "Enable diagnostic root access first." }
        require(previousMainBufferBytes(context) == null) {
            "GoneSmart already remembers a previous main buffer size; restore it first."
        }
        val before = inspectLogcatBuffers(context).getValue("main").sizeBytes
        if (before >= LogcatBufferPolicy.TARGET_MAIN_BYTES) return before to before
        require(LogcatBufferPolicy.restorable(before)) {
            "Cannot safely remember Android's original main buffer size."
        }

        // Persist before changing logd, so an app crash cannot lose the restore size.
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(preferences.edit().putLong(PREVIOUS_MAIN_BUFFER_BYTES, before).commit()) {
            "Could not save the previous Logcat buffer size. No change was made."
        }
        try {
            runControlCommand(
                DiagnosticPermissionCommands.resizeMainLogcatBuffer(LogcatBufferPolicy.TARGET_MAIN_BYTES)
            )
            val after = inspectLogcatBuffers(context).getValue("main").sizeBytes
            check(after == LogcatBufferPolicy.TARGET_MAIN_BYTES) {
                "Android reported ${LogcatBufferPolicy.format(after)} instead of 8 MiB. " +
                    "The previous size is saved for restoration."
            }
            return before to after
        } catch (error: Exception) {
            // If Android rejected the change, don't show a spurious restore option.
            val current = runCatching {
                inspectLogcatBuffers(context).getValue("main").sizeBytes
            }.getOrNull()
            if (current == before) preferences.edit().remove(PREVIOUS_MAIN_BUFFER_BYTES).commit()
            throw error
        }
    }

    /** Never overwrite a buffer size that another actor changed in the meantime. */
    fun restoreMainBuffer(context: Context): String {
        require(rootCaptureApproved(context)) { "Enable diagnostic root access first." }
        val previous = previousMainBufferBytes(context)
            ?: throw IllegalStateException("No previously changed buffer size is saved.")
        val current = inspectLogcatBuffers(context).getValue("main").sizeBytes
        if (current != LogcatBufferPolicy.TARGET_MAIN_BYTES) {
            if (current == previous) {
                clearPreviousMainBuffer(context)
                return "Main Logcat buffer is already back at ${LogcatBufferPolicy.format(previous)}."
            }
            throw IllegalStateException(
                "Main Logcat buffer is now ${LogcatBufferPolicy.format(current)}, not 8 MiB. " +
                    "Another setting may have changed it; GoneSmart did not overwrite it."
            )
        }
        runControlCommand(DiagnosticPermissionCommands.resizeMainLogcatBuffer(previous))
        val restored = inspectLogcatBuffers(context).getValue("main").sizeBytes
        check(restored == previous) {
            "Android now reports ${LogcatBufferPolicy.format(restored)}; expected " +
                "${LogcatBufferPolicy.format(previous)}."
        }
        clearPreviousMainBuffer(context)
        return "Main Logcat buffer restored to ${LogcatBufferPolicy.format(previous)}."
    }

    private fun clearPreviousMainBuffer(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(PREVIOUS_MAIN_BUFFER_BYTES).commit()) {
            "Buffer size changed but the restore preference could not be cleared."
        }
    }

    private fun runControlCommand(arguments: List<String>): String {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Android Logcat control command timed out.")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().take(4096) }
            check(process.exitValue() == 0) {
                "Logcat command failed: ${output.trim().take(240)}"
            }
            return output
        } finally {
            process.destroy()
        }
    }

    /**
     * A snapshot requests a time window; it cannot recover messages already
     * evicted from Android's circular buffers. Include the separately retained
     * companion event history so an unexpectedly short Logcat is still useful.
     */
    fun snapshot(context: Context, minutes: Int = 5): File {
        require(minutes in 1..30)
        require(hasLogAccess(context)) { "ADB log access is not granted." }
        val uids = permittedUids(context)
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(System.currentTimeMillis() - minutes * 60_000L))
        val command = chooseBackend(context, listOf(
            "logcat", "-d", "-b", "main,system,crash",
            "-v", "threadtime", "--uid=$uids", "-T", stamp
        ))
        val bufferSizes = runCatching {
            LogcatBufferPolicy.display(inspectLogcatBuffers(context))
        }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
        val file = newExportFile(context, "last-${minutes}min")
        try {
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry("gmmp-gonesmart-logcat.txt"))
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                var lines = 0
                var bytes = 0
                var truncated = false
                var firstCaptured: String? = null
                var lastCaptured: String? = null
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val buffer = (line + "\n").toByteArray(Charsets.UTF_8)
                            if (DiagnosticCapturePolicy.shouldTruncate(lines, bytes, buffer.size)) {
                                truncated = true
                                break
                            }
                            zip.write(buffer)
                            DiagnosticCapturePolicy.threadtimeStamp(line)?.let { timestamp ->
                                if (firstCaptured == null) firstCaptured = timestamp
                                lastCaptured = timestamp
                            }
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
                } finally {
                    process.destroy()
                }
                if (lines == 0) {
                    zip.write(
                        "[GoneSmart: no matching Logcat messages remain in the requested window]\n"
                            .toByteArray(Charsets.UTF_8)
                    )
                }
                zip.closeEntry()

                val companionEvents = GoneSmartEventStore.logText(context)
                zip.putNextEntry(ZipEntry("gonesmart-companion-events.txt"))
                zip.write(
                    companionEvents.ifBlank { "No companion events have been recorded.\n" }
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("capture-info.txt"))
                zip.write((
                    info(context, "Logcat snapshot, up to $minutes minutes") +
                        "Requested start: $stamp\n" +
                        "First captured Logcat entry: ${firstCaptured ?: "none"}\n" +
                        "Last captured Logcat entry: ${lastCaptured ?: "none"}\n" +
                        "Exported Logcat lines: $lines ($bytes bytes)\n" +
                        "Snapshot truncated by GoneSmart: $truncated\n" +
                        "Logcat ring buffer sizes at export: $bufferSizes\n" +
                        "The first entry is not proof of Android buffer coverage; " +
                        "earlier matching messages may be absent or overwritten.\n" +
                        "For reliable intermittent-bug traces, start recording before the issue.\n"
                    ).toByteArray(Charsets.UTF_8))
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
    fun liveLogcatCommand(context: Context): List<String> = chooseBackend(
        context, listOf(
            "logcat", "-b", "main,system,crash",
            "-v", "threadtime", "--uid=${permittedUids(context)}",
            "-T", SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date(System.currentTimeMillis() - 1_000L))
        )
    )

    private fun chooseBackend(context: Context, command: List<String>): List<String> =
        if (rootCaptureApproved(context)) DiagnosticPermissionCommands.wrapLogcatInRoot(command)
        else command

    private fun permittedUids(context: Context): String {
        val gmmpUid = context.packageManager.getApplicationInfo(GMMP_PACKAGE, 0).uid
        return DiagnosticCapturePolicy.uidFilter(context.applicationInfo.uid, gmmpUid)
    }

    private fun info(context: Context, mode: String): String = buildString {
        appendLine("GoneSmart diagnostics")
        appendLine("Mode: $mode")
        appendLine("Time: ${Date()}")
        appendLine("GoneSmart: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_REVISION})")
        appendLine("Android: ${Build.VERSION.SDK_INT}")
        appendLine("Source: Android logcat main/system/crash, limited to GMMP and GoneSmart UIDs")
        appendLine("Capture backend: ${if (rootCaptureApproved(context)) "Root (explicitly approved)" else "App / READ_LOGS"}")
        appendLine("CAUTION: logs can contain local file names and other personal information.")
        appendLine("A Logcat snapshot is limited to messages still retained by Android; it cannot guarantee the requested time window.")
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
        // Mark the completed segment as newest before choosing an old one.
        // Otherwise a long-running current segment could replace itself.
        segment?.setLastModified(System.currentTimeMillis())
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
