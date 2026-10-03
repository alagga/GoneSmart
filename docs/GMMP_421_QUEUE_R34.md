# GMMP 4.2.1 Queue compatibility — r34 native entity reader

## Device evidence from r33

The r33 device pass crosses the DAO-ownership boundary successfully:

- Track Mix reaches `MIX PLAY VERIFIED` with five Cursor-backed queue rows.
- `GMDatabase_Impl.F():sx3` materializes the real Queue DAO implementation `vx3`.
- `vx3.v / w / x` prove INSERT / DELETE / UPDATE ownership of `queue_table`.
- the generated binder cast proves Queue entity class `cy3`.
- the queue remains unchanged on failure (`initialSize=5`, `finalSize=5`, `refillObserved=false`).

The remaining failure is narrower:

`GMMP queue_table Room entity reconstruction did not correlate with the read-only Cursor`

The same runtime shape also exposes one synchronous native reader on the proven Queue DAO contract:

`sx3.H1(): java.util.ArrayList`

This is structurally equivalent to the native read boundary used by the accepted GMMP 4.2.0 Queue Flip implementation: read GMMP's own Queue entities, mutate those entities through GMMP's own writer, and verify through the independent Cursor snapshot.

## r34 rule: prefer the native Queue entity list

After Queue DAO ownership and Queue entity type are proven, GoneSmart now:

1. Finds zero-argument methods on that already-proven Queue DAO whose return type is `List` / `ArrayList` compatible.
2. If exactly one exists, invokes that read-only native list boundary.
3. Requires the returned row count to equal the accepted read-only Cursor row count.
4. Requires every returned object to be the generated Room entity class already proven by the `queue_table` adapter.
5. Replays GMMP's generated INSERT binder against a fake statement and derives `queue_id`, `queue_track_id`, `queue_position`, and `queue_shuffle_position` for every native row.
6. Requires a complete one-to-one match with the Cursor snapshot on all four values before any writer is eligible.
7. If a native list boundary exists but is ambiguous or fails correlation, fail closed. Do not silently manufacture replacement entities.
8. Constructor permutation reconstruction remains only as a fallback for proven Queue DAO shapes that expose no synchronous native List reader at all.

No direct SQL mutation is introduced. Predicate-array and reactive discovery remain disabled.

## Generated binder parity correction

r33 proved the entity type using generated Room adapter reflection, but entity reconstruction used the broader compatibility reflection policy. That broader policy excludes synthetic bridge methods and could see both the runtime adapter binder and inherited helper binders as ambiguous.

r34 makes reconstruction use the same generated-adapter rules as type discovery:

- synthetic/bridge methods are visible locally at the already-owned generated Room adapter boundary;
- `(statement,Object)->void` is required;
- a binder declared directly by the runtime adapter is preferred over inherited helper binders;
- duplicate virtual SQL descriptions are deduplicated by normalized SQL text;
- only the INSERT adapter whose own SQL contains all four Queue columns can bind/correlate native rows.

## Automated gate

Host tests now cover:

- constructor reconstruction remains available when no native list reader exists;
- a unique native no-arg `ArrayList` reader wins and returns the original native objects;
- an inherited alternate binder contract does not make the runtime generated binder ambiguous;
- a native reader whose Queue IDs do not match the Cursor fails closed rather than falling back to synthetic reconstruction;
- Predicate[] / List query shapes still cannot nominate or reconstruct Queue entities;
- INSERT SQL must expose exactly the four Queue columns used for correlation.

## Expected next device pass

Run Track Mix once only.

Expected progression:

- `MIX PLAY VERIFIED`
- `QUEUE DAO MAPPING | dao=vx3`
- `QUEUE ENTITY TYPE | model=cy3`
- `QUEUE MUTATION MAPPING | ... | reader=native-list:sx3.H1`
- Track Mix seed isolation through the original Queue DAO writer
- Cursor verification of the isolated seed
- normal Auto-DJ refill

If the native reader does not correlate, the failure remains before any Queue mutation and the Queue should remain unchanged.
