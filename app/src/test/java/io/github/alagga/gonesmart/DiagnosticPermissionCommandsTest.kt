package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticPermissionCommandsTest {
    @Test
    fun rootOnlyGrantsDeclaredLogPermissionToGoneSmart() {
        assertEquals(
            listOf(
                "su", "-c",
                "pm grant io.github.alagga.gonesmart android.permission.READ_LOGS"
            ),
            DiagnosticPermissionCommands.rootGrantProcessArguments()
        )
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
