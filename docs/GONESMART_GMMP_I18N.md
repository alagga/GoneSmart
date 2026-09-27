# GoneSmart-only strings injected into GMMP

**Scope:** Keep the GoneSmart companion app English-only. Most text injected into GoneMAD Music Player must continue to come directly from the installed GMMP APK at runtime, using the actual GMMP Activity locale. This file documents the maintainer-approved exception for labels GMMP itself **does not** translate.

## Source of truth

`app/src/main/java/io/github/alagga/gonesmart/GoneSmartGmmpStrings.kt` is the ONE central translation source for new GoneSmart-only GMMP action text. Each missing native phrase should receive one semantic key/function and explicit locale mappings in that file. Don't copy existing GMMP resource translations into it; fetch those using the host GMMP `Resources.getIdentifier` / `getString` instead. This avoids parallel/inconsistent translations for the normal playlist/dialog actions already implemented upstream.

The initial phrase `move` is used in the normal Playlists-tab overflow/context menus and the actual native contextual ActionMode while choosing a playlist MOVE destination. The maintainer explicitly requested German **Verschieben** and rejected the former language-independent but misleading `→ Ordner` workaround.

## Verified installed GMMP 4.2.0 locale inventory

The supplied tested original `base.apk` resource table exposes **76** distinct two-letter language codes:

`af, am, ar, as, az, be, bg, bn, bs, ca, cs, da, de, el, en, es, et, eu, fa, fi, fr, gl, gu, he, hi, hr, hu, hy, id, in, is, it, iw, ja, ka, kk, km, kn, ko, ky, lo, lt, lv, mk, ml, mn, mr, ms, my, nb, ne, nl, or, pa, pl, pt, ro, ru, si, sk, sl, sq, sr, sv, sw, ta, te, th, tl, tr, uk, ur, uz, vi, zh, zu`.

After normalizing Android's legacy aliases `in → id` and `iw → he`, this is **74** unique language translations. The separate resource variants `en-GB`, `en-AU`, `en-CA`, `en-IN`, `en-XC`, `es-US`, `fr-CA`, `pt-BR`, `pt-PT`, `zh-CN`, `zh-HK` and `zh-TW` inherit a parent language where appropriate; Traditional Chinese `zh-HK`, `zh-TW` and `zh-Hant` are handled explicitly. The app chooses the first current locale from **GMMP's own Context**, not the device JVM default and not the English-only GoneSmart companion's resources.

Unit test `GoneSmartGmmpStringsTest.includesEveryLanguageCodeFromTheVerifiedGmmp420ResourceTable` checks all 76 installed language codes have a translation and guards against dropping one while refactoring; the other tests cover basic translations, legacy locale aliases, regional/script routing and unknown-language fallback.

## Fallbacks and future additions

Unknown language codes (for example a future GMMP version adds a language) fall back to English **Move** and are reported through the existing bounded `NATIVE MOVE LABEL` diagnostic with an explicit GoneSmart-i18n fallback source. They do NOT reuse an unrelated native GMMP command such as Copy/Rename. This distinction is important because playlist Move deletes the original file through GMMP's original DeletePlaylistFileWorker after durable staging and native-index verification; labeling it Copy would be incorrect.

These new translations were written for feature testing and must be reviewed by native speakers where possible before a public multilingual release, especially less common GMMP language variants and imperative/menu-style phrasing. Passing compilation or checking coverage is NOT equivalent to having independently validated every locale's idiomatic grammar. Keep one central file as the expansion point for future phrases that the installed GMMP APK genuinely lacks; document every newly introduced semantic key and coverage test here.
