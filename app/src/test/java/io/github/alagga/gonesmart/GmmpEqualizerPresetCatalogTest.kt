package io.github.alagga.gonesmart

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpEqualizerPresetCatalogTest {

    @Test
    fun readsTenBandSubtreeWithoutScanningOtherExplicitBandCounts() {
        val root = Files.createTempDirectory("gmmp-eq-preset").toFile()
        try {
            val five = root.resolve("5 bands").apply { mkdirs() }
            val ten = root.resolve("10").apply { mkdirs() }
            five.resolve("Wrong.eq").writeText("0 0 0 0 0 0")
            ten.resolve("Flat.eq").writeText("0 0 0 0 0 0 0 0 0 0 0")
            ten.resolve("Rock.eq").writeText("0 1 2 3 4 5 4 3 2 1 0")

            val files = GmmpEqualizerPresetCatalog.presetFilesForDirectory(root, expectedBandCount = 10)
            assertEquals(listOf("Flat.eq", "Rock.eq"), files.map { it.name }.sorted())
            assertTrue(files.all { it.parentFile.name == "10" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsFlatUnscopedPresetFilesAlongsideTenBandFamily() {
        val root = Files.createTempDirectory("gmmp-eq-preset-flat").toFile()
        try {
            root.resolve("Custom.eq").writeText("0 0 0 0 0 0 0 0 0 0 0")
            val ten = root.resolve("bands_10").apply { mkdirs() }
            ten.resolve("Club.eq").writeText("0 1 1 0 0 0 0 0 0 0 0")

            val files = GmmpEqualizerPresetCatalog.presetFilesForDirectory(root, expectedBandCount = 10)
            assertEquals(setOf("Custom.eq", "Club.eq"), files.map { it.name }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsNumericBandCountInCommonDirectoryNames() {
        assertEquals(10, GmmpEqualizerPresetCatalog.bandCountHint("10"))
        assertEquals(10, GmmpEqualizerPresetCatalog.bandCountHint("10 bands"))
        assertEquals(7, GmmpEqualizerPresetCatalog.bandCountHint("bands_7"))
        assertEquals(null, GmmpEqualizerPresetCatalog.bandCountHint("presets"))
    }
}
