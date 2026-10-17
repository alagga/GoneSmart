package io.github.alagga.gonesmart

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpEqualizerPresetParserTest {

    @Test
    fun parsesFrequencyKeyedTenBandPresetWithoutObservation() {
        val text = """
            preset_name="Club"
            pre=1.5
            band_32=-1
            band_64=-0.5
            band_128=0
            band_256=1
            band_512=2
            band_1000=3
            band_2000=2
            band_4000=1
            band_8000=0
            band_16000=-1
        """.trimIndent()

        val parsed = GmmpEqualizerPresetParser.parse("ignored.eq", text.toByteArray())!!
        assertEquals("Club", parsed.name)
        assertEquals("1.5", parsed.shape["eq.pre_db"])
        assertEquals("-1", parsed.shape["eq.band_32_db"])
        assertEquals("3", parsed.shape["eq.band_1000_db"])
        assertEquals(11, parsed.shape.size)
    }

    @Test
    fun parsesActualNativeFlatPresetFormat() {
        val text = """
            Flat
            0.0
            10
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
        """.trimIndent()

        val parsed = GmmpEqualizerPresetParser.parse("Flat.eq", text.toByteArray())!!
        assertEquals("Flat", parsed.name)
        assertEquals("0", parsed.shape["eq.pre_db"])
        assertEquals(11, parsed.shape.size)
        assertTrue(parsed.shape.values.all { it == "0" })
    }

    @Test
    fun parsesActualNativeDancePresetFormat() {
        val text = """
            Dance
            -3.0
            10
            6.0
            6.0
            4.0
            1.5
            0.0
            0.0
            -4.0
            -5.0
            -5.5
            0.0
        """.trimIndent()

        val parsed = GmmpEqualizerPresetParser.parse("Dance.eq", text.toByteArray())!!
        assertEquals("Dance", parsed.name)
        assertEquals("-3", parsed.shape["eq.pre_db"])
        assertEquals("6", parsed.shape["eq.band_32_db"])
        assertEquals("-5.5", parsed.shape["eq.band_8000_db"])
        assertEquals("0", parsed.shape["eq.band_16000_db"])
    }

    @Test
    fun parsesActualNativeCustomPresetFormat() {
        val text = """
            Neues Preset
            -2.0
            10
            12.0
            12.0
            0.0
            0.0
            0.0
            -12.0
            -12.0
            -12.0
            -12.0
            12.0
        """.trimIndent()

        val parsed = GmmpEqualizerPresetParser.parse("Neues Preset.eq", text.toByteArray())!!
        assertEquals("Neues Preset", parsed.name)
        assertEquals("-2", parsed.shape["eq.pre_db"])
        assertEquals("12", parsed.shape["eq.band_32_db"])
        assertEquals("-12", parsed.shape["eq.band_1000_db"])
        assertEquals("12", parsed.shape["eq.band_16000_db"])
    }

    @Test
    fun nativePresetRejectsDifferentBandCountInsteadOfGuessing() {
        val text = """
            Five Band
            0.0
            5
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
            0.0
        """.trimIndent()

        assertNull(GmmpEqualizerPresetParser.parse("Five Band.eq", text.toByteArray()))
    }

    @Test
    fun parsesNativeUtf16LittleEndianPresetWithoutBom() {
        val text = """
            Club
            0
            10
            1
            2
            3
            4
            5
            4
            3
            2
            1
            0
        """.trimIndent()

        val parsed = GmmpEqualizerPresetParser.parse(
            "wrong-name.eq",
            text.toByteArray(StandardCharsets.UTF_16LE)
        )!!

        assertEquals("Club", parsed.name)
        assertEquals("0", parsed.shape["eq.pre_db"])
        assertEquals("5", parsed.shape["eq.band_512_db"])
    }

    @Test
    fun parsesStrictCompactElevenValuePreset() {
        val parsed = GmmpEqualizerPresetParser.parse(
            "Soft.preset",
            "0 -1 -1 0 1 2 2 1 0 -1 -2".toByteArray()
        )!!

        assertEquals("Soft", parsed.name)
        assertEquals("0", parsed.shape["eq.pre_db"])
        assertEquals("-2", parsed.shape["eq.band_16000_db"])
    }

    @Test
    fun parsesNativeOrderedBandCountPrefixedNumericCurve() {
        val parsed = GmmpEqualizerPresetParser.parse(
            "Rock.eq",
            "0\n10\n1\n2\n3\n4\n5\n4\n3\n2\n1\n0".toByteArray()
        )!!

        assertEquals("Rock", parsed.name)
        assertEquals("0", parsed.shape["eq.pre_db"])
        assertEquals("5", parsed.shape["eq.band_512_db"])
        assertEquals(11, parsed.shape.size)
    }

    @Test
    fun keepsCompatibilityWithLeadingBandCountNumericCurve() {
        val parsed = GmmpEqualizerPresetParser.parse(
            "Rock.eq",
            "10\n0\n1\n2\n3\n4\n5\n4\n3\n2\n1\n0".toByteArray()
        )!!

        assertEquals("Rock", parsed.name)
        assertEquals("0", parsed.shape["eq.pre_db"])
        assertEquals("5", parsed.shape["eq.band_512_db"])
    }

    @Test
    fun parsesDecimalDotCsvPreset() {
        val parsed = GmmpEqualizerPresetParser.parse(
            "Dance.eq",
            "0.0,-1.5,-1.0,0.0,1.0,2.0,3.0,2.0,1.0,0.0,-1.0".toByteArray()
        )!!

        assertEquals("Dance", parsed.name)
        assertEquals("-1.5", parsed.shape["eq.band_32_db"])
        assertEquals("3", parsed.shape["eq.band_1000_db"])
    }

    @Test
    fun rejectsIncompleteOrOutOfRangePresetRatherThanGuessing() {
        assertNull(GmmpEqualizerPresetParser.parse("broken", "pre=0 band_32=1".toByteArray()))
        assertNull(
            GmmpEqualizerPresetParser.parse(
                "broken",
                "0 0 0 0 0 0 0 0 0 0 99".toByteArray()
            )
        )
    }

    @Test
    fun compactPresetContainsExactlyCompleteShape() {
        val parsed = GmmpEqualizerPresetParser.parse(
            "Live",
            "0,0,0,0,0,0,0,0,0,0,0".toByteArray()
        )!!
        assertEquals(GoneSmartAudioProfiles.equalizerShapeFieldIds.toSet(), parsed.shape.keys)
        assertTrue(parsed.shape.values.all { it == "0" })
    }
}
