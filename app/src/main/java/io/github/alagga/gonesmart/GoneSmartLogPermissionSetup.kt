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
        check(!GoneSmartDiagnosticCapture.hasLogAccess(context)) {
            "Detailed logging permission is already granted."
        }
        val process = try {
            ProcessBuilder(DiagnosticPermissionCommands.rootGrantProcessArguments())
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
            if (!GoneSmartDiagnosticCapture.hasLogAccess(context)) {
                throw IllegalStateException(
                    "Root command finished, but Android did not grant READ_LOGS. " +
                        "Your ROM may restrict this permission; use ADB or a device bug report."
                )
            }
        } finally {
            process.destroy()
        }
    }
}
