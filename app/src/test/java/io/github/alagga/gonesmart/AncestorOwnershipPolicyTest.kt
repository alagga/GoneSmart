package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AncestorOwnershipPolicyTest {
    private class Node(
        val name: String,
        var parent: Node? = null,
        val directRow: Boolean = false
    )

    @Test fun descendantDispatchResolvesToDirectRecyclerRow() {
        val row = Node("row", directRow = true)
        val content = Node("content", row)
        val title = Node("title", content)

        val resolved = AncestorOwnershipPolicy.directOwnedAncestor(
            start = title,
            parentOf = { it.parent },
            isDirectOwnedChild = { it.directRow }
        )

        assertEquals(row, resolved)
    }

    @Test fun unrelatedDescendantFailsClosed() {
        val root = Node("root")
        val child = Node("child", root)

        assertNull(
            AncestorOwnershipPolicy.directOwnedAncestor(
                start = child,
                parentOf = { it.parent },
                isDirectOwnedChild = { it.directRow }
            )
        )
    }

    @Test fun traversalIsBounded() {
        var node = Node("row", directRow = true)
        repeat(20) { index ->
            node = Node("n$index", node)
        }

        assertNull(
            AncestorOwnershipPolicy.directOwnedAncestor(
                start = node,
                maxDepth = 8,
                parentOf = { it.parent },
                isDirectOwnedChild = { it.directRow }
            )
        )
    }
}
