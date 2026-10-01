package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpPlaylistAdapterPolicyTest {
    @Test fun acceptsOnlyDeviceVerifiedMainPlaylistAdapters() {
        assertTrue(GmmpPlaylistAdapterPolicy.isVerified("zn3"))
        assertTrue(GmmpPlaylistAdapterPolicy.isVerified("ao3"))
        assertFalse(GmmpPlaylistAdapterPolicy.isVerified("is4"))
        assertFalse(GmmpPlaylistAdapterPolicy.isVerified(null))

        assertTrue(GmmpPlaylistAdapterPolicy.hasVerifiedModelSource("zn3"))
        assertFalse(GmmpPlaylistAdapterPolicy.hasVerifiedModelSource("ao3"))
        assertFalse(GmmpPlaylistAdapterPolicy.hasVerifiedModelSource(null))
    }
}
