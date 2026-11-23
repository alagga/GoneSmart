package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMoveUiSafetyPolicyTest {
    @Test fun exactRowMenuAnchorIsRequiredAndBounded() {
        assertTrue(SmartContextMoveAnchorPolicy.isValid(true,true,true,true,160))
        assertFalse(SmartContextMoveAnchorPolicy.isValid(true,true,true,true,3100))
        assertFalse(SmartContextMoveAnchorPolicy.isValid(true,true,false,true,20))
        assertFalse(SmartContextMoveAnchorPolicy.isValid(true,true,true,false,20))
        assertFalse(SmartContextMoveAnchorPolicy.isValid(true,false,true,true,20))
        assertFalse(SmartContextMoveAnchorPolicy.isValid(false,true,true,true,20))
    }

    @Test fun smartPrehideRequiresIndependentAdapterAndForegroundEvidence() {
        assertTrue(SmartFolderPrehidePolicy.mayPrehide(
            true,true,true,true,true,true
        ))
        for (mask in 0 until 6) {
            val evidence = BooleanArray(6) { it != mask }
            assertFalse(SmartFolderPrehidePolicy.mayPrehide(
                evidence[0],evidence[1],evidence[2],
                evidence[3],evidence[4],evidence[5]
            ))
        }
    }

    @Test fun originalNativePaletteWinsOverUnverifiedAccent() {
        val correctNativeFab = 0xFF36A8BE.toInt()
        val oldStaleRedAccent = 0xFF8E0E00.toInt()
        assertEquals(correctNativeFab,NativeMovePalettePolicy.preferredColor(
            correctNativeFab,null,oldStaleRedAccent
        ))
        assertEquals(correctNativeFab,NativeMovePalettePolicy.preferredColor(
            null,correctNativeFab,oldStaleRedAccent
        ))
        assertNull(NativeMovePalettePolicy.preferredColor(null,null,null))
    }
}
