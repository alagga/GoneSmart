# GMMP 4.2.1 Queue compatibility — r36 verified current-state host

## Device evidence from r35

The r35 device pass preserves the already-solved Queue DAO/entity boundary:

- the read-only Cursor reports the live Queue successfully;
- the current row is repeatedly resolved as `field:t->ur.method:b`;
- `GMDatabase_Impl.F():sx3` resolves the native Queue DAO implementation `vx3`;
- generated `queue_table` adapters prove native entity `cy3`;
- `sx3.H1()` executes `SELECT DISTINCT * FROM queue_table` and returns the native entity rows.

The r35 typed-delete fix is **not reached**. There is no `QUEUE DELETE ARRAY` marker. Resolution stops first at:

`GMMP current-position state binding is not unique | fields=none | methods=none`

Track Mix and Queue Flip fail at the same boundary, before any Queue writer runs.

The submitted Queue already contains 7213 rows before Track Mix starts, so this pass does not show a new 2 -> 7213 expansion caused by r35 discovery or mutation.

## Correction of the r34 state mapping

An earlier r34 pass logged:

`state=dx3.method:D->c2`

That is no longer accepted as semantic identity. In r35 the independent read-only Queue resolver still identifies the actual current pointer as `qr.t -> ur.method:b`, while the old value-only mutation scan can no longer correlate `dx3` once the current Queue position is 19.

The r34 `dx3.D->c2` result was therefore an accidental value correlation: an unrelated integer happened to equal the current Queue position in that run. A writable state boundary must not be selected merely because another object's integer currently has the same value.

## r36 rule: read and write must stay on the same proven state host

Mutation state resolution now follows the same ownership rule used elsewhere in 4.2.1 compatibility work:

1. `qr.t` is checked first because the accepted read-only Queue resolver repeatedly proves that host by Cursor correlation.
2. If that host exposes integer read evidence equal to the Cursor-derived current Queue position, write discovery is locked to that same host.
3. A no-arg integer getter is accepted only after its current value correlates with the independent Queue Cursor.
4. One-argument integer/void writer candidates are considered only on that same host.
5. A writer declared directly by the runtime state class wins over unrelated inherited writer contracts when it is unique.
6. Multiple directly-owned writers remain ambiguous and fail closed.
7. Once `qr.t` has read evidence, resolution must **not** fall through to `qr.p/dx3` or another object.
8. `qr.p` remains a compatibility fallback only when `qr.t` provides no Cursor-correlated integer evidence at all, preserving support for earlier 4.2.1 runtime shapes.
9. No candidate writer is invoked during discovery.

This removes the false `dx3` state mapping without hard-coding `ur.b` as semantic identity.

## Failure-only diagnostic

If the proven state host has no uniquely writable boundary, r36 emits:

`QUEUE CURRENT POINTER SHAPE`

The marker records only bounded structural/read-only evidence:

- source host (`field:t`, `field:p`, or structural fallback),
- runtime host class,
- Cursor-derived current Queue position,
- integer fields currently matching that value,
- no-arg integer getters currently matching that value,
- one-int/void setter signatures,
- directly-owned setter signatures.

No setter is called by the diagnostic.

## Automated gate

Host tests cover:

- one directly-owned state setter winning over an inherited one-int writer contract;
- multiple directly-owned setters failing closed;
- a host whose integer state does not match the Cursor-derived current position producing no read evidence.

The existing r35 test continues to require a typed native entity array (`cy3[]` at runtime) for the erased Queue delete bridge.

## Expected next device pass

Run Track Mix once only.

Two acceptable outcomes exist:

### Writable `ur` boundary is unique

Expected progression:

- `MIX PLAY VERIFIED`
- `QUEUE DAO MAPPING | dao=vx3`
- `QUEUE ENTITY TYPE | model=cy3`
- `QUEUE MUTATION MAPPING | ... | state=ur.method:...->... | reader=native-list:sx3.H1`
- `QUEUE DELETE ARRAY | ... | runtime=cy3`
- `QUEUE MUTATION | seed isolation verified`
- normal Auto-DJ refill and verification.

### Writable `ur` boundary remains ambiguous

Expected safe failure:

- `QUEUE CURRENT POINTER SHAPE | source=field:t | host=ur | ...`
- no `QUEUE DELETE ARRAY`
- no Queue writer invocation.

That one marker is sufficient to identify the remaining native setter boundary without another broad compatibility probe.
