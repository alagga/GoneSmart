# GMMP 4.2.1 Queue compatibility — r33 lazy DAO accessor resolution

## Device evidence from r32

The 2026-10-04 r32 Track Mix log reaches the accepted read-side boundary and fails safely at writer ownership:

- the selected song is confirmed through `MIX PLAY VERIFIED`;
- the accepted read-only Cursor reports two queue rows before mutation;
- failure leaves the queue at two rows and reports `refillObserved=false`;
- Queue Flip later reaches the same shared writer-owner failure.

The important ownership evidence is now complete enough to retire r32's "already initialized database field only" assumption:

- the live Auto-DJ object is `qr`;
- `qr.q` is declared as `y75` and currently contains runtime `d85`;
- the verified database is `GMDatabase_Impl`;
- `GMDatabase_Impl.G():y75` exposes the exact same DAO contract type;
- the database's `m..z` fields are runtime `r15` lazy holders rather than direct DAO objects.

Therefore a scan of already-initialized raw database fields cannot discover the generated DAO instances even when GMMP exposes their native Room accessors.

The r32 diagnostic also continues to prove that the adapters directly visible on `d85` write `tracks`, so `d85.O0(List)` must not be guessed as the Queue writer merely because the 4.2.0 Queue DAO also had an `O0(List)` method. The 4.2.0 implementation is the behavioral oracle, but obfuscated/generic method names are not semantic identity.

## r33 rule: materialize Room DAOs through verified database accessors

r33 extends `NativeQueueDaoResolver` with one bounded, non-mutating native step:

1. Locate the already-verified live `GMDatabase_Impl` through the Auto-DJ database field.
2. Keep already-live Auto-DJ/database objects as cheap candidates.
3. Enumerate only callable methods declared directly by the concrete generated database implementation.
4. An invokable DAO accessor must be non-static, have zero parameters, and return an interface or abstract custom reference type.
5. Invoke each qualifying accessor once. This may instantiate/cache a generated Room DAO, but it does not invoke a DAO query, reactive carrier or writer.
6. Inspect the returned DAO only through the existing generated-adapter SQL/type proof.
7. A candidate becomes the Queue writer DAO only if a generated Room adapter's own SQL names `queue_table` and one entity type is uniquely proven.
8. Multiple distinct `queue_table` DAO owners remain ambiguous and fail closed.
9. Entity reconstruction must still exactly correlate with the accepted Cursor by `queue_id + song_id + queue_position` before any writer is eligible.
10. Existing Cursor post-write verification and rollback remain mandatory. No direct SQL mutation is introduced.

## Why accessor invocation is safe enough for discovery

Room's generated database accessors are the native ownership boundary for lazy DAO construction. The r32 log shows the lazy cache holders but also exposes all generated DAO accessor signatures. Calling such an accessor performs object construction/cache lookup only; it is not equivalent to invoking an unknown DAO method.

The resolver deliberately does **not** invoke:

- parameterized database methods;
- inherited helper/lifecycle methods;
- concrete custom-return helper methods;
- any method on a returned DAO merely to discover rows;
- `d85.W1()` / `d85.X1()`;
- any `List`/array writer.

Returned objects are filtered by the existing `queue_table` Room-adapter proof, so Track/Auto-DJ/other DAOs may be instantiated but cannot claim Queue ownership from type/name coincidence.

## Automated gate

`NativeQueueDaoResolverTest` now additionally proves:

- generated zero-arg abstract/interface DAO accessors are invoked and their returned DAOs become candidates;
- a `queue_table` DAO wins over a Track DAO after accessor materialization;
- parameterized methods are not invoked;
- inherited methods are not invoked;
- concrete helper-return methods are not treated as DAO accessors;
- existing duplicate-object and multiple-Queue-owner fail-closed behavior remains intact.

## Expected next device result

One Track Mix action remains sufficient.

Best case:

- `MIX PLAY VERIFIED`
- `QUEUE DAO MAPPING | ... | proof=queue_table-generated-adapter`
- `QUEUE ENTITY TYPE | source=generated-room-adapter`
- `QUEUE MUTATION MAPPING`
- `QUEUE MUTATION | seed isolation verified`

If no generated DAO accessor owns a `queue_table` entity adapter, `QUEUE DAO DISCOVERY SHAPE` now contains the materialized DAO candidates and their adapter SQL in one bounded record. That result would prove that GMMP 4.2.1 mutates the queue through a custom query/transaction boundary rather than a normal queue entity adapter, and the next resolver can be derived from the same log without returning to broad Rx/Room probing.
