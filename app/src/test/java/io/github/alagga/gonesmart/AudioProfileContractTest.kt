package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfileContractTest {

    @Test
    fun equalizerIsOneWholeProfileOverride() {
        val equalizer = GoneSmartAudioProfiles.equalizerGroup
        val ids = equalizer.fields.map { it.id }

        assertEquals("equalizer", equalizer.id)
        assertTrue("eq.enabled" in ids)
        assertTrue("eq.preset" in ids)
        assertTrue("eq.pre_db" in ids)
        assertTrue("eq.band_32_db" in ids)
        assertTrue("eq.band_16000_db" in ids)
        assertEquals("override.bt_demo.equalizer", GoneSmartAudioProfiles.overrideKey("bt_demo", equalizer.id))
    }

    @Test
    fun compoundEffectsUseOneOverridePerNativeCard() {
        val bass = GoneSmartAudioProfiles.group("bass_boost")!!
        assertEquals(setOf("bass.enabled", "bass.strength_percent"), bass.fields.map { it.id }.toSet())
        assertEquals("override.bt_demo.bass_boost", GoneSmartAudioProfiles.overrideKey("bt_demo", bass.id))

        val limiter = GoneSmartAudioProfiles.group("limiter")!!
        assertEquals(
            setOf("limiter.enabled", "limiter.attack_ms", "limiter.release_ms"),
            limiter.fields.map { it.id }.toSet()
        )
    }

    @Test
    fun equalizerValuesNormalizeAndStayInsideDocumentedRange() {
        val field = GoneSmartAudioProfiles.field("eq.band_32_db")!!
        assertEquals("12", field.normalize("99"))
        assertEquals("-12", field.normalize("-99"))
        assertEquals("1.3", field.normalize("1.26"))
        assertEquals("-3.9", field.normalize("-3,9"))
    }

    @Test
    fun customIsAStateNotASelectablePreset() {
        val preset = GoneSmartAudioProfiles.field("eq.preset")!!
        assertTrue("Club" in preset.choices)
        assertTrue("Soft" in preset.choices)
        assertTrue("Live" in preset.choices)
        assertFalse(GoneSmartAudioProfiles.CUSTOM_PRESET in preset.choices)
        assertTrue(GoneSmartAudioProfiles.isEqualizerShapeField("eq.pre_db"))
        assertTrue(GoneSmartAudioProfiles.isEqualizerShapeField("eq.band_16000_db"))
        assertFalse(GoneSmartAudioProfiles.isEqualizerShapeField("eq.enabled"))
    }

    @Test
    fun nativePresetSnapshotKeysAreStableAndSeparated() {
        val clubPre = GoneSmartAudioProfiles.nativePresetValueKey("Club", "eq.pre_db")
        val clubBand = GoneSmartAudioProfiles.nativePresetValueKey("Club", "eq.band_32_db")
        val softPre = GoneSmartAudioProfiles.nativePresetValueKey("Soft", "eq.pre_db")

        assertEquals(clubPre, GoneSmartAudioProfiles.nativePresetValueKey(" club ", "eq.pre_db"))
        assertNotEquals(clubPre, clubBand)
        assertNotEquals(clubPre, softPre)
        assertTrue(clubPre.startsWith("native_preset."))
    }

    @Test
    fun bluetoothRouteIdentityUsesAddressRatherThanSharedModelName() {
        val first = GoneSmartAudioProfiles.bluetoothRouteId(8, "00:11:22:33:44:55", "Esinkin BT Adapter")
        val second = GoneSmartAudioProfiles.bluetoothRouteId(8, "66:77:88:99:AA:BB", "Esinkin BT Adapter")
        val otherAudioType = GoneSmartAudioProfiles.bluetoothRouteId(26, "00:11:22:33:44:55", "Renamed receiver")

        assertNotEquals(first, second)
        assertEquals(first, otherAudioType)
        assertTrue(first.startsWith("bt_"))
    }

    @Test
    fun bluetoothRouteIdentityUsesLegacyLabelOnlyWhenAddressIsHidden() {
        val fallback = GoneSmartAudioProfiles.bluetoothRouteId(8, "", "Esinkin BT Adapter")
        val sameName = GoneSmartAudioProfiles.bluetoothRouteId(26, null, "esinkin bt adapter")
        val knownAddress = GoneSmartAudioProfiles.bluetoothRouteId(8, "00:11:22:33:44:55", "Esinkin BT Adapter")

        assertEquals(fallback, sameName)
        assertNotEquals(fallback, knownAddress)
    }

    @Test
    fun bluetoothRouteIdentityFallsBackWhenNoLabelExists() {
        val first = GoneSmartAudioProfiles.bluetoothRouteId(8, "00:11:22:33:44:55", null)
        val same = GoneSmartAudioProfiles.bluetoothRouteId(8, "00:11:22:33:44:55", "")
        val other = GoneSmartAudioProfiles.bluetoothRouteId(8, "66:77:88:99:AA:BB", null)

        assertEquals(first, same)
        assertNotEquals(first, other)
    }

    @Test
    fun routeMetadataParserIgnoresActiveRouteMarker() {
        val ids = GoneSmartAudioProfiles.routeIdsFromKeys(
            setOf(
                GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE,
                GoneSmartAudioProfiles.routeLabelKey("local"),
                GoneSmartAudioProfiles.routeLabelKey("bt_one"),
                GoneSmartAudioProfiles.routeLastSeenKey("bt_two"),
                "override.bt_one.equalizer"
            )
        )

        assertEquals(setOf("local", "bt_one", "bt_two"), ids)
        assertFalse("active" in ids)
    }
}
