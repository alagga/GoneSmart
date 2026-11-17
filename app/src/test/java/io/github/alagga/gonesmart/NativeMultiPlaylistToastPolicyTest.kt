package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMultiPlaylistToastPolicyTest {
    @Test fun infersTracksFromExactLocalizedToastWithoutOldPickerHandler() {
        val p = NativeMultiPlaylistToastPolicy
        val native = { count: Int -> "$count Dateien hinzugefügt" }
        assertEquals(2, p.resolveNativeCount("2 Dateien hinzugefügt", 0, native))
        assertEquals(1, p.resolveNativeCount("1 Dateien hinzugefügt", 0, native))
        assertNull(p.resolveNativeCount("nothing", 0, native))
        assertNull(p.resolveNativeCount("2 Dateien hinzugefügt", 3, native))
        assertEquals(2, p.resolveNativeCount("2 Dateien hinzugefügt", 2, native))
        assertNull(p.resolveNativeCount("Fertig", 0, { _: Int -> "Fertig" }))
        assertNull(p.resolveNativeCount("999 Dateien hinzugefügt", 0, native))
    }

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
