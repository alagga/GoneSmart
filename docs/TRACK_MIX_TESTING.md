# Track Mix / Titel-Mix — combined GMMP 4.2.0 phone test

Status: implementation complete on the development branch, **not yet
verified on a physical phone**. No signed public release has been
published for this new feature.

## Intended user experience

In a song's three-dot menu, **Titel-Mix** (English: **Track Mix**)
appears as the **third entry**, directly after native **Play** and
**Play next**, with GoneSmart's lilac two-star sparkle. The neighboring
menu row heights do not change.

Supported GMMP song context resources:
`menu_gm_context_track`, `menu_gm_context_queue`,
`menu_gm_context_search`, `menu_gm_context_playlist_details`,
`menu_gm_context_file_browser`, `menu_gm_context_shared`.
Native album/artist/playlist group menus are deliberately excluded.

Selecting Track Mix should:

1. Run the selected row's **native Play** callback so the correct song
   becomes current, whether chosen in Queue, Library, Search or Playlist.
2. Enable GoneSmart Smart DJ automatically if the companion switch is
   off; persist the change via the companion app's writable framework
   preferences and use Smart DJ immediately in the current GMMP process.
3. Use GMMP's officially documented **CLEAR_QUEUE** control command.
   According to GMMP's Queue help, Clear removes everything **except
   the playing song**. If Auto-DJ was already active and refills during
   this step, accept a changed native queue with the same seed song.
4. Use GMMP's officially documented **AUTO_DJ** control command.
   The **Initial Size includes the selected song**: at initial size 5,
   the target queue is that song + 4 recommendations. Continue native
   Auto-DJ's later refills according to **Upcoming Tracks**.
5. Verify that the selected song remains current and that the initial
   queue is filled to the configured size. If GMMP doesn't initiate
   the first refill, request **only the missing tracks** once through
   the existing native `qr.z(missing)` and GoneSmart selection hook.
   Never claim verification success if that condition fails.

Uses the actual native PopupMenu listener for the selected row;
AppCompat menu dispatch is supported as a fallback. Native queue writes
and playhead changes are observed passively to avoid mistaking the
previous queue for the newly selected song.

## One combined test

Install the latest successful APK from the
`feature/multi-playlist-add` Actions run, restart GMMP, and check the
new row's placement and sparkle. Use a recognizable track as the seed
and set **Initial Size** to something visible (for example, 5).
Then test Track Mix on a different song from **Queue**, **Library**,
**Playlist details** and **Search**; include the **file browser**
only if you use it. One case should begin while GMMP is already in
Auto-DJ mode and one while GMMP is in normal playback. If convenient,
switch GoneSmart Smart DJ off before a case to verify auto-enabling.

For every case, the newly selected song should remain at queue position
1 and the queue should contain **at least Initial Size** tracks, with
GoneSmart recommendations directly behind the seed. The player should
display its Auto-DJ playback mode and continue to refill normally.

Only send logs if anything differs. The app's **Logs → Track Mix**
category records high-level success or error messages. For detailed
diagnostics, filter Android Logcat by `GoneSmartTrackMix`,
`GoneSmart` or `GoneSmartFlip`. Especially useful lines:

- `MIX MENU`: which context menu received the third entry.
- `MIX NATIVE PLAY`: how native playback was dispatched.
- `MIX SEED`: confirms the selected song survived queue clearing.
- `MIX AUTO-DJ`: native command and GMMP Initial Size.
- `MIX NATIVE REFILL` / `MIX REQUEST REFILL`: initial fill path.
- `MIX VERIFIED`: expected/actual initial size, seed preserved.
- `MIX INCOMPLETE` / `MIX FAILED`: stage and failure reason.

CI tests the pure TrackMixPlan logic and builds the APK, but only an
actual device run verifies GMMP 4.2.0's native command timing, selected
row callbacks, automatic setting persistence and generated music.
