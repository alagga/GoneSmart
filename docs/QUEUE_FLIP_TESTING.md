# Flip Queue – staged GMMP 4.2.0 validation

This is an **experimental feature under development for the upcoming v0.4.x series**, not yet a completed queue-reversal implementation. It is deliberately **off by default** under **GoneSmart → UI → Playback & Queue**.

## Phase 1: verify menu placement, native context and queue identity

GoneSmart injects a preview action into three independently controlled GMMP menus:

| GMMP screen | Native menu resource | Placement |
| --- | --- | --- |
| Queue (top-right overflow) | `menu_gm_queue` | Directly **above Remove duplicates** |
| Playlists (row three-dot menu) | `menu_gm_context_playlist_list` | Directly **below Shuffle** |
| Smart Playlists (row three-dot menu) | `menu_gm_context_smart` | Directly **below Shuffle** |

The list and smart-list actions use GMMP's *currently localized* native Play title plus the universal direction symbol `⇵` and a small GoneSmart `✦`. The queue action uses GMMP's localized Queue title. **GMMP 4.2.0 has no translated string meaning reverse/flip a queue.** Its resource named `flip` is a *view ID*, not a translatable string. Do not mistakenly use the `invert_colors` text for playback semantics.

### First phone test

1. Install the debug APK from the `feature/multi-playlist-add` branch, with GoneSmart's scope enabled for `gonemad.gmmp`. Restart GMMP after installing the **module update**.
2. In GoneSmart open **UI → Playback & Queue**, enable **Flip queue / Play flipped**. This setting is separate from Smart DJ and Multi-playlist selection.
3. **Test 1: Queue.** Open GMMP's Queue overflow. The new `Queue ⇵` entry should appear directly **above Remove duplicates** with the **same two-star lilac GoneSmart sparkle** used by the other features. Tap it with a small queue and check that the log prints a `FLIP PLAN | mode=DRY_RUN` line.
4. **Test 2: Playlist.** Open the three-dot menu of an ordinary playlist. Verify that `Play ⇵` (with the lilac two-star sparkle) follows Shuffle; tap it.
5. **Test 3: Smart Playlist.** Open a Smart Playlist three-dot menu, verify the same position directly below Shuffle, and tap it.
6. All three entries are still a **safe preview**. Tapping them logs diagnostics and shows a preview toast; **no track is reordered and no playlist starts playing** in this build.
7. Send Logcat lines filtered by `GoneSmartFlip`, especially `FLIP MENU`, `FLIP CLICK`, `FLIP QUEUE`, `FLIP PLAN`, `FLIP PLAN FIRST` and `FLIP PLAN LAST`. The test ordering is always **Queue → Playlist → Smart Playlist**, so consecutive click logs identify their source.

Prefer a disposable five-track queue for the first test. The native model can contain thousands of tracks and we will not experiment on a large queue.

## Native integration research

The GMMP 4.2.0 APK exposes the following native resources and classes:

- `menu_gm_queue` (0x7f0e0044), `menu_gm_context_playlist_list` (0x7f0e0020), and `menu_gm_context_smart` (0x7f0e0025).
- `menuContextShuffle` (0x7f090238), `menuContextPlay` (0x7f090232), and `menuRemoveDuplicates` (0x7f09028b).
- The queue is `ex3`; its DAO is `ex3.r` (`tx3`, runtime implementation `xx3`).
- `tx3.H1()` returns the native `ey3` queue entries, whose `a`, `b`, `c`, and `d` fields represent visible position, song ID, shuffle position, and unique queue-entry ID respectively.
- Queue playback's current position is available through `ex3.D()`.
- Candidate native mutations include `ex3.K(Int,Int)` (reorder), `xx3.O0(List)` (DAO update), and `ex3.b2(Int)` (current position), but **their complete semantics and event ordering still require validation**. Never manipulate `.m3u` files or update GMMP's database directly.

## Phase 2: safe queue reordering

After phase-1 logs confirm exact callback and native queue identity:

1. **Confirmed behavior:** preserve the currently selected track at its **exact absolute index**, regardless of whether playback is playing or paused. Reverse **all other queue entries globally**, including both history and upcoming songs; do **not** reverse the two portions independently. Example: `A, B, [C], D, E` becomes `E, D, [C], B, A` with `C` pinned at index 2. For a noncentral current song, e.g. `A, [B], C, D, E`, the result is `E, [B], D, C, A`.
2. Verify the native queue writer and its UI notifications with a disposable five-track queue. Preserve the pinned current queue-entry ID and its absolute index; maintain valid queue/shuffle positions unless the user explicitly requests otherwise. The preview now logs the complete **planned permutation** in truncated first/last samples without changing anything.
3. For playlist and smart-playlist actions, invoke their native Play command and apply reversal **only after the resulting queue is known to have loaded**; otherwise an asynchronous playlist load could reverse the old queue.
4. Replace preview-only clicks with a native, reversible operation after the phone test passes. Never hook unrelated menus or bypass normal GMMP playlist loading.

Do not document Flip Queue as shipped until all three actions actually work and have passed device testing.
