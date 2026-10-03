# GMMP 4.2.1 Queue compatibility — r30

## Scope

Open 4.2.1 compatibility work is now limited to the native Queue mutation bridge used by:

- Track Mix seed isolation
- Flip queue

Playlist **Play Flipped** is no longer part of the open bug set. The device pass on 2026-10-03 confirmed that native reversed playlist playback works normally.

## Latest device evidence

The r29 device log proved several important boundaries:

1. Track Mix's native Play stage is correct. The selected track becomes the current queue entry and GoneSmart reaches `MIX PLAY VERIFIED` with a two-row Queue snapshot before seed isolation starts.
2. Seed isolation still fails inside `GmmpQueueMutationBridge.resolve()` before `QUEUE MUTATION MAPPING`.
3. Queue Flip fails at the same native Queue-row reader boundary.
4. The r29 structural `Predicate[] -> List` fallback is invalid. Invoking the observed inherited reader caused GMMP to execute `SELECT DISTINCT * FROM tracks`, not a queue-table query.
5. The custom array family observed as `ww3[]` is therefore a query predicate/where contract, not a queue entity or relation wrapper. Its constructor shape `Predicate(Column, String, Object)` also explains why the previously nominated custom constructor component was not a Queue row.

The r29 predicate reader has been removed rather than merely deprioritized.

## r30 correction

### Array contracts no longer nominate Queue entities

`NativeQueueEntityTypeResolver` no longer converts a nearest custom array contract into an entity hint. In current 4.2.1 this prevents the predicate family from being passed to either reactive row filtering or generated-entity reconstruction.

A future Queue entity type must instead be proven by one of the stronger ownership paths below.

### Generated Room reconstruction remains queue-table gated

`NativeQueueEntityReconstructor` now uses only a generated Room adapter whose own SQL explicitly names `queue_table` and whose fake-binder mapping proves the expected queue columns. The disproven `Predicate[] -> List` reader was removed completely.

No real SQL statement is created or executed by this reconstruction path.

### Deep read-only evaluation of the real Queue DAO carriers

The concrete generated Queue DAO continues to expose two no-argument read boundaries in the tested 4.2.1 build. Their R8 names are diagnostic evidence only; the mutation bridge discovers them structurally.

`NativeReactiveListReader` can now materialize a bounded snapshot through:

- a direct emitted List/array,
- an erased no-arg blocking Object terminal,
- a callback/observer terminal,
- a uniquely nested List snapshot returned by one of those terminals,
- a callback stream containing a uniquely nested queue-shaped row,
- a main-thread observer registration retry only when background registration actually throws.

It does **not** inspect arbitrary private fields of the source object as a direct snapshot. Doing that would bypass the carrier's native terminal semantics.

If no snapshot can be materialized, a bounded failure-only diagnostic is emitted once per runtime carrier class as:

`QUEUE REACTIVE READ FAIL | ...`

That diagnostic records generic superclass/interfaces, erased Object terminals, callback interface signatures, and a bounded field shape. It does not mutate host state.

## Mutation safety gate

None of the read heuristics above authorizes a write by itself.

Before any native writer becomes eligible, `GmmpQueueMutationBridge` still requires:

1. a verified read-only Queue Cursor snapshot,
2. the same native row count,
3. exactly one numeric field matching every live `queue_id`,
4. exactly one numeric field matching every live `song_id`,
5. exactly one numeric field matching every live `queue_position`,
6. exactly one correlated native row set,
7. only then selection of GMMP's original DAO update/delete methods,
8. post-mutation verification through the read-only Cursor, with the existing rollback path for reversible queue reordering.

There is no direct SQL mutation and no writer is selected by a newly guessed R8 name.

## Track Mix failure side effect

The r29 device pass again showed the known secondary failure behavior: after native Play had produced a two-row queue, failed seed isolation eventually released the refill hold while Smart DJ was enabled and the queue grew to thousands of entries. This is downstream of the unresolved Queue-row mutation boundary rather than the initial Play step.

Fixing the shared native Queue mutation bridge remains the primary path. If another reader-only diagnostic pass is needed, containment of failed Track Mix refill should be handled separately rather than hiding the unresolved native mutation failure.

## Next device pass

Only test the still-open features:

1. Track Mix once.
2. Flip queue once.
3. Send one combined log.

Do not retest Playlist Play Flipped or unrelated accepted functionality.

### Success markers

The important new success sequence is:

- `MIX PLAY VERIFIED`
- `QUEUE MUTATION MAPPING | ...`
- for Track Mix: `QUEUE MUTATION | seed isolation verified | ...`
- for Queue Flip: `QUEUE MUTATION | reverse verified | ...`

### If the reader still fails

The expected new evidence is `QUEUE REACTIVE READ FAIL` for the concrete Queue DAO carrier(s), followed by the existing fail-closed mutation-shape log. That one combined log should be sufficient for the next mapping step; no separate probe build should be necessary.
