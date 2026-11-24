package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovePrimaryColorPolicyTest {
    @Test fun rejectUnusableStaticPrimaryButAcceptLiveThemeColor() {
        assertFalse(NativeMovePalettePolicy.acceptLivePrimary(0xff000000.toInt()))
        assertFalse(NativeMovePalettePolicy.acceptLivePrimary(0xff101010.toInt()))
        assertTrue(NativeMovePalettePolicy.acceptLivePrimary(0xff256fa2.toInt()))
        assertFalse(NativeMovePalettePolicy.acceptLivePrimary(0x00256fa2))
    }
}
