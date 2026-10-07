# GMMP 4.2.1 queue mutation mapping — r26

Date: 2026-10-02

This note extends `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` for the active 4.2.1 Queue/Track Auto-DJ boundary. Fold it into the chronological playbook on the next documentation consolidation.

## Device evidence that is now graduated

The r25 host log proves the read side is no longer the blocker:

- `GMDatabase_Impl -> f94.q(p94): android.database.Cursor` resolves and GoneSmart loads the library through the read-only Cursor.
- Queue snapshots resolve through the same Cursor, including Current through `qr.t -> ur.method:b`.
- Track Auto-DJ reaches `MIX PLAY VERIFIED` for the selected native row before isolation fails.
- Queue Flip reaches the same successful queue snapshot before mutation fails.

Do not reopen the Room/Cursor/current-position mappings for these failures.

## Shared r25 failure

Both Track Auto-DJ and Queue Flip fail in `GmmpQueueMutationBridge.resolve` because the native queue writer entity is still unresolved. The bounded failure reports:

- DAO implementation: `d85`.
- generated adapter fields: `d85.v:d85$a`, `d85.w:d85$c`, `d85.x:d85$d`.
- each generated adapter exposes `G(yb4,Object):void` plus `M():String`.
- queue-specific API witness: `ww3`, constructor `ww3(pw3,String,Object)`.
- read carriers remain `d85.W1():xp4` and `d85.X1():jm1`.
- writer families include `d85.N(List)`, `d85.O0(List)` and concrete `d85.P0(Object[])`.

`ww3` must not be assumed to be the writer entity merely because inherited Queue-DAO APIs mention `ww3[]`.

## r26 resolver contract

1. **Generated Room bridge exception:** the global reflection policy continues to exclude synthetic methods. Only inside an already-owned generated Room adapter may the resolver inspect synthetic/bridge methods, because the erased `bind(statement,Object)` method is itself the semantic Room entity boundary. No broad host reflection policy is weakened.
2. **Entity ownership:** a generated `d85` adapter may expose its concrete entity through generic metadata or through the ClassCastException raised by its erased bind bridge when a marker object is supplied. A concrete host statement type such as `yb4` is passed as `null`; GoneSmart never constructs or executes a SQLite statement for discovery.
3. **Relation-row unwrapping:** once the generated adapter independently proves the writer entity class, a Queue DAO reactive read may return a relation/wrapper row rather than the entity directly. GoneSmart may reuse the *existing native embedded object* only when each wrapper contains exactly one field whose runtime value is an instance of that proven entity class. Zero or multiple matches fail closed. This structurally covers the observed `ww3(pw3,String,Object)` shape without pinning either R8 name.
4. **No guessed reconstruction:** the first r26 CI attempt intentionally tested constructor-only numeric reconstruction and disproved it as sufficient evidence: permutations of four numeric constructor values cannot establish which native field semantically means queue ID, track ID, position or shuffle position. That fallback was removed before producing a device build.
5. **Authoritative correlation remains:** unwrapped native entities become writer candidates only after the existing complete Cursor correlation proves row count plus unique `queue_id`, `song_id` and `queue_position` mapping. Writer selection, post-write Cursor verification and rollback remain unchanged.
6. **Original native writers only:** r26 introduces no direct SQL write and does not manufacture a `pw3` object. Mutation still goes through GMMP's original generated DAO methods.

## Automated gate

The JVM suite covers:

- an owned generic adapter whose erased `G(statement,Object)` exists as a synthetic JVM bridge;
- concrete-statement adapter discovery without constructing the statement;
- relation rows containing exactly one proven embedded entity, including a multiple-entity fail-closed case;
- strict wrapper-type unwrapping evidence;
- all existing Queue Flip, Room query, reactive-carrier and Track Mix state tests.

The CI failure from the first r26 draft is retained as a design lesson: tests rejected ambiguous constructor-only reconstruction before any debug APK was produced. The corrected r26 path reuses only host-created native entities.

## Probe lifecycle / next device check

No new broad probe is added in r26. `QUEUE MUTATION SHAPE`, `QUEUE REACTIVE SHAPE` and `QUEUE ENTITY FACTORY` remain failure-only until native mutation succeeds. If the next run emits `QUEUE ENTITY TYPE` and `QUEUE MUTATION MAPPING`, retire the factory/reactive discovery diagnostics in the following cleanup.

The next real-device check remains exactly two actions: Track Auto-DJ once from a track row, then Queue Flip once. Do not repeat Smart DJ, Smart folders, Playlist folders, Add picker, selection colors, creation, or navigation tests for this change.
