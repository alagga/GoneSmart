package io.github.alagga.gonesmart

import java.util.Locale

/**
 * GoneSmart-only phrases that are NOT present in the installed GMMP APK.
 * Every other injected label must continue to resolve GMMP's own resource.
 *
 * Use the HOST GMMP Activity's current Resources.configuration locale; the
 * companion app intentionally remains English-only. Do not use JVM default
 * Locale here: Android/GMMP may run in a different app-specific language.
 *
 * Language-only entries follow the installed GMMP 4.2.0 resource-table
 * locales. Regional variants inherit their parent except where an actual
 * writing-system difference changes the translation.
 */
internal object GoneSmartGmmpStrings {
    private val moveByLanguage = mapOf(
        "af" to "Skuif",
        "am" to "አንቀሳቅስ",
        "ar" to "نقل",
        "as" to "স্থানান্তৰ কৰক",
        "az" to "Köçür",
        "be" to "Перамясціць",
        "bg" to "Премести",
        "bn" to "সরান",
        "bs" to "Premjesti",
        "ca" to "Mou",
        "cs" to "Přesunout",
        "da" to "Flyt",
        "de" to "Verschieben",
        "el" to "Μετακίνηση",
        "en" to "Move",
        "es" to "Mover",
        "et" to "Teisalda",
        "eu" to "Mugitu",
        "fa" to "انتقال",
        "fi" to "Siirrä",
        "fr" to "Déplacer",
        "gl" to "Mover",
        "gu" to "ખસેડો",
        "he" to "העבר",
        "hi" to "स्थानांतरित करें",
        "hr" to "Premjesti",
        "hu" to "Áthelyezés",
        "hy" to "Տեղափոխել",
        "id" to "Pindahkan",
        "is" to "Færa",
        "it" to "Sposta",
        "ja" to "移動",
        "ka" to "გადატანა",
        "kk" to "Жылжыту",
        "km" to "ផ្លាស់ទី",
        "kn" to "ಸ್ಥಳಾಂತರಿಸಿ",
        "ko" to "이동",
        "ky" to "Жылдыруу",
        "lo" to "ຍ້າຍ",
        "lt" to "Perkelti",
        "lv" to "Pārvietot",
        "mk" to "Премести",
        "ml" to "നീക്കുക",
        "mn" to "Зөөх",
        "mr" to "हलवा",
        "ms" to "Alihkan",
        "my" to "ရွှေ့ရန်",
        "nb" to "Flytt",
        "ne" to "सार्नुहोस्",
        "nl" to "Verplaatsen",
        "or" to "ସ୍ଥାନାନ୍ତର କରନ୍ତୁ",
        "pa" to "ਹਿਲਾਓ",
        "pl" to "Przenieś",
        "pt" to "Mover",
        "ro" to "Mută",
        "ru" to "Переместить",
        "si" to "ගෙනයන්න",
        "sk" to "Presunúť",
        "sl" to "Premakni",
        "sq" to "Zhvendos",
        "sr" to "Премести",
        "sv" to "Flytta",
        "sw" to "Hamisha",
        "ta" to "நகர்த்து",
        "te" to "తరలించు",
        "th" to "ย้าย",
        "tl" to "Ilipat",
        "tr" to "Taşı",
        "uk" to "Перемістити",
        "ur" to "منتقل کریں",
        "uz" to "Ko‘chirish",
        "vi" to "Di chuyển",
        "zh" to "移动",
        "zu" to "Hambisa"
    )

