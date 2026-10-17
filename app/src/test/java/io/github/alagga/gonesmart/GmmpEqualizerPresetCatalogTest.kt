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
            val five = root.resolve("5bands").apply { mkdirs() }
            val ten = root.resolve("10bands").apply { mkdirs() }
            five.resolve("Wrong.eq").writeText("Wrong\n0\n0\n0\n0\n0\n0")
            ten.resolve("Flat.eq").writeText("Flat\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0")
            ten.resolve("Rock.eq").writeText("Rock\n0\n1\n2\n3\n4\n5\n4\n3\n2\n1\n0")

            val files = GmmpEqualizerPresetCatalog.presetFilesForDirectory(root, expectedBandCount = 10)
            assertEquals(listOf("Flat.eq", "Rock.eq"), files.map { it.name }.sorted())
            assertTrue(files.all { it.parentFile.name == "10bands" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresAndroidEqFamilyAndEqStateBesideTenBandFamily() {
        val root = Files.createTempDirectory("gmmp-eq-preset-android").toFile()
        try {
            root.resolve("eqstate.eq").writeText("Current\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0")
            val androidEq = root.resolve("android-eq").apply { mkdirs() }
            androidEq.resolve("Club.eq").writeText("Club\n0\n1\n1\n0\n0\n0\n0\n0\n0\n0\n0")
            val ten = root.resolve("10bands").apply { mkdirs() }
            ten.resolve("Flat.eq").writeText("Flat\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0")

            val files = GmmpEqualizerPresetCatalog.presetFilesForDirectory(root, expectedBandCount = 10)
            assertEquals(listOf("Flat.eq"), files.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsRootPresetFilesAlongsideTenBandFamilyButNotRuntimeState() {
        val root = Files.createTempDirectory("gmmp-eq-preset-flat").toFile()
        try {
            root.resolve("Custom.eq").writeText("Custom\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0")
            root.resolve("eq_state.eq").writeText("State\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0")
            val ten = root.resolve("bands_10").apply { mkdirs() }
            ten.resolve("Club.eq").writeText("Club\n0\n1\n1\n0\n0\n0\n0\n0\n0\n0\n0")

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
        assertEquals(10, GmmpEqualizerPresetCatalog.bandCountHint("10bands"))
        assertEquals(7, GmmpEqualizerPresetCatalog.bandCountHint("bands_7"))
        assertEquals(null, GmmpEqualizerPresetCatalog.bandCountHint("presets"))
    }
}
