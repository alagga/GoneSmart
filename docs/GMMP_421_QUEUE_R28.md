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

## r28 correction 1: adapter ownership requires queue_table SQL

r25-r27 allowed a second ownership proof for a direct generated DAO field: any owned DML SQL plus an erased binder bridge could nominate its entity type. The latest host evidence disproves that as sufficiently specific. A Queue DAO can own generated DML adapters whose model is not the `queue_table` writer entity; all three `g85` results came through this weaker `owned-dml` path.

r28 removes that fallback. `NativeQueueEntityAdapterTypeResolver` may now nominate an entity only when the adapter's own no-arg SQL boundary explicitly names `queue_table`. Binder-cast, synthetic bridge handling, direct-binder precedence and generic-superclass precedence are still used, but only after that table-level proof.

## r28 correction 2: carry the unique Queue relation component into runtime correlation

Before requesting another device build, the preceding r24 host result was rechecked. That build already failed with `entityHint=ww3`, so merely rejecting the false `g85` adapter ownership would recreate a known failure and waste another maintainer test pass.

The nearest queue-specific array contract remains a relation/wrapper family observed as `ww3(...)`, whose constructor contains one custom non-platform component (observed as `pw3`) plus platform/opaque metadata. The former hierarchy resolver would unwrap that component only when its static class shape already exposed at least four obvious numeric fields or a four-number constructor. The earlier host pass proves that requirement is too strict after R8 because the resolver stopped at the wrapper itself.

r28 now treats a **unique custom constructor component as a read-only nominee**, not as writer ownership. This is safe because all stronger gates happen afterwards:

1. `NativeReactiveListReader` must obtain the existing objects from the already-owned Queue read carrier and, for relation rows, find exactly one nested instance of the nominated type per row;
2. the resulting set must contain exactly the live Cursor row count;
3. `GmmpQueueMutationBridge.cursorCorrelates` must derive one and only one mapping whose values match every live `queue_id`, `song_id` and `queue_position`;
4. only after that correlation may GMMP's original generated update/delete writers become eligible;
5. mutation is still followed by the existing read-only Cursor postcondition and rollback path.

Zero or multiple custom constructor components do not produce an embedded nominee. No R8 class name (`ww3`, `pw3`, `g85`, `d85`) is used as a compatibility key.

This remains native-first and fail-closed: no direct SQL writes and no guessed obfuscated writer are introduced.

## Automated gate

- `NativeQueueEntityAdapterTypeResolverTest` uses `queue_table` in every positive fixture and has a negative fixture proving that a directly owned non-Queue DML adapter cannot claim Queue entity ownership.
- `NativeQueueEntityTypeResolverTest` now covers an opaque embedded relation component whose static numeric shape is intentionally insufficient. It may be nominated for runtime correlation only because it is the wrapper's unique custom constructor component. A relation with multiple custom components does not choose one.
- Existing `NativeReactiveListReaderTest` coverage still proves exact-one embedded-entity unwrapping and fail-closed behavior for multiple nested entities.
- Existing mutation-bridge Cursor correlation remains the authoritative writer gate.

## Next device pass

Use one combined pass only:

1. Track Auto-DJ once from a Queue/track row. Expected progression: Queue Cursor mapping -> native Play verified -> Queue entity hint from the queue-specific relation contract -> correlated native entity set -> seed isolation verified -> native Auto-DJ refill.
2. Queue Flip once. Expected progression: Queue Cursor mapping -> Queue mutation mapping -> reverse verified with the same current queue entry.
3. Playlist Play Flipped only needs observation if it still appears wrong to the maintainer. The previous log already proves reversed native input, reversed Queue order and current-first Cursor state, so do not remap the MusicService method blindly. If the visible/audible result still disagrees, capture that same action in the consolidated log and diagnose the discrepancy as a post-playback/order-mode issue rather than a failed interception.
