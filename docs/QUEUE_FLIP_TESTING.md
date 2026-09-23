# Flip Queue / Play Flipped – combined first device test

This is a development feature on `feature/multi-playlist-add` (GMMP 4.2.0).
All three modes are now wired to native GMMP playback/queue APIs. Automated
tests cannot replace one physical-device end-to-end test because GMMP's
obfuscated implementation, Room observers and audio engine run only in GMMP.

## Intended behavior

- **Existing Queue:** Entire queue `A B C D E` becomes `E D C B A`. Keep
  the same **currently playing or paused queue entry**, its playback progress
  and play/pause state. Move its numeric playback position to
  `newPosition = length + 1 - oldPosition`. History and upcoming titles
  are both reversed; duplicate song IDs retain distinct native queue IDs.
- **Playlist and Smart Playlist:** Use the clicked row's **native Play**
  callback to resolve its own tracks. Reverse that **new** playlist's entire
  resolved order **before** GMMP creates its playback queue. Start with the
  **original last track**, then play through to the original first. This
  is *not* the same as preserving a previous current queue entry.

The menu positions remain Queue → immediately above Remove duplicates,
and Playlist / Smart Playlist → immediately below Shuffle. Menus use
GMMP's own localized Queue/Play label, font-weight-adjusted spaced arrows
and GoneSmart's centered full-size lilac two-star sparkle.

## Combined phone test — do once, in this order

1. Install the APK from the latest green GitHub workflow of
   `feature/multi-playlist-add`, restart GMMP, enable the separate Flip
   toggle in GoneSmart → UI. Save any queue you care about first.
2. **Queue:** On a disposable 5–7-track queue, play or pause an off-center
   title (e.g. B in A B C D E). Tap Flip. Expected full order E D C B A,
   with B still playing or paused at index 4. Check audio progress.
   The new `FLIP APPLIED` log must include `verified=true`.
3. **Playlist:** Choose a short, identifiable ordinary playlist, tap
   Play Flipped in its row menu. The original last track must begin
   playback, followed by the second-to-last. Watch for
   `FLIP PLAY APPLIED` and `FLIP SERVICE` log lines.
4. **Smart Playlist:** Repeat for a Smart Playlist with a known native
   ordering. The original last track must be first after GMMP evaluates
   its Smart Playlist definition.
5. If all three work, report that once, plus whether the arrows/spaces
   look right. If any fails, filter Logcat by `GoneSmartFlip`, include
   the corresponding error/success lines and whether the old queue
   changed. No need to repeat the earlier diagnostic-only click tests.

## Implementation and safeguards

- Queue: `tx3.H1()` read on a dedicated worker; distinct `ey3.d` IDs;
  validate contiguous 1-based `ey3.a` positions; `xx3.O0(List)` performs
  a **native Room transaction** updating each existing entry by
  `queue_id`, then `ex3.b2(newPosition)` moves the playhead with its
  original queue entry. Re-read and verify the reversed IDs and pointer.
  If verification fails after write, attempt to restore the original list.
  Abort if the native snapshot changed before write.
- Playlist: invoke the exact native PopupMenu listener for the clicked
  row. On the pending `MusicService.w1(action=0)` event, reverse the
  resolved `List<rm3>` *before* the native service clears/replaces the
  queue. Expire pending requests after 30 seconds. Ordinary native
  Play calls without an active pending Flip request remain untouched.
- Native `ex3.K(int,int)` is a SQL position-shift helper, **not** a safe
  high-level reorder method. Do not call it to reverse the queue.
- Do not modify .m3u playlist files or run raw SQL outside GMMP's DAO.

If an on-device test exposes an unexpected native callback or a
transaction error, leave Flip disabled and share the log. Static APK
inspection and CI prove the signatures/build, **not** real audio-state
continuity; do not mark the feature as final until the combined phone
test succeeds.
