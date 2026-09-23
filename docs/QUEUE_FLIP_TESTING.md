# Flip Queue – staged GMMP 4.2.0 validation

This is an **experimental feature under development for the upcoming v0.4.x series**, not yet a completed queue-reversal implementation. It is deliberately **off by default** under **GoneSmart → UI → Playback & Queue**.

## Phase 1: verify menu placement, native context and queue identity

GoneSmart injects a preview action into three independently controlled GMMP menus:

| GMMP screen | Native menu resource | Placement |
| --- | --- | --- |
| Queue (top-right overflow) | `menu_gm_queue` | Directly **above Remove duplicates** |
| Playlists (row three-dot menu) | `menu_gm_context_playlist_list` | Directly **below Shuffle** |
| Smart Playlists (row three-dot menu) | `menu_gm_context_smart` | Directly **below Shuffle** |

The playlist and smart-playlist actions use GMMP's *currently localized* native Play title, a custom pair of **two moderately weighted parallel up/down arrows** (approximately matching the native menu letter-stem width), and GoneSmart's full-size **two-star lilac sparkle**. The queue action uses GMMP's localized Queue title with the same arrows and sparkle. Both icons are centered without increasing native menu row height. **GMMP 4.2.0 has no translated string meaning reverse/flip a queue.** Its resource named `flip` is a *view ID*, not a translatable string. Do not mistakenly use the `invert_colors` text for playback semantics.

### First phone test

1. Install the debug APK from the `feature/multi-playlist-add` branch, with GoneSmart's scope enabled for `gonemad.gmmp`. Restart GMMP after installing the **module update**.
2. In GoneSmart open **UI → Playback & Queue**, enable **Flip queue / Play flipped**. This setting is separate from Smart DJ and Multi-playlist selection.
3. **Test 1: Queue.** Open GMMP's Queue overflow. The new Queue + bold paired arrows + lilac sparkle entry should appear directly **above Remove duplicates**. Check the **row height matches adjacent native entries**. Tap it with a small queue and check `FLIP NATIVE API`, `FLIP QUEUE` and `FLIP PLAN | mode=DRY_RUN` in Logcat.
4. **Test 2: Playlist.** Open the three-dot menu of an ordinary playlist. Verify that Play + bold paired arrows + lilac sparkle follows Shuffle without increasing row height; tap it. Logcat should print `FLIP TARGET`, `FLIP TARGET TYPES` and `FLIP PLAY PLAN` for `PLAYLIST`. It must **not log a flip plan for the old queue**.
5. **Test 3: Smart Playlist.** Open a Smart Playlist three-dot menu, verify the same placement and normal row height, then tap it. Look for `FLIP TARGET`, `FLIP TARGET TYPES` and `FLIP PLAY PLAN` for `SMART`.
6. All three entries are still a **safe preview**. Tapping them logs diagnostics and shows a preview toast; **no track is reordered and no playlist starts playing** in this build.
7. Send Logcat lines filtered by `GoneSmartFlip`, especially `FLIP MENU`, `FLIP CLICK`, `FLIP NATIVE API`, `FLIP QUEUE`, `FLIP PLAN`, `FLIP TARGET`, `FLIP TARGET TYPES`, **`FLIP POPUP SOURCE`, `FLIP POPUP TYPES`** and `FLIP PLAY PLAN`. The test ordering is always **Queue → Playlist → Smart Playlist**, so consecutive click logs identify their source.
8. **Optional, after those three tests:** in a disposable five-track queue, manually drag one song from the first position to the fourth. Send the resulting **`FLIP NATIVE MOVE`** line, which observes GMMP's own `ex3.K(int,int)` invocation. This is a passive hook: GoneSmart does not move any tracks itself.

Prefer a disposable five-track queue for the first test. The native model can contain thousands of tracks and we will not experiment on a large queue.

## Native integration research

The GMMP 4.2.0 APK exposes the following native resources and classes:

- `menu_gm_queue` (0x7f0e0044), `menu_gm_context_playlist_list` (0x7f0e0020), and `menu_gm_context_smart` (0x7f0e0025).
- `menuContextShuffle` (0x7f090238), `menuContextPlay` (0x7f090232), and `menuRemoveDuplicates` (0x7f09028b).
- The queue is `ex3`; its DAO is `ex3.r` (`tx3`, runtime implementation `xx3`).
- `tx3.H1()` returns the native `ey3` queue entries, whose `a`, `b`, `c`, and `d` fields represent visible position, song ID, shuffle position, and unique queue-entry ID respectively.
- Queue playback's current position is available through `ex3.D()`.
- Runtime logs confirm that `ex3.K(int,int):void`, `xx3.O0(List):void` and `ex3.b2(int):void` exist. We have not established `K`'s argument direction or safe playback-state semantics; the next build passively logs calls while you manually reorder a test queue. Both playlist menus currently route through generic `android.widget.PopupMenu$1` callbacks; new `FLIP POPUP SOURCE` diagnostics inspect the wrapped listener and anchor's **types only** to help identify GMMP's original clicked playlist. Never manipulate `.m3u` files or update GMMP's database directly.

## Phase 2: safe queue reordering

After phase-1 logs confirm exact callback and native queue identity:

1. **Confirmed behavior (corrected):** reverse the **entire queue with no pinned position**: `A, B, C, D, E` → `E, D, C, B, A`. This includes all previously played and all upcoming tracks. Keep the exact same **song/queue-entry playing or paused** by moving GMMP's playback pointer to its new index (`newCurrentIndex = queueSize - 1 - oldCurrentIndex`). For instance, `A, [B], C, D, E` → `E, D, C, [B], A` (the current B moves from index 1 to index 3 but continues playing or stays paused). If current C was central, `A, B, [C], D, E` → `E, D, [C], B, A`.
2. Verify the native queue writer and its UI notifications with a disposable five-track queue. Preserve the current **queue-entry ID, playing/paused state and playback progress**, but change the numeric queue position to the new index. Maintain valid queue/shuffle positions unless the user requests otherwise. The preview now logs the full reverse permutation in truncated first/last samples without writing anything.
3. For playlist and smart-playlist actions, reverse **the newly resolved playlist's complete ordering** and begin playback at the **original last song**, not at the preexisting queue's current track. The pure `QueueFlipPlanner.reverseForNewPlayback` and tests already encode this distinction. We are currently probing the native Play dispatcher and target classes through `FLIP TARGET`/`FLIP TARGET TYPES`; do **not** invoke the native Play action until the correct asynchronous source and after-load callback are verified.
4. Replace preview-only clicks with a native, reversible operation after the phone test passes. Never hook unrelated menus or bypass normal GMMP playlist loading.

Do not document Flip Queue as shipped until all three actions actually work and have passed device testing.
