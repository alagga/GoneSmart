package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBridgePlaylistDaoResolverTest {
    private data class PlaylistRow(
        val path: String,
        val name: String,
        val type: Int = 0
    )

    private abstract class PlaylistDao421Shape {
        abstract fun renamedAll(): List<PlaylistRow>
        abstract fun renamedLookup(path: String): PlaylistRow
    }

    private abstract class UnrelatedListDao {
        abstract fun all(): List<String>
        abstract fun byId(id: Long): String
    }

    private abstract class WrongLookupModelDao {
        abstract fun all(): List<String>
        abstract fun lookup(path: String): String
    }

    private open class HistoricalLookingBase {
        @Suppress("unused") fun inheritedRows(): List<String> = emptyList()
    }

    private class HistoricalLookingUiClass : HistoricalLookingBase()

    @Test
    fun `accepts playlist dao without relying on obfuscated method names`() {
        assertTrue(
            PlaylistBridgeReflectionResolver.matchesPlaylistDaoType(
                PlaylistDao421Shape::class.java
            )
        )
    }

    @Test
    fun `rejects unrelated list dao`() {
        assertFalse(
            PlaylistBridgeReflectionResolver.matchesPlaylistDaoType(
                UnrelatedListDao::class.java
            )
        )
    }

    @Test
    fun `rejects historical-looking UI class that only inherits a list reader`() {
        assertFalse(
            PlaylistBridgeReflectionResolver.matchesPlaylistDaoType(
                HistoricalLookingUiClass::class.java
            )
        )
    }

    @Test
    fun `rejects string lookup that does not return a playlist model`() {
        assertFalse(
            PlaylistBridgeReflectionResolver.matchesPlaylistDaoType(
                WrongLookupModelDao::class.java
            )
        )
    }
}
