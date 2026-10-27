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

    fun rootGrantProcessArguments(): List<String> =
        listOf("su", "-c", LOCAL_SHELL_GRANT)
}
