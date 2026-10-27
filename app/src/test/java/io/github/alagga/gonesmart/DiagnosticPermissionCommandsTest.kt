package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticPermissionCommandsTest {
    @Test
    fun rootOnlyGrantsDeclaredLogPermissionToGoneSmart() {
        assertEquals(
            listOf(
                "su", "-c",
                "id -u && pm grant io.github.alagga.gonesmart android.permission.READ_LOGS"
            ),
            DiagnosticPermissionCommands.rootGrantProcessArguments()
        )
        assertEquals(
            listOf("su", "-c", "id -u"),
            DiagnosticPermissionCommands.rootGrantProcessArguments(permissionAlreadyGranted = true)
        )
    }

    @Test
    fun privilegedCapturePreservesOnlyUidFilteredLogcatArguments() {
        assertEquals(
            listOf(
                "su", "-c",
                "exec 'logcat' '-d' '--uid=10100,10101' '-T' '10-27 15:20:00.000'"
            ),
            DiagnosticPermissionCommands.wrapLogcatInRoot(
                listOf("logcat", "-d", "--uid=10100,10101", "-T", "10-27 15:20:00.000")
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticPermissionCommands.wrapLogcatInRoot(listOf("pm", "clear", "gonemad.gmmp"))
        }
    }

    @Test
    fun withoutRootUsesSameGrantViaOnDeviceShellOrComputer() {
        assertEquals(
            "pm grant io.github.alagga.gonesmart android.permission.READ_LOGS",
            DiagnosticPermissionCommands.LOCAL_SHELL_GRANT
        )
        assertEquals(
            "adb shell " + DiagnosticPermissionCommands.LOCAL_SHELL_GRANT,
            DiagnosticPermissionCommands.ADB_GRANT
        )
    }
}
