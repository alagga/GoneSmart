# GMMP 4.2.1 Queue Compatibility — r41 playback-position correction

## Device evidence that reopens the Current mapping

The 2026-10-06 device pass disproves the previously accepted `qr.t -> ur.b()` current-position mapping.

During normal playback GMMP reads queue position 7 as the current row and then queue position 8 as the next row to preload. At the later Queue Flip attempt, however, `ur.b()` still returns 1. The old read-only resolver therefore selected row 1 merely because `queue_position = 1` existed uniquely in the queue.

This is value correlation, not semantic identity.

Consequences:

- `qr.t/ur.b()` is no longer accepted as playback `queue_position`.
- Writer discovery on `ur` is retired.
- r36's statement that `ur` was the verified current-state owner is superseded by this note.
- Queue mutation remains fail-closed until both the corrected reader and a natural native writer are proven.

## Restored read owner

Earlier 4.2.1 device passes repeatedly resolved the current queue row through `qr.p -> dx3.field:o`. Those passes also correlated the selected row with the actual currently playing track URI and produced correct CURRENT/HISTORY seed ordering.

r41 restores `qr.p/dx3` as the first read owner in `GmmpQueueReader` and explicitly excludes `qr.t/ur` from structural fallback. `NativeQueuePositionSignal`, used for writer proof, likewise reads only the `qr.p` state owner. The observed `field:o` name is retained as a verified-version fast path; other Auto-DJ children are not searched by value.

This corrects read ownership but does **not** by itself authorize mutation.

## Passive writer proof

The old r34 candidate `dx3.D() -> c2(int)` is reopened as evidence only. It was never behaviorally verified because the Queue delete bridge failed earlier in that run. r41 does not invoke `c2` or any other candidate during discovery.

Instead, r41 installs passive hooks on Auto-DJ child types that structurally expose:

- integer read state; and
- one-Int/void commands.

A command is retained as a writer only when GMMP calls it naturally and the corrected `qr.p/dx3` read signal changes exactly to that argument.

`GmmpQueueMutationBridge` now requires this passively verified writer before moving the playback pointer. A structurally plausible setter is diagnostic evidence only and cannot be called by Queue Flip.

## Bundled playback transition diagnostics

The same device pass showed that the three observed one-Int `MusicService` commands do not run during the natural title transition. To avoid another narrow probe build, r41 passively wraps a bounded set of direct `MusicService` playback/event methods in the same APK.

For natural calls only, it compares read-only integer snapshots before/after and may emit:

`QUEUE PLAYBACK STATE TRANSITION`

The marker contains:

- the native method signature;
- immediate or bounded delayed phase;
- changed integer signals; and
- a bounded primitive shape of natural event arguments.

No candidate playback/event method is invoked by the diagnostic. Delayed checks are limited to one 120 ms sample with a small global pending cap.

Queue Flip additionally emits:

`QUEUE PLAYBACK SNAPSHOT`

so the next device pass contains the live `dx3`, `ur`, and direct service integer signals at the exact mutation boundary.

## Expected next device pass

One normal title change followed by one Queue Flip is sufficient.

Useful outcomes, in order of strength:

1. `GMMP QUEUE MAPPING ... currentResolver=direct-state:dx3.field:o` while GMMP's own current-row query uses the same queue position.
2. `QUEUE STATE COMMAND OBSERVED` for one of the passively hooked Auto-DJ child commands.
3. `QUEUE POSITION WRITER VERIFIED` with the corrected `field:p->dx3.field:o` signal.
4. Queue Flip then reaches the native Queue mutation path and verifies the reversed rows/current entry.

If no state writer is verified, the same log should still contain `QUEUE PLAYBACK STATE TRANSITION` and `QUEUE PLAYBACK SNAPSHOT`, intended to identify the real native call chain without another broad probe.

## Unchanged proven Queue boundary

This correction does not reopen the already proven Room Queue boundary:

- observed database accessor `GMDatabase_Impl.F():sx3`;
- observed runtime Queue DAO `vx3`;
- observed generated Queue entity `cy3`;
- observed synchronous native row reader `sx3.H1()`;
- native update writer `O0(List)` after DAO/entity proof;
- erased native delete bridge receives a runtime `cy3[]`, not `Object[]`;
- reactive `W1/X1` discovery remains disabled; and
- no direct SQL writes are used.
