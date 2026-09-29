# GoneSmart-only strings injected into GMMP

**Scope:** Keep the GoneSmart companion app English-only. Most text injected into GoneMAD Music Player must continue to come directly from the installed GMMP APK at runtime, using the actual GMMP Activity locale. This file documents GoneSmart-owned labels that the installed GMMP does not translate, with native GMMP resources always taking priority.

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


## 2026-09-28 source-level localization audit and second necessary label

The initial text audit found a **second exception** that the earlier documentation missed: the virtual playlist group named **Other Locations** was a hardcoded English constant even in a German/other-language GMMP interface. A real physical folder of that name must of course retain its actual filesystem name. The new pure folder-index parameter `otherLocationsLabel` and host-context resolver `NativeGmmpUiText.otherLocations` resolve an installed GMMP `other_locations` or `other_locations_title` string *if available*, otherwise they use the central `GoneSmartGmmpStrings.otherLocations` locale table. This includes 74 canonical GMMP 4.2.0 language codes and the `in/iw` aliases. **Translation coverage tests are not linguistic certification**: the new non-native resource fallbacks are initial translations and require native-speaker review for a public multilingual release. Missing future locales use English. An already visible virtual node re-resolves the current host Activity locale when the native page redraws; physical folder names are never translated.

The same audit found old hardcoded **German** in the Add-picker confirm accessibility description and several debug-guard Toasts, and hardcoded **English** in folder creation/move errors, Flip confirmations/errors and Track Auto-DJ failure Toasts. These were replaced with the installed player's native selected-count/noun/`error` strings and a language-neutral symbol-only fallback for missing generic error resources. Specific technical failure details remain in the English-only GoneSmart Logs and Logcat instead of appearing as mixed-language GMMP popup text. Both picker mini-FAB icon descriptions now use GMMP's own translated `playlist`/`folder` resources. No additional custom error translation tables were introduced.

When native `track` or `auto_dj` is missing in GMMP, **Track Auto-DJ now omits its injected menu action** rather than inventing mixed-language labels. Its successful result uses the host-native `started` phrase if present; otherwise it shows the fully native composed action plus `✓`. Flip Queue and Play Flipped require the installed native `queue` / `play` title instead of hardcoded English fallbacks, and display native generic errors. The Add-picker aggregate result still composes GMMP's original `add_to_playlist_toast` with the native singular/plural `playlist(s)` terms; exact natural grammar in all languages, especially complex plural rules and RTL display, is **not** independently verified.

The Move action is still the only custom **verb** inside GMMP. Other Locations is a GoneSmart-owned **virtual node name**, not a second translated native command. Both exceptions live in the existing central source instead of scattering copied strings throughout the injected UI. Source and unit test coverage do not substitute for testing GMMP's own app-specific language override on a device.

## 2026-09-29 creation dialogs and pure GoneSmart notices

The language boundary is now explicit:

- Pure GoneSmart-owned recommendation/cache/provider notices stay useful **English prose**, because the GoneSmart product surface is English. Do not convert these notices to symbol-only pseudo-localization.
- GMMP-referential injected UI — especially Playlist/Smart-Playlist/folder creation, Add/Delete/Move/selection and related dialogs — follows the current **GMMP app locale**.
- Runtime localization is native-first: reuse an installed GMMP `R.string` whenever an exact semantic equivalent exists. Only a genuinely missing phrase is read from the single centralized `GoneSmartGmmpStrings.kt` table.
- That table must cover the complete audited GMMP 4.2.0 locale inventory (including legacy aliases) and have coverage tests. Do not introduce controller-local translation maps.

MaterialDialogs can populate input hints/buttons during or just after `show()`. Creation-dialog localization therefore performs an immediate pass plus bounded UI-thread post-show passes. GoneSmart's reused native `showNewFolderCreator` is localized through the same path, so normal Playlist folders and Smart-Playlist folders cannot diverge.
