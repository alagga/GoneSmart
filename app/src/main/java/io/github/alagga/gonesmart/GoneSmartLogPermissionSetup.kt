package io.github.alagga.gonesmart

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Grant only the declared READ_LOGS development permission after an explicit
 * confirmation in the companion UI. Magisk/SuperSU owns user approval for su.
 */
internal object GoneSmartLogPermissionSetup {
    private const val MAX_GRANT_WAIT_SECONDS = 35L

    fun grantViaRoot(context: Context) {
        val hasAccess = GoneSmartDiagnosticCapture.hasLogAccess(context)
        val process = try {
            ProcessBuilder(DiagnosticPermissionCommands.rootGrantProcessArguments(hasAccess))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Root is unavailable. Use the no-root instructions or ADB instead.", e
            )
        }
        try {
            if (!process.waitFor(MAX_GRANT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException(
                    "Root permission request timed out. Check Magisk's superuser prompt."
                )
            }
            val diagnostic = process.inputStream.bufferedReader().use {
                it.readText().take(400).trim()
            }
            if (process.exitValue() != 0) {
                throw IllegalStateException(
                    "Root permission was denied or the grant failed." +
                        if (diagnostic.isBlank()) "" else " $diagnostic"
                )
            }
            if (!diagnostic.lineSequence().any { it.trim() == "0" }) {
                throw IllegalStateException("Superuser command did not run as root.")
            }
            if (!GoneSmartDiagnosticCapture.hasLogAccess(context)) {
                throw IllegalStateException(
                    "Root command finished, but Android did not grant READ_LOGS. " +
                        "Your ROM may restrict this permission; use ADB or a device bug report."
                )
            }
            // The user explicitly approved both a root grant and a privileged,
            // UID-filtered recorder in the preceding confirmation dialog.
            GoneSmartDiagnosticCapture.setRootCaptureApproved(context, true)
        } finally {
            process.destroy()
        }
    }
}
