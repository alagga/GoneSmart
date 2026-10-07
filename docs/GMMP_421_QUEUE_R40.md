# GMMP 4.2.1 Queue Compatibility — r40

## Scope

r40 bundles the two remaining Track Mix issues seen after r39 so the next device pass does not require separate probe builds:

1. Track Mix started from a large Smart Playlist could lock onto GMMP's temporary 2-row Play image and mutate while the full Smart Playlist was still being materialized into `queue_table`.
2. The post-seed Auto-DJ transition reached Initial Size indirectly (`upcoming=1`, then a GoneSmart supplement) instead of using the proven native refill boundary once for the complete Initial Size shortage.

## Device evidence

The 2026-10-04 Smart Playlist run shows:

- selected track `19121` is first detected in a 2-row queue;
- that 2-row image remains quiet long enough for the old 700 ms quiescence rule to accept it;
- while the queue bridge starts resolving, GMMP continues the native list Play;
- `queue_table` later contains all `7213` Smart-Playlist tracks and the CURRENT row has changed to another track (`19132`).

The normal Playlist run independently proves the native mutation path itself works:

- `vx3` / `cy3` mapping succeeds;
- typed `cy3[]` delete succeeds;
- seed isolation is cursor-verified;
- r39 then reaches 5 total tracks only by letting GMMP request its normal `upcoming=1` refill and later requesting the remaining 3.

The same log proves `qr.z(count)` is the native refill boundary. The `AUTO_DJ` command on an already-seeded queue triggers the ordinary `upcoming` refill count; it is not, by itself, the Initial Size calculation.

## r40 Smart Playlist rule

Track Mix now retains the originally detected selected track ID across the native list rebuild.

For `menu_gm_context_track` (the Smart-Playlist run's source), the 2-row intermediate image must pass both:

- the existing 700 ms queue quiet window; and
- a 2500 ms bounded native-Play completion guard.

Any queue identity/order/current-index change resets the quiet window. The overall wait remains bounded (20 s).

If the final native list rebuild changes which row is CURRENT, the original selected track may still be accepted only when it appears exactly once and the queue size has changed from the detected temporary image. Ordinary same-size retargets remain rejected.

After the final queue is stable, the 4.2.1 bridge may realign that unique selected native `cy3` row to `queue_position=1` only when the already-verified native current-position reader reports position 1. This does not invent a pointer writer: the old row at position 1 is deleted, the selected row is moved to position 1 through the native Queue DAO update writer, and the independent Cursor must then report the selected row as CURRENT. Ambiguous/missing selected tracks or a non-1 pointer remain fail-closed.

## r40 Initial Size rule

The seed remains isolated while the Track Mix refill hold is armed.

Then:

1. send GMMP's normal `AUTO_DJ` command so native Auto-DJ mode is enabled;
2. keep the refill hold armed and suppress the command's transitional ordinary `upcoming` refill (`1` in the captured settings);
3. wait briefly for that native command refill boundary to be observed/suppressed (bounded to 1800 ms; no writer/query probing);
4. call the already-proven native `qr.z(count)` boundary exactly once with `Initial Size - seed queue size`;
5. for Initial Size 5 and one selected seed, request exactly 4 tracks;
6. verify the read-only Cursor contains exactly 5 tracks with the selected seed still CURRENT;
7. only then release the refill hold so future ordinary `upcoming` refills work normally.

A ThreadLocal allowance lets only Track Mix's explicit native refill cross the global hold on its worker thread. Unrelated/asynchronous GMMP refills remain suppressed during this transition.

The r39 `1 + later supplement 3` policy is retired.

## Safety invariants retained

- Queue identity/order/current state comes from the accepted read-only Cursor.
- Queue writer ownership remains `queue_table` generated-adapter proven.
- Native entity type remains `cy3` and native synchronous reader remains `sx3.H1()`.
- Delete remains a real runtime-typed `cy3[]` passed to GMMP's native writer.
- No direct SQL mutation.
- No reactive/query probing.
- `qr.z(int)` is refill only and is never promoted to a current-position setter.
- Every seed isolation is post-verified through the independent Cursor.

## Next device pass

One bundled test pass is sufficient:

1. Track Mix once from the same large Smart Playlist (7213-track source).
2. Track Mix once from a normal Playlist to confirm the new one-shot Initial Size path.

Expected normal Playlist markers:

- `MIX AUTO-DJ ARM | transitionalRefillSuppressed=true` (or false only if GMMP emitted no transitional request within the bounded arm window)
- `MIX INITIAL FILL | initial=5 | seedSize=1 | requested=4 | boundary=native-refill-once`
- `MIX NATIVE REFILL | requested=4`
- `MIX VERIFIED | initial=5 | actual=5`

Expected Smart Playlist markers before isolation:

- `MIX PLAY DETECTED ... queueSize=2 ... completionGuardMs=2500`
- later `MIX PLAY VERIFIED ... targetTrack=<clicked> ... queueSize=7213 ... rebuilt=true`
- `QUEUE MUTATION | seed isolation verified ... realigned=true` when GMMP's final CURRENT row drifted during the list build.
