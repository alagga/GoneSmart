package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistAdapterEventPolicyTest {
    @Test fun observesExactVerifiedOriginalAdapterNotifications() {
        assertEquals(
            listOf(
                "notifyDataSetChanged" to 0,
                "notifyItemInserted" to 1,
                "notifyItemRangeInserted" to 2,
                "notifyItemRemoved" to 1,
                "notifyItemRangeRemoved" to 2
            ),
            NativePlaylistAdapterEventPolicy.nativeMethods
        )
    }

    @Test fun newNativePlaylistRequestsRefreshWithoutWaitingForDraw() {
        assertTrue(NativePlaylistAdapterEventPolicy.needsRefresh(262, 263))
    }

    @Test fun noChangesAndTransientEmptyModelsMustNotReplaceBrowser() {
        assertFalse(NativePlaylistAdapterEventPolicy.needsRefresh(262, 262))
        assertFalse(NativePlaylistAdapterEventPolicy.needsRefresh(262, 0))
        assertFalse(NativePlaylistAdapterEventPolicy.needsRefresh(262, -1))
    }

    @Test fun nativePlaylistRemovalAlsoRefreshesFolderIndex() {
        assertTrue(NativePlaylistAdapterEventPolicy.needsRefresh(263, 262))
    }
}
