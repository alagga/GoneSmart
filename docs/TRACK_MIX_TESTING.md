# Track Mix / Titel-Mix — combined GMMP 4.2.0 phone test

Status: **native playback and initial queue filling verified in five
successful phone tests on GMMP 4.2.0** (23 September 2026). The first
trial reported an initial native Clear Queue race; the next build retries
that command once when native Play overlaps with an already-active
Auto-DJ refill. The retry itself still requires one on-device check.
No signed public release has been published for this new feature.

## Intended user experience

In a song's three-dot menu, **Titel-Mix** (English: **Track Mix**)
appears as the **third entry**, directly after native **Play** and
**Play next**, with GoneSmart's lilac two-star sparkle. The neighboring
menu row heights do not change. The label now reads GMMP's own translated
`track` resource (German: `Titel`, English: `Track`). For other
player languages, it combines the native `track` and `auto_dj` nouns,
rather than showing an unrelated English label.

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
   the playing song**. If a preexisting Auto-DJ refill overlaps the native
   Play/Clear transition, wait up to 3 seconds, then retry Clear exactly
   once if the same selected song remains current. A failed second
   attempt reports a real failure; it does not claim the seed was isolated.
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

## Phone results and user-facing notifications

The supplied 23 September 2026 Logcat showed **five successful
`MIX VERIFIED` events**, each with GMMP Initial Size 5, an unchanged
current seed and four additional queued tracks. Some refills came from
GoneSmart recommendations; others used GMMP native fallback when
GoneSmart's quality gate lacked sufficient matches. Therefore high-level
Logs now say `tracks queued`, not `GoneSmart recommendations`.

The **first** run (`23:56:40`) failed to isolate the selected seed:
`MIX FAILED | Could not isolate the selected song in the queue.`
GMMP's own Auto-DJ had already begun refilling the previous queue.
The follow-up build attempts one bounded native Clear retry; it does
not modify GMMP's database directly.

No success toast is shown after a verified Track Mix; GoneSmart's Logs
tab records the outcome. Failed or incomplete operations still show
short warnings. Native GMMP notifications may still appear and are
not suppressed by GoneSmart.

CI tests the pure TrackMixPlan logic and builds the APK, but GMMP 4.2.0's
native timing, seed isolation and playback continuity still merit
on-device regression checks after changes.
