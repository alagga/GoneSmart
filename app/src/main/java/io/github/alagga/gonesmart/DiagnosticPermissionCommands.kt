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
        return listOf("su", "-c", safelyQuoted)
    }
}
