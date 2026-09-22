# Multi-playlist selection: diagnostic phase

This is a development-only investigation for GMMP 4.2.0 on the
`feature/multi-playlist-add` branch. The multi-select UI is **not implemented yet**.

## Intended behavior

- Normal playlist tap: GMMP's existing single-playlist behavior.
- First long press on a playlist: enter GoneSmart multi-selection.
- Further taps: select/deselect target playlists.
- While selecting: the existing `playlistFab` switches from `+` to a persistent
  pink-sparkle `✓` confirmation button.
- Back: cancel multi-selection without leaving the playlist picker.
- Confirm: add all the already-selected GMMP source tracks to each chosen target
  playlist, preferably via GMMP's own native add operation.

No direct playlist-file writes are planned.

## What we know from existing GMMP logs and the supplied APK

- Opening Add to Playlist creates obfuscated fragments `do3` and `bo3`.
- The list presenter `go3` queries playlist file name, URI and ID.
- The native plus button has resource ID `gonemad.gmmp:id/playlistFab` and
  opens a playlist-name input dialog (`md_input_message`).
- The GMMP 4.2.0 APK exposes:
  - `bo3.k2()`: returns a `FloatingActionButton`.
  - `bo3.D1()`: returns a `RecyclerView`.
  - `bo3.E2(Boolean)`: relevant candidate to observe FAB visibility handling.
  - `go3.y2()`: relevant candidate to observe list refreshes.

The exact native handler for adding the selected source track(s) to one target
playlist still needs to be verified. Do not assume an obfuscated method name's
meaning solely from its signature.

## Build and log

The diagnostics run **only in DEBUG builds**. They observe GMMP's native
FAB, playlist RecyclerView, listener classes (when Android permits read-only
inspection), and selected native picker callback invocations. No clicks are
intercepted, no selections are changed, and no playlist data is written.

1. In Android Studio, make sure the checked-out branch is
   `feature/multi-playlist-add`; use **Git > Pull**.
2. Build/install the **debug** variant of GoneSmart and confirm the module is
   enabled for GMMP in Vector/LSPosed. Force-stop and restart GMMP.
3. If the currently installed GoneSmart is the GitHub-signed release APK, a
   locally debug-signed APK **cannot update it in place**. Do not uninstall
   until any GoneSmart settings you need are backed up. Use a compatible
   locally signed build or a private test release signed with the same key.
4. Filter Android Studio Logcat with `package:gonemad.gmmp`, or use:
   `adb logcat -s GoneSmartPlaylist:I GoneSmart:I '*:S'`.
5. Open Add to Playlist through your Now Playing gesture with one **test**
   track. Let the list settle; scroll down/up to reveal the native FAB
   visibility behavior. Tap `+` and back out of the name dialog.
6. Select a **test** playlist and observe the normal add operation.
7. Repeat the test starting from GMMP's multi-track selection.

Look for:

- `DIAGNOSTICS READY`
- `HOOK READY`
- `FAB FOUND`, `FAB STATE` (including scroll-triggered changes)
- `LIST FOUND`, `LIST STATE`, `FIRST ROW`
- `PICKER CALL` and `PRESENTER CALL`

When sharing logs, include the timestamps around the actual playlist tap and
existing GMMP logs as well as the `GoneSmartPlaylist` lines. Redact unrelated
app logs and sensitive file paths if needed.

## Before implementing multi-selection

Confirm the native add callback, identify the model representing target
playlists, and check the lifetime of GMMP's already-selected source tracks.
Once confirmed, implement multi-selection with a persistent FAB and Back
cancellation while preserving the original plus/create flow outside
multi-select mode.
