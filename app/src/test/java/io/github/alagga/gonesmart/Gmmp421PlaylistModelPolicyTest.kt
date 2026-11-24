package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gmmp421PlaylistModelPolicyTest {
    @Suppress("ClassName")
    private class yn3(val n: Long, var o: String, var p: String) {
        fun getId(): Long = n
    }
    @Suppress("ClassName")
    private class xn3(val n: Long, var o: String, var p: String) {
        fun getId(): Long = n
    }

    @Test fun originalPlaylistRowHasTheDexVerifiedShape() {
        assertTrue(Gmmp421PlaylistModelPolicy.isOriginalPlaylistRow(
            yn3::class.java
        ))
        assertFalse(Gmmp421PlaylistModelPolicy.isOriginalPlaylistRow(
            xn3::class.java
        ))
    }

    @Test fun rejectsMisidentifiedOrUnverifiedTypeShapes() {
        assertFalse(Gmmp421PlaylistModelPolicy.isOriginalPlaylistRow(
            String::class.java
        ))
    }
}
