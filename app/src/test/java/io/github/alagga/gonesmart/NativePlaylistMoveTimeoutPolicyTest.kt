package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistMoveTimeoutPolicyTest {
    @Test fun noCancellationWhileOriginalNativeDialogRemainsOpen() {
        assertFalse(NativePlaylistMoveTimeoutPolicy.isAbandoned(
            null, 300_000L, originalsStillPresent = true
        ))
        assertFalse(NativePlaylistMoveTimeoutPolicy.hasTimedOut(
            1000, waitingForUserConfirmation = true
        ))
    }

    @Test fun cancellationGraceBeginsWhenNativeDialogActuallyCloses() {
        assertFalse(NativePlaylistMoveTimeoutPolicy.isAbandoned(
            300_000L, 301_000L, originalsStillPresent = true
        ))
        assertFalse(NativePlaylistMoveTimeoutPolicy.isAbandoned(
            300_000L, 360_001L, originalsStillPresent = false
        ))
        assertTrue(NativePlaylistMoveTimeoutPolicy.isAbandoned(
            300_000L, 360_001L, originalsStillPresent = true
        ))
        assertFalse(NativePlaylistMoveTimeoutPolicy.isAbandoned(
            300_000L, 299_999L, originalsStillPresent = true
        ))
    }

    @Test fun nativeScanMayTimeOutOnlyWhenNotAwaitingUser() {
        assertFalse(NativePlaylistMoveTimeoutPolicy.hasTimedOut(
            359, waitingForUserConfirmation = false
        ))
        assertTrue(NativePlaylistMoveTimeoutPolicy.hasTimedOut(
            360, waitingForUserConfirmation = false
        ))
    }
}
