package io.github.alagga.gonesmart

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSmartModelCacheTest {
    private class NativeModel(val version: Int)
    private class OtherModel

    @Test fun unchangedFileReusesNativeModelButAlwaysExpires() {
        val file = File.createTempFile("gonesmart-native-smart-", ".spl")
        try {
            file.writeText("smart one")
            val cache = NativeSmartModelCache(maxAgeMs = 5_000L)
            var parses = 0
            fun load(): NativeModel = NativeModel(++parses)
            val first = cache.getOrLoad(file, NativeModel::class.java, 100L) {
                load()
            }
            val reused = cache.getOrLoad(file, NativeModel::class.java, 200L) {
                load()
            }
            assertSame(first, reused)
            assertEquals(1, parses)
            val expired = cache.getOrLoad(
                file, NativeModel::class.java, 5_101L
            ) { load() }
            assertNotSame(first, expired)
            assertEquals(2, parses)
        } finally {
            file.delete()
        }
    }

    @Test fun changedFileOrNativeModelTypeCannotUseOldModel() {
        val file = File.createTempFile("gonesmart-native-smart-", ".spl")
        try {
            file.writeText("abc")
            val cache = NativeSmartModelCache()
            val initial = cache.getOrLoad(
                file, NativeModel::class.java, 0
            ) { NativeModel(1) }
            file.writeText("abcd – changed content")
            val changed = cache.getOrLoad(
                file, NativeModel::class.java, 1
            ) { NativeModel(2) }
            assertNotSame(initial, changed)
            assertEquals(2, (changed as NativeModel).version)
            val other = cache.getOrLoad(
                file, OtherModel::class.java, 2
            ) { OtherModel() }
            assertEquals(OtherModel::class.java, other?.javaClass)
            file.delete()
            assertNull(cache.getOrLoad(
                file, OtherModel::class.java, 3
            ) { OtherModel() })
        } finally {
            file.delete()
        }
    }
}
