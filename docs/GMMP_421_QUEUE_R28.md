# GMMP 4.2.1 queue/playback compatibility — r28

Date: 2026-10-03

This note continues the r27 handoff after the consolidated device pass that exercised Track Auto-DJ, Playlist Play Flipped and Queue Flip. Fold this result into `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` during the next large playbook edit; until then this is the current handoff for the remaining 4.2.1 playback boundary.

## Device evidence

The supplied host log separates the three reported failures:

- Track Auto-DJ reaches the accepted read-only Queue mapping and `MIX PLAY VERIFIED`, then enters seed isolation.
- r27 emits `QUEUE ENTITY TYPE | source=generated-room-adapter | model=g85`, but the evidence for every participating adapter is `owned-dml`, not `queue-sql`.
- Reconstruction of `g85` fails and the bridge later aborts with `entityHint=g85; none; callableReaders=d85.W1->xp4,d85.X1->jm1`.
- Queue Flip reaches the same accepted Cursor Queue snapshot and fails at the same native entity-reader boundary with the same `g85` hint.
- Playlist Play Flipped is different: the original playlist Play action is dispatched, GoneSmart reverses the native four-track List before the resolved MusicService playback method, GMMP starts the reversed first track, and the Cursor postcondition reports `FLIP PLAY VERIFIED` with all four expected track IDs and the current entry at queue position 1. The submitted log therefore does not support treating the Playlist path as the same Queue-DAO failure.

## r28 correction: adapter ownership requires queue_table SQL

r25-r27 allowed a second ownership proof for a direct generated DAO field: any owned DML SQL plus an erased binder bridge could nominate its entity type. The latest host evidence disproves that as sufficiently specific. A Queue DAO can own generated DML adapters whose model is not the `queue_table` writer entity; all three `g85` results came through this weaker `owned-dml` path.

r28 removes that fallback. `NativeQueueEntityAdapterTypeResolver` may now nominate an entity only when the adapter's own no-arg SQL boundary explicitly names `queue_table`. Binder-cast, synthetic bridge handling, direct-binder precedence and generic-superclass precedence are still used, but only after that table-level proof.

If no generated adapter proves `queue_table`, the existing queue-specific hierarchy resolver becomes authoritative again. On the observed 4.2.1 shape the nearest queue-specific array contract is the relation/wrapper family (`ww3[...]`); its unique custom embedded constructor type may be used only when that embedded type has the four-number Queue-entity shape. `NativeReactiveListReader` already has a regression-tested path that unwraps exactly one independently proven embedded Queue entity from each relation row before Cursor correlation.

This remains native-first and fail-closed: no direct SQL writes, no guessed obfuscated names, no synthetic Queue entity write unless the existing one-to-one `queue_id + song_id + queue_position` Cursor correlation succeeds.

## Automated gate

`NativeQueueEntityAdapterTypeResolverTest` now uses `queue_table` in every positive fixture and adds a negative fixture proving that a directly owned non-Queue DML adapter cannot claim Queue entity ownership. Existing wrapper-unwrapping and reactive-reader tests remain the downstream contract for the hierarchy fallback.

## Next device pass

Use one combined pass only:

1. Track Auto-DJ once from a Queue/track row. Expected progression: Queue Cursor mapping -> native Play verified -> Queue entity hint from the queue-specific contract -> seed isolation verified -> native Auto-DJ refill.
2. Queue Flip once. Expected progression: Queue Cursor mapping -> Queue mutation mapping -> reverse verified with the same current queue entry.
3. Playlist Play Flipped only needs observation if it still appears wrong to the maintainer. The previous log already proves reversed native input, reversed Queue order and current-first Cursor state, so do not remap the MusicService method blindly. If the visible/audible result still disagrees, capture that same action in the consolidated log and diagnose the discrepancy as a post-playback/order-mode issue rather than a failed interception.
