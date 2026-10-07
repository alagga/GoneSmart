# GMMP 4.2.1 Queue compatibility — r35

## Device evidence from r34

The r34 device pass crossed the previously unresolved native entity boundary completely:

- Queue writer DAO: `vx3`
- Native queue entity: `cy3`
- Native synchronous entity reader: `sx3.H1(): ArrayList`
- Full native mapping reached successfully:
  - `queueId=d`
  - `trackId=b`
  - `position=a`
  - update writer `vx3.O0(List)`
  - delete writer `vx3.O(Object[])`
  - state binding `dx3.method:D->c2`
  - reader `native-list:sx3.H1`

The native reader itself is confirmed safe for this boundary: GMMP executed `SELECT DISTINCT * FROM queue_table`, and the returned `cy3` rows correlated against the independent read-only Cursor before writer selection.

## r34 failure

The first delete invocation failed before the delete could execute:

`ClassCastException: java.lang.Object[] cannot be cast to cy3[]`

The reflected writer signature is erased as `vx3.O(Object[])`, but its generated implementation internally casts the array to the concrete Room entity array `cy3[]`.

The previous bridge built the argument array from the reflected writer parameter component type. Because that component is `Object`, it created an actual `Object[]`. Passing that array through the erased R8/Room bridge therefore failed at the internal `cy3[]` cast.

## r35 rule

For generated Room array writers, the runtime array component must come from the **already verified native entity model**, not from an erased bridge parameter.

r35 therefore:

1. builds the delete argument as `Array.newInstance(currentModel.javaClass, count)`,
2. verifies every value is an instance of that exact native entity type before invocation,
3. still invokes GMMP's original native delete writer,
4. logs both the erased declared component and real runtime component for the next device pass.

Expected 4.2.1 diagnostic:

`QUEUE DELETE ARRAY | writer=O | declared=java.lang.Object | runtime=cy3 | ...`

This produces a true `cy3[]`, which is assignable to the erased `Object[]` parameter and also survives the generated writer's internal cast back to `cy3[]`.

## Safety boundary unchanged

No direct SQL mutation is introduced. Queue identity remains independently verified through the read-only Cursor, entity identity remains proven through the generated `queue_table` Room adapter and binder, and only native GMMP DAO writers are invoked.

The queue expansion observed after the r34 exception (2 -> 7213 rows) occurred after Track Mix aborted and released its refill barrier. r35 removes the concrete exception that caused that abort; no speculative refill behavior is changed in this revision. If a later writer stage fails independently, failure containment can be hardened from that direct evidence rather than masking the now-understood delete bridge bug.

## Next device pass

Run Track Mix once. The important sequence is:

1. `QUEUE MUTATION MAPPING ... reader=native-list:sx3.H1`
2. `QUEUE DELETE ARRAY ... declared=java.lang.Object | runtime=cy3`
3. `QUEUE MUTATION | seed isolation verified`
4. `MIX SEED | queueSize=1`
5. `MIX AUTO-DJ | command=sent`
6. `MIX VERIFIED ...`

Queue Flip does not need a separate pass until this shared mutation path succeeds.
