package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeQuickNavDiffTest {
    @Test fun openingFirstFolderAddsNativeRootAndSeparator() {
        assertEquals(
            NativeQuickNavDiff.Plan(0, 0, 0, 3),
            NativeQuickNavDiff.between(emptyList(), listOf("root", "Music"))
        )
    }

    @Test fun enteringChildFolderAppendsNativeTwoItemPair() {
        assertEquals(
            NativeQuickNavDiff.Plan(2, 3, 0, 2),
            NativeQuickNavDiff.between(
                listOf("root", "Music"),
                listOf("root", "Music", "House")
            )
        )
    }

    @Test fun returningToParentRemovesTheSeparatorAndChild() {
        assertEquals(
            NativeQuickNavDiff.Plan(2, 3, 2, 0),
            NativeQuickNavDiff.between(
                listOf("root", "Music", "House"),
                listOf("root", "Music")
            )
        )
    }

    @Test fun jumpingToSiblingPreservesSharedRootAndReplacesSuffix() {
        assertEquals(
            NativeQuickNavDiff.Plan(1, 1, 4, 4),
            NativeQuickNavDiff.between(
                listOf("root", "Music", "House"),
                listOf("root", "Podcasts", "Shows")
            )
        )
    }

    @Test fun unchangedPathNeverProducesFakeInsertions() {
        assertEquals(
            NativeQuickNavDiff.Plan(3, 5, 0, 0),
            NativeQuickNavDiff.between(
                listOf("root", "Music", "House"),
                listOf("root", "Music", "House")
            )
        )
    }
}
