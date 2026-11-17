package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMultiPlaylistToastPolicyTest {
    @Test fun tagsOnlyExactHostLocalizedNativeResultFromMultiDestinationBatch() {
        val p = NativeMultiPlaylistToastPolicy
        assertTrue(p.shouldTag("2 Dateien hinzugefügt", "2 Dateien hinzugefügt",
            2, 3, 0, 100, 110))
        assertFalse(p.shouldTag("2 Dateien hinzugefügt (3 Playlists)",
            "2 Dateien hinzugefügt", 2, 3, 0, 100, 110))
        assertFalse(p.shouldTag("2 Dateien hinzugefügt", "2 Dateien hinzugefügt",
            2, 1, 0, 100, 110))
        assertFalse(p.shouldTag("2 Dateien hinzugefügt", "2 Dateien hinzugefügt",
            2, 3, 3, 100, 110))
        assertFalse(p.shouldTag("2 Dateien hinzugefügt", "2 Dateien hinzugefügt",
            2, 3, 1, 111, 110))
        assertFalse(p.shouldTag("2 Dateien hinzugefügt", "2 Dateien hinzugefügt",
            0, 3, 0, 100, 110))
    }
}
