# Multi-playlist selection (GMMP 4.2.0)

GoneSmart's optional **UI → Multi-playlist selection** feature is implemented and available in release builds. It is independent of Smart DJ; normal single-playlist taps remain native GMMP operations. The implementation uses GMMP's own playlist writer and never edits playlist files directly.

## User flow

1. Enable **Multi-playlist selection** in GoneSmart's **UI** tab; enable and scope the libxposed module to GMMP.
2. In GMMP's **Add to Playlist** dialog, long-press a destination to enter multiple selection.
3. Tap other playlists to select or deselect them. The action bar displays GMMP's own localized selection count; selected rows track GMMP's changing dynamic theme and stay correctly highlighted when scrolling.
4. Tap the confirmation checkmark (with GoneSmart's lilac sparkle) to add all source files to every selected destination. One native-style summary Toast reports the file count and the number of successful destinations.
5. Back or the selection action bar's back arrow cancels the selection without an extra screen navigation. Outside selection mode, GMMP's create-playlist plus button and normal one-playlist tap behave normally.

## Verified native integration points

Tested against GMMP **4.2.0**; these are obfuscated implementation details, not a stable API:

- `bo3.I3()` initializes the picker; `bo3.D1()` exposes its RecyclerView and `bo3.k2()` exposes its FAB.
- `zn3` binds `jo3` row holders; each bound `xn3` model contains the playlist file path in its `q` field. Stable paths, never recycled views/adapter positions, identify selected destinations.
- `io3(ho3, boolean)` captures the original GMMP source selection. `io3.r(Context, ie0)` performs each native playlist-add action using a `jo3` holder.
- A batch tracks the native completion callbacks and ensures duplicate picker-close events cannot navigate back multiple screens. Per-destination native Toasts are replaced with one aggregate result for GoneSmart multi-add only.
- The feature retrieves translations from GMMP's live resources (`num_selected`, `add_to_playlist_toast`, `playlist` and `playlists`) rather than keeping its own translation table.
- GMMP's Aesthetic primary/accent and FAB color observables supply live selection and sparkle color changes when the active album or theme changes.

## Settings and lifecycle

`KEY_MULTI_PLAYLIST` has its own `GoneSmartSettingsKeys` entry and lives in the **UI** tab. It remains available with Smart DJ disabled. Existing Xposed/module settings are synchronized through remote preferences. After installing a new build or changing module scope, restart the GMMP process.

## Testing

Use disposable playlists. Verify long-press, toggle, scroll down/up, back cancellation, normal single-playlist tap, plus/create mode, multi-file adds, a dynamic-theme track change during selection and successful return to the launching GMMP screen. The aggregate Toast must appear exactly once and count **source files** and **successful playlist destinations**.

For debugging, filter Logcat to `GoneSmartPlaylist` and inspect `MULTI PICKER`, `MULTI SELECT`, `MULTI PALETTE`, `MULTI NAV`, `MULTI NATIVE ADD`, `MULTI TOAST` and `MULTI CONFIRM`. Paths in logs may reveal local filenames: redact them before publishing logs.

This implementation targets GMMP 4.2.0 and will need revalidation if GMMP changes its internal obfuscated classes or its localized string resources.
