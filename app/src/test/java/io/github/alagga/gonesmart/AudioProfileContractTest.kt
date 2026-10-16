package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfileContractTest {

    @Test
    fun exposesEveryEqAndEffectControlAsAnIndependentSetting() {
        val ids = GoneSmartAudioProfiles.allSettings.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        assertTrue("eq.band_32_db" in ids)
        assertTrue("eq.band_16000_db" in ids)
        assertTrue("bass.enabled" in ids)
        assertTrue("bass.strength_percent" in ids)
        assertTrue("limiter.attack_ms" in ids)
        assertTrue("amplifier.gain_db" in ids)
    }

    @Test
    fun equalizerValuesNormalizeAndStayInsideNativeDocumentedRange() {
        val setting = GoneSmartAudioProfiles.setting("eq.band_32_db")!!
        assertEquals("12", setting.normalize("99"))
        assertEquals("-12", setting.normalize("-99"))
        assertEquals("1.3", setting.normalize("1.26"))
        assertEquals("-3.9", setting.normalize("-3,9"))
    }

    @Test
    fun sparseOverrideKeysDoNotRequireWholeProfiles() {
        val bass = GoneSmartAudioProfiles.overrideKey("bt_demo", "bass.strength_percent")
        val band = GoneSmartAudioProfiles.overrideKey("bt_demo", "eq.band_32_db")
        assertEquals("override.bt_demo.bass.strength_percent", bass)
        assertEquals("override.bt_demo.eq.band_32_db", band)
        assertNotEquals(bass, band)
    }

    @Test
    fun bluetoothRouteIdentityIsStableButSeparatesDevices() {
        val first = GoneSmartAudioProfiles.bluetoothRouteId(8, "00:11:22:33:44:55", "Headphones")
        val same = GoneSmartAudioProfiles.bluetoothRouteId(8, "00:11:22:33:44:55", "Renamed headphones")
        val other = GoneSmartAudioProfiles.bluetoothRouteId(8, "66:77:88:99:AA:BB", "Headphones")

        assertEquals(first, same)
        assertNotEquals(first, other)
        assertTrue(first.startsWith("bt_"))
    }

    @Test
    fun routeMetadataParserIgnoresActiveRouteMarker() {
        val ids = GoneSmartAudioProfiles.routeIdsFromKeys(
            setOf(
                GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE,
                GoneSmartAudioProfiles.routeLabelKey("local"),
                GoneSmartAudioProfiles.routeLabelKey("bt_one"),
                GoneSmartAudioProfiles.routeLastSeenKey("bt_two"),
                "override.bt_one.eq.enabled"
            )
        )

        assertEquals(setOf("local", "bt_one", "bt_two"), ids)
        assertFalse("active" in ids)
    }
}
