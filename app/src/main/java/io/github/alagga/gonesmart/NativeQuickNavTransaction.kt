package io.github.alagga.gonesmart

/** The native Files presenter interleaves equal empty-path separators. */
internal object NativeQuickNavTransaction {
    data class Item(val metadataIdentity: String?)
    fun items(segments: List<String>): List<Item> = buildList {
        segments.forEachIndexed { index, identity ->
            if (index > 0) add(Item(null))
            add(Item(identity))
        }
    }
}
