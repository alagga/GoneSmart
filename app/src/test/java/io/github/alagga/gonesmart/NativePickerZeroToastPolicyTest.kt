package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePickerZeroToastPolicyTest {
    @Test fun nativeEmptyResultIsSuppressedOnlyDuringItsOwnCreate() {
        val policy = NativePickerZeroToastPolicy()
        assertFalse(policy.shouldSuppress(
            "0 Dateien zur Playlist hinzugefügt",
            "0 Dateien zur Playlist hinzugefügt",
            100, insideCreate = false
        ))
        assertTrue(policy.shouldSuppress(
            "0 Dateien zur Playlist hinzugefügt",
            "0 Dateien zur Playlist hinzugefügt",
            100, insideCreate = true
        ))
    }

    @Test fun oneDelayedNativeToastConsumesExactlyOneSuccessfulCreate() {
        val policy = NativePickerZeroToastPolicy()
        policy.arm(100)
        assertTrue(policy.hasPending(101))
        assertFalse(policy.shouldSuppress(
            "1 Datei zur Playlist hinzugefügt",
            "0 Dateien zur Playlist hinzugefügt",
            102, insideCreate = false
        ))
        assertTrue(policy.shouldSuppress(
            "0 Dateien zur Playlist hinzugefügt",
            "0 Dateien zur Playlist hinzugefügt",
            103, insideCreate = false
        ))
        assertFalse(policy.shouldSuppress(
            "0 Dateien zur Playlist hinzugefügt",
            "0 Dateien zur Playlist hinzugefügt",
            104, insideCreate = false
        ))
    }

    @Test fun anExpiredOrDifferentLanguageToastCannotBeHidden() {
        val policy = NativePickerZeroToastPolicy(windowMs = 50)
        policy.arm(10)
        assertFalse(policy.shouldSuppress(
            "0 files added", "0 Dateien zur Playlist hinzugefügt",
            20, insideCreate = false
        ))
        assertFalse(policy.shouldSuppress(
            "0 Dateien zur Playlist hinzugefügt",
            "0 Dateien zur Playlist hinzugefügt",
            61, insideCreate = false
        ))
    }
}
