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
