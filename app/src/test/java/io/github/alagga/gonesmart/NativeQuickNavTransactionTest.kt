package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import androidx.recyclerview.widget.ListUpdateCallback

class NativeQuickNavTransactionTest {
    private fun events(old: List<String>, next: List<String>): List<String> {
        val events = mutableListOf<String>()
        NativeQuickNavTransaction.calculate(old, next).dispatchUpdatesTo(
            object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) {
                    events.add("insert:$position:$count")
                }
                override fun onRemoved(position: Int, count: Int) {
                    events.add("remove:$position:$count")
                }
                override fun onMoved(fromPosition: Int, toPosition: Int) {
                    events.add("move:$fromPosition:$toPosition")
                }
                override fun onChanged(position: Int, count: Int, payload: Any?) {
                    events.add("change:$position:$count")
                }
            }
        )
        return events
    }

    @Test fun nestedForwardAndBackMatchObservedNativeAdapterEvents() {
        val parent = listOf("root", "a")
        val child = listOf("root", "a", "b")
        assertEquals(listOf("insert:3:2"), events(parent, child))
        assertEquals(listOf("remove:3:2"), events(child, parent))
        assertEquals(listOf("insert:1:2"), events(listOf("root"), parent))
        assertEquals(listOf("remove:1:2"), events(parent, listOf("root")))
        assertEquals(emptyList<String>(), events(parent, parent))
    }

    @Test fun nativeAlternatingItemsUseEqualSeparators() {
        val items = NativeQuickNavTransaction.items(listOf("root", "a", "b"))
        assertEquals(5, items.size)
        assertEquals(items[1], items[3])
        assertNotEquals(items[0], items[1])
        assertNotEquals(items[2], items[4])
    }

    @Test fun nestedAndStorageReturnUseCompleteSnapshots() {
        val root = NativeQuickNavTransaction.items(listOf("root"))
        val a = NativeQuickNavTransaction.items(listOf("root", "a"))
        val b = NativeQuickNavTransaction.items(listOf("root", "a", "b"))
        assertEquals(root, a.take(1))
        assertEquals(a, b.take(3))
        assertEquals(root, b.take(1))
        assertEquals(emptyList<NativeQuickNavTransaction.Item>(),
            NativeQuickNavTransaction.items(emptyList()))
    }
}
