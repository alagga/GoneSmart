# GMMP 4.2.1 queue mutation mapping — r26

Date: 2026-10-02

This note extends `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` for the active 4.2.1 Queue/Track Auto-DJ boundary. It is kept separate for this pass so the resolver/test change can stay one atomic Git-data commit; fold it into the chronological playbook on the next documentation consolidation.

## Device evidence that is now graduated

The r25 host log proves the read side is no longer the blocker:

- `GMDatabase_Impl -> f94.q(p94): android.database.Cursor` resolves and GoneSmart loads the library through the read-only Cursor.
- Queue snapshots resolve through the same Cursor, including Current through `qr.t -> ur.method:b`.
- Track Auto-DJ reaches `MIX PLAY VERIFIED` for the selected native row before isolation fails.
- Queue Flip reaches the same successful queue snapshot before mutation fails.

Do not reopen the Room/Cursor/current-position mappings for these failures.

## Shared r25 failure

Both Track Auto-DJ and Queue Flip fail in `GmmpQueueMutationBridge.resolve` because the writer entity is still unresolved. The bounded failure reports:

- DAO implementation: `d85`.
- generated adapter fields: `d85.v:d85$a`, `d85.w:d85$c`, `d85.x:d85$d`.
- each generated adapter exposes `G(yb4,Object):void` plus `M():String`.
- queue-specific API witness: `ww3`, constructor `ww3(pw3,String,Object)`.
- read carriers remain `d85.W1():xp4` and `d85.X1():jm1`.
- writer families include `d85.N(List)`, `d85.O0(List)` and concrete `d85.P0(Object[])`.

`ww3` remains a relation/API wrapper, not writer-entity ownership.

## r26 resolver contract

1. **Generated Room bridge exception:** the global reflection policy continues to exclude synthetic methods. Only inside an already-owned generated Room adapter may the resolver inspect synthetic/bridge methods, because the erased `bind(statement,Object)` method is itself the semantic Room entity boundary. No broad host reflection policy is weakened.
2. **Embedded wrapper fallback:** when the nearest queue-specific array witness is not itself a four-number queue entity, it may be unwrapped only if its constructors contain exactly one non-platform custom type and that nested type has the four-number entity shape. This structurally covers the observed `ww3(pw3,String,Object)` without pinning either R8 name.
3. **Concrete statement fallback:** GMMP 4.2.1 uses concrete `yb4`, so GoneSmart must not construct a real SQLite statement merely to inspect an entity. After entity ownership is independently established, constructor permutations are tested in memory against the already-verified live Cursor values for `queue_id`, `queue_track_id`, `queue_position` and `queue_shuffle_position`.
4. **Fail closed on ambiguity:** four distinct numeric fields must explain all four columns for all witness rows. Equivalent permutations are collapsed only when they produce identical native numeric field values. Any different valid shape aborts mapping.
5. **Original native writers only:** no SQL write is introduced. A reconstructed entity set must still pass the mutation bridge's complete row-count and `queue_id + song_id + queue_position` correlation before `d85` update/delete is eligible. Existing post-write Cursor verification and rollback remain mandatory.

## Automated gate

The JVM suite now covers:

- a true generic JVM adapter whose erased `G(statement,Object)` is synthetic/bridge;
- strict unwrapping of a wrapper with one embedded four-number queue entity;
- binder-independent numeric reconstruction and an incomplete-shape fail-closed case;
- all existing Queue Flip, Room query, reactive-carrier and Track Mix state tests.

## Probe lifecycle / next device check

No new broad probe is added in r26. `QUEUE MUTATION SHAPE`, `QUEUE REACTIVE SHAPE` and `QUEUE ENTITY FACTORY` remain failure-only until native mutation succeeds. If r26 emits `QUEUE ENTITY TYPE` and `QUEUE MUTATION MAPPING`, retire the factory/reactive discovery diagnostics in the following cleanup.

The next real-device check remains exactly two actions: Track Auto-DJ once from a Queue row, then Queue Flip once. Do not repeat Smart DJ, Smart folders, Playlist folders, Add picker, selection colors, creation, or navigation tests for this change.
