package io.github.alagga.gonesmart

/**
 * Platform-free ancestor resolver used by semantic View hooks.
 *
 * Android may dispatch performClick/performLongClick from an inner TextView
 * rather than the RecyclerView's direct row child. Keeping this traversal
 * pure lets the ownership rule be regression-tested without GMMP/LSPosed.
 */
internal object AncestorOwnershipPolicy {
    fun <T : Any> directOwnedAncestor(
        start: T?,
        maxDepth: Int = 12,
        parentOf: (T) -> T?,
        isDirectOwnedChild: (T) -> Boolean
    ): T? {
        var node = start ?: return null
        repeat(maxDepth) {
            if (isDirectOwnedChild(node)) return node
            node = parentOf(node) ?: return null
        }
        return null
    }
}