    /**
     * GoneSmart's virtual "Other Locations" node is NOT a physical GMMP
     * folder name. Reuse a genuine installed GMMP translation first if
     * it exists. These are fallback translations for that feature-owned
     * label; native-speaker review is still required before a broad release.
     */
    private val otherLocationsByLanguage = mapOf(
        "af" to "Ander liggings",
        "am" to "ሌሎች ቦታዎች",
        "ar" to "مواقع أخرى",
        "as" to "অন্যান্য স্থান",
        "az" to "Digər yerlər",
        "be" to "Іншыя месцы",
        "bg" to "Други местоположения",
        "bn" to "অন্যান্য অবস্থান",
        "bs" to "Druge lokacije",
        "ca" to "Altres ubicacions",
        "cs" to "Další umístění",
        "da" to "Andre placeringer",
        "de" to "Andere Speicherorte",
        "el" to "Άλλες τοποθεσίες",
        "en" to "Other Locations",
        "es" to "Otras ubicaciones",
        "et" to "Muud asukohad",
        "eu" to "Beste kokapen batzuk",
        "fa" to "مکان‌های دیگر",
        "fi" to "Muut sijainnit",
        "fr" to "Autres emplacements",
        "gl" to "Outras localizacións",
        "gu" to "અન્ય સ્થાનો",
        "he" to "מיקומים אחרים",
        "hi" to "अन्य स्थान",
        "hr" to "Ostale lokacije",
        "hu" to "Egyéb helyek",
        "hy" to "Այլ տեղադրություններ",
        "id" to "Lokasi Lain",
        "is" to "Aðrar staðsetningar",
        "it" to "Altre posizioni",
        "ja" to "その他の場所",
        "ka" to "სხვა მდებარეობები",
        "kk" to "Басқа орындар",
        "km" to "ទីតាំងផ្សេងទៀត",
        "kn" to "ಇತರ ಸ್ಥಳಗಳು",
        "ko" to "다른 위치",
        "ky" to "Башка жерлер",
        "lo" to "ສະຖານທີ່ອື່ນ",
        "lt" to "Kitos vietos",
        "lv" to "Citas atrašanās vietas",
        "mk" to "Други локации",
        "ml" to "മറ്റ് സ്ഥാനങ്ങൾ",
        "mn" to "Бусад байршлууд",
        "mr" to "इतर ठिकाणे",
        "ms" to "Lokasi Lain",
        "my" to "အခြားတည်နေရာများ",
        "nb" to "Andre plasseringer",
        "ne" to "अन्य स्थानहरू",
        "nl" to "Andere locaties",
        "or" to "ଅନ୍ୟ ସ୍ଥାନ",
        "pa" to "ਹੋਰ ਟਿਕਾਣੇ",
        "pl" to "Inne lokalizacje",
        "pt" to "Outros locais",
        "ro" to "Alte locații",
        "ru" to "Другие расположения",
        "si" to "වෙනත් ස්ථාන",
        "sk" to "Iné umiestnenia",
        "sl" to "Druge lokacije",
        "sq" to "Vendndodhje të tjera",
        "sr" to "Друге локације",
        "sv" to "Andra platser",
        "sw" to "Maeneo mengine",
        "ta" to "பிற இடங்கள்",
        "te" to "ఇతర స్థానాలు",
        "th" to "ตำแหน่งอื่น",
        "tl" to "Iba pang lokasyon",
        "tr" to "Diğer konumlar",
        "uk" to "Інші розташування",
        "ur" to "دیگر مقامات",
        "uz" to "Boshqa joylar",
        "vi" to "Vị trí khác",
        "zh" to "其他位置",
        "zu" to "Ezinye izindawo"
    )

    internal val translatedOtherLocationsLanguageCodes: Set<String>
        get() = otherLocationsByLanguage.keys

    fun otherLocations(locale: Locale): String {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        return otherLocationsByLanguage[language]
            ?: otherLocationsByLanguage.getValue("en")
    }

    fun hasOtherLocationsTranslation(locale: Locale): Boolean {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        return otherLocationsByLanguage.containsKey(language)
    }

    /** Explicit audit contract for the installed GMMP 4.2.0 locales. */
    internal val translatedLanguageCodes: Set<String>
        get() = moveByLanguage.keys

    /** Actual GMMP 4.2.0 resources also include zh-HK and zh-TW. */
    fun move(locale: Locale): String {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        if (language == "zh" && (
                locale.country.equals("TW", ignoreCase = true) ||
                locale.country.equals("HK", ignoreCase = true) ||
                locale.script.equals("Hant", ignoreCase = true)
            )
        ) return "移動"
        return moveByLanguage[language] ?: moveByLanguage.getValue("en")
    }

    fun hasMoveTranslation(locale: Locale): Boolean {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        return moveByLanguage.containsKey(language)
    }
}
