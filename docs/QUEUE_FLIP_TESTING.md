# Flip Queue / Play Flipped — validation and compatibility

Status: **implemented and verified on-device with GMMP 4.2.0** on the
`feature/multi-playlist-add` development branch. Feature remains optional
and **off by default**; it is not a claim that a signed public release
has already shipped.

## Confirmed semantics

- **Existing queue:** `A B [C] D E` becomes `E D [C] B A`.
  Every entry (including history) reverses. The **same current entry**
  continues playing or paused, with its playback position, while its
  absolute position changes to `size + 1 - oldPosition`. Duplicate track
  IDs remain distinct by native queue-entry ID.
- **Playlist / Smart Playlist:** Resolve the selected playlist normally,
  then reverse the **complete** resulting track list before GMMP replaces
  the playback queue. `A B C D E` plays `E D C B A`, starting at E.
  Smart Playlist membership and ordering are evaluated by GMMP first.
- No .m3u playlist on disk is changed. Flip and multi-playlist selection
  are independent of Smart DJ.

The Queue entry appears immediately before **Remove duplicates**.
The Playlist / Smart Playlist entry appears immediately after **Shuffle**.
Labels reuse GMMP's translated Queue/Play text. The separated typographic
arrows and full-size lilac two-star sparkle do not expand popup row height.

## Actual phone results — 23 September 2026

| Test | Observed native result | Verification |
| --- | --- | --- |
| Existing 31-track queue | Current position 8 → 24; original unique current entry ID `5265097` unchanged | `FLIP APPLIED … verified=true` |
| Ordinary 17-track playlist | Original first track `19062`, new first track `19100`; original first now last | `FLIP PLAY VERIFIED … queueSize=17 … currentPosition=1` |
| 31-track Smart Playlist | Original first `9699`, new first `18442`; original first now last | `FLIP PLAY VERIFIED … queueSize=31 … currentPosition=1` |
| 140-track Smart Playlist | Original first `10242`, new first `4826`; original first now last | `FLIP PLAY VERIFIED … queueSize=140 … currentPosition=1` |

The user also reported that the feature appears to work correctly in
GMMP. These logs prove queue identity/order and the observed current
playback position; they **cannot independently establish every audio
detail**, e.g. all play/pause/progress race conditions or behavior on
future GMMP versions.

## Runtime logging contract

The **GoneSmart → Logs** tab records concise events from all features
with labels `[Smart DJ]`, `[Playlists]`, `[Flip]`, `[UI]` and
`[System]`. The UI shows category totals and retains up to 400 lines.
`[Flip]` logs a completed operation **only after native verification**,
and records failures/rollback results separately. Normal UI events do
not replace the independent Auto-DJ readiness/fallback status on Home.

For detailed troubleshooting, use Logcat tags `GoneSmart`,
`GoneSmartPlaylist` and `GoneSmartFlip`. Obsolete preview-only queue
snapshots and passive manual-move diagnostics were removed from the final
implementation; a regular native Flip operation now logs the actual
result rather than `mode=DRY_RUN`.

## Native safeguards and limitations

- Current queue: `tx3.H1()` on a worker thread, validate contiguous
  `ey3.a` positions and unique `ey3.d` queue IDs; `xx3.O0(List)`
  updates existing entries through GMMP's own Room DAO. Then
  `ex3.b2(newPosition)` moves the native playhead. Re-read the queue,
  verify IDs and current position, and attempt rollback on failure.
- New playlist: pass the original selected playlist's native Play command
  through its native popup callback. When GMMP's `MusicService.w1(action=0)`
  receives the newly resolved track list, reverse it before native queue
  replacement and starting its original-last song. Ignore normal
  playback without a pending Flip request.
- On-device native verification follows playlist load and confirms the
  complete expected order and `currentPosition=1`.
- GMMP updates may change obfuscated methods and require renewed
  compatibility testing. Concurrent playlist/queue operations, duplicate
  songs and interruption during database writes remain useful regression
  scenarios before broader distribution.

Do not publish a new signed release or merge the feature branch solely
because CI is green; review and use the normal project release process.
