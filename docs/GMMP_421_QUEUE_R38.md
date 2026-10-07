# GMMP 4.2.1 queue r38 — native Play settling

## Device evidence from r37

The r37 Track Mix run finally proves the shared 4.2.1 queue mutation path itself:

- native Play reaches a verified current track;
- Queue DAO resolves to `vx3`;
- Queue entity resolves to `cy3`;
- native list reader resolves to `sx3.H1()`;
- the delete writer receives a real `cy3[]`;
- the two-row intermediate queue is reduced to one row;
- `QUEUE MUTATION | seed isolation verified` succeeds.

The later return to 7213 rows was not a failed delete and not a GoneSmart
Auto-DJ refill. The action was started from a Smart Playlist containing 7213
tracks. GMMP's native Play operation exposes the selected current track before
its asynchronous list-to-queue population has finished. r37 isolated the early
two-row intermediate state while the same native Play operation was still
appending the rest of the Smart Playlist.

A control run from an ordinary playlist completed successfully, which further
separates the queue mutation path from the Smart Playlist population race.

## r38 correction

Track Mix no longer treats `current track changed` as sufficient proof that a
list-backed native Play operation has finished.

After the selected current identity is detected, GoneSmart keeps reading the
already-accepted read-only queue Cursor and waits for the complete queue image
(track IDs, queue entry IDs and current index) to remain unchanged for a bounded
700 ms quiet window. Every native queue change resets that window. The overall
wait is bounded to 12 seconds and remains fail-closed.

This is not a fixed post-Play sleep: a small/ordinary playlist proceeds after
one short quiet window, while a large Smart Playlist can continue populating
for as long as GMMP keeps changing the queue. Isolation starts only after the
native queue is actually quiescent.

New runtime markers:

- `MIX PLAY DETECTED | ... | queueSize=...`
- `MIX PLAY VERIFIED | source=queue-settled | ... | queueSize=... | stableMs=...`

Host tests cover a growing queue, the required quiet window, and current-index
changes resetting the settling proof.

## r37 pointer-writer correction

r37 temporarily allowed the unique directly-owned `qr(int)->void` method to act
as a split current-position writer for the verified `qr.t -> ur.b()` reader.
That inference is withdrawn in r38.

GoneSmart's own accepted native refill path already proves that `qr.z(int)` is
GMMP's Auto-DJ refill command. Therefore numeric commands on the Auto-DJ owner
are diagnostics only and are never promoted to current-position writers by
shape. Queue Flip remains fail-closed until the real writable current-position
boundary is proven independently.

Track Mix is unaffected when native Play already places the selected seed at
queue position 1; that path does not require a current-position writer.

## Next device pass

Run Track Mix once from the same large Smart Playlist that produced the 7213-row
race. Expected order:

1. `MIX PLAY DETECTED` while GMMP is still building the native queue;
2. queue size may change repeatedly;
3. only after quiescence: `MIX PLAY VERIFIED | source=queue-settled` with the
   completed Smart Playlist size;
4. `QUEUE DELETE ARRAY` removes all non-seed rows;
5. `QUEUE MUTATION | seed isolation verified` leaves one selected row;
6. Auto-DJ then fills the new seed queue normally.

Do not use Queue Flip as part of this pass; its current-position writer remains
a separate unresolved boundary.
