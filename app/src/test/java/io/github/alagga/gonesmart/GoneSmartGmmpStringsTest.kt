package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GoneSmartGmmpStringsTest {
    @Test
    fun includesEveryLanguageCodeFromTheVerifiedGmmp420ResourceTable() {
        // Original tested base.apk exposes 76 distinct locale language
        // codes, including the old Android aliases in/iw for id/he.
        val actualGmmp420 = (
            "af,am,ar,as,az,be,bg,bn,bs,ca,cs,da,de,el,en,es," +
                "et,eu,fa,fi,fr,gl,gu,he,hi,hr,hu,hy,id,in,is,it,iw,ja," +
                "ka,kk,km,kn,ko,ky,lo,lt,lv,mk,ml,mn,mr,ms,my,nb,ne,nl," +
                "or,pa,pl,pt,ro,ru,si,sk,sl,sq,sr,sv,sw,ta,te,th,tl,tr," +
                "uk,ur,uz,vi,zh,zu"
            ).split(",")
        assertEquals(76, actualGmmp420.size)
        for (raw in actualGmmp420) {
            assertTrue(
                "GMMP 4.2.0 language is missing: $raw",
                GoneSmartGmmpStrings.hasMoveTranslation(Locale(raw))
            )
        }
        assertEquals(
            74,
            GoneSmartGmmpStrings.translatedLanguageCodes.size
        )
    }

    @Test
    fun translatedMissingNativeMoveVerbFollowsInstalledGmmpLocale() {
        assertEquals(
            "Verschieben",
            GoneSmartGmmpStrings.move(Locale.GERMANY)
        )
        assertEquals(
            "Move",
            GoneSmartGmmpStrings.move(Locale.UK)
        )
        assertEquals(
            "Déplacer",
            GoneSmartGmmpStrings.move(Locale.CANADA_FRENCH)
        )
        assertEquals(
            "Mover",
            GoneSmartGmmpStrings.move(
                Locale.forLanguageTag("es-US")
            )
        )
        assertEquals(
            "Переместить",
            GoneSmartGmmpStrings.move(
                Locale.forLanguageTag("ru-RU")
            )
        )
        assertEquals(
            "移動",
            GoneSmartGmmpStrings.move(
                Locale.forLanguageTag("zh-TW")
            )
        )
        assertEquals(
            "移动",
            GoneSmartGmmpStrings.move(
                Locale.forLanguageTag("zh-CN")
            )
        )
        assertEquals(
            "移動",
            GoneSmartGmmpStrings.move(
                Locale.forLanguageTag("zh-Hant")
            )
        )
    }

    @Test
    fun legacyAndroidLanguageCodesAndUnsupportedLocaleFallback() {
        assertEquals(
            "העבר",
            GoneSmartGmmpStrings.move(Locale("iw", "IL"))
        )
        assertEquals(
            "Pindahkan",
            GoneSmartGmmpStrings.move(Locale("in", "ID"))
        )
        assertEquals(
            "Flytt",
            GoneSmartGmmpStrings.move(
                Locale.forLanguageTag("no-NO")
            )
        )
        val unsupported = Locale.forLanguageTag("xx-ZZ")
        assertFalse(GoneSmartGmmpStrings.hasMoveTranslation(unsupported))
        assertEquals("Move", GoneSmartGmmpStrings.move(unsupported))
        assertTrue(
            GoneSmartGmmpStrings.hasMoveTranslation(
                Locale.GERMANY
            )
        )
    }
}
