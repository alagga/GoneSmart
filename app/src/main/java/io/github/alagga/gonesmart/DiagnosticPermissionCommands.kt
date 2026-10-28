package io.github.alagga.gonesmart

/**
 * Fixed commands for user-approved troubleshooting access only. No untrusted
 * input is interpolated into any privileged shell command.
 */
internal object DiagnosticPermissionCommands {
    private const val PACKAGE = "io.github.alagga.gonesmart"
    private const val PERMISSION = "android.permission.READ_LOGS"

    const val LOCAL_SHELL_GRANT = "pm grant $PACKAGE $PERMISSION"
    const val ADB_GRANT = "adb shell $LOCAL_SHELL_GRANT"
    const val ADB_REVOKE = "adb shell pm revoke $PACKAGE $PERMISSION"

    fun rootGrantProcessArguments(permissionAlreadyGranted: Boolean = false): List<String> =
        listOf(
            "su", "-c",
            if (permissionAlreadyGranted) "id -u" else "id -u && $LOCAL_SHELL_GRANT"
        )

    /**
     * Privileged Logcat is used only after explicit root-recording consent.
     * Quote every known argument so the shell cannot reinterpret a timestamp.
     */
    fun wrapLogcatInRoot(command: List<String>): List<String> {
        require(command.firstOrNull() == "logcat") { "Only logcat may run with diagnostic root." }
        val safelyQuoted = command.joinToString(" ") { arg ->
            "'" + arg.replace("'", "'\\''") + "'"
        }
        // exec avoids leaving a detached shell around when capture is stopped.
        return listOf("su", "-c", "exec $safelyQuoted")
    }

    /** Read-only: logd capacity metadata, never log messages. */
    fun inspectLogcatBuffers(rootApproved: Boolean): List<String> {
        val command = listOf("logcat", "-b", "main,system,crash", "-g")
        return if (rootApproved) wrapLogcatInRoot(command) else command
    }

    /** Only the main ring is writable; the numeric size is bounded and validated. */
    fun resizeMainLogcatBuffer(bytes: Long): List<String> {
        require(LogcatBufferPolicy.restorable(bytes)) { "Unsupported Logcat buffer size." }
        return wrapLogcatInRoot(listOf("logcat", "-b", "main", "-G", bytes.toString()))
    }
}
