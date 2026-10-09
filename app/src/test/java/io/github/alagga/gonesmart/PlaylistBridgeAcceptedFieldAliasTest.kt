package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBridgeAcceptedFieldAliasTest {
    @Test
    fun `GMMP 4_2_1 smart editor state aliases preserve historical fast paths`() {
        assertEquals(
            listOf("x", "w"),
            PlaylistBridgeReflectionResolver.acceptedFieldNameOrder(
                typeName = "es4",
                preferredNames = listOf("x")
            )
        )
        assertEquals(
            listOf("y", "x"),
            PlaylistBridgeReflectionResolver.acceptedFieldNameOrder(
                typeName = "es4",
                preferredNames = listOf("y")
            )
        )
        assertEquals(
            listOf("v", "u"),
            PlaylistBridgeReflectionResolver.acceptedFieldNameOrder(
                typeName = "es4",
                preferredNames = listOf("v")
            )
        )
    }

    @Test
    fun `unknown state class keeps only requested fast paths`() {
        assertEquals(
            listOf("y"),
            PlaylistBridgeReflectionResolver.acceptedFieldNameOrder(
                typeName = "futureState",
                preferredNames = listOf("y")
            )
        )
    }
}
