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
3. Open GMMP's Queue overflow. Confirm that the new `Queue ⇵ ✦` action is directly **above Remove duplicates**.
4. Open the three-dot context menu of an ordinary playlist. Confirm that `Play ⇵ ✦` follows Shuffle.
5. Repeat with a Smart Playlist. Capture any menu that is missing or inserted in the wrong place.
6. For each of the three new actions, tap once. **Phase 1 only logs diagnostics and shows a preview toast: it does not modify the queue or start playlist playback.**
7. Send Logcat lines filtered by `GoneSmartFlip`, especially `FLIP READY`, `FLIP MENU`, `FLIP ITEMS`, `FLIP CLICK` and `FLIP QUEUE`.

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

1. Validate the intended semantics for the current track and already-played history. The safe default to test first is to preserve the currently playing song and history and reverse **only upcoming tracks**, then consider full queue reversal separately.
2. Verify the native queue writer and its UI notifications with a disposable five-track queue. Preserve the current queue-entry ID and shuffle state unless the user explicitly requests otherwise.
3. For playlist and smart-playlist actions, invoke their native Play command and apply reversal **only after the resulting queue is known to have loaded**; otherwise an asynchronous playlist load could reverse the old queue.
4. Replace preview-only clicks with a native, reversible operation after the phone test passes. Never hook unrelated menus or bypass normal GMMP playlist loading.

Do not document Flip Queue as shipped until all three actions actually work and have passed device testing.
