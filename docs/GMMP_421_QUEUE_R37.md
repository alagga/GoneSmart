# GMMP 4.2.1 Queue compatibility — r37 deferred pointer write

## Device evidence from r36

The 2026-10-04 r36 Track Mix pass confirms the current-position architecture more precisely:

- Native Play reduces the live Queue from the pre-existing large queue to exactly two rows before mutation.
- The accepted read-only Queue Cursor identifies the selected seed as current at `queue_position=1`.
- Queue DAO ownership remains `vx3`, generated Queue entity remains `cy3`, and the native row reader remains intact.
- `qr.t -> ur.b():int` still correlates with the independent Cursor and reports the current pointer correctly.
- `ur` exposes no writable integer boundary at all: no matching field, no `int -> void` setter and no directly-owned numeric writer.
- r36 therefore fails closed before `QUEUE DELETE ARRAY`; final Queue size remains two rows and no refill is observed.

The failure-only marker is:

`QUEUE CURRENT POINTER SHAPE | source=field:t | host=ur | current=1 | ... getters=ur.b():int | setters=none ...`

This proves that `ur` is a read-signal host, not necessarily the command owner.

## r37 correction: do not require a write when the pointer does not move

Track Mix seed isolation removes every stale Queue row and normalizes the surviving current row to position 1.

If Native Play has already made that row current at position 1, the native current pointer does not change. Requiring a writable current-position binding in that case is unnecessary and blocks an otherwise valid native mutation.

r37 therefore resolves Track Mix in this order:

1. Read the Queue and current row through the accepted Cursor path.
2. Resolve the Queue DAO/entity/update/delete boundaries as before.
3. If the current row is already at `queue_position=1`, do **not** resolve or invoke any current-position writer.
4. Delete stale rows through the original native DAO delete writer.
5. Normalize the surviving native Queue entity to position 1 through the original native DAO update writer.
6. Re-read the Queue through the independent Cursor and require exactly one row with the same queue ID, selected song ID and CURRENT state.

The r35 typed `cy3[]` delete-array fix remains unchanged and should now be reached by the next Track Mix pass.

## Split reader/writer rule for actual pointer moves

Queue Flip still needs a writer when reversing the Queue moves the current row to another position.

r36 established that the verified reader may be a separate native object. r37 therefore permits a split binding only under a narrow rule:

- reader: the already cursor-correlated state host (`qr.t -> ur.b()` in the observed build);
- writer owner: the Auto-DJ runtime object itself (`qr`), never another coincidentally matching state object;
- writer shape: exactly one directly-declared, non-static `int -> void` method on that owner;
- no obfuscated method name is used as semantic identity;
- after a real value change, the verified reader must synchronously report the requested value or the mutation fails and rollback is attempted through the same native writer.

The r36 prohibition against falling through from verified `ur` read evidence to unrelated `dx3` state remains in force.

## Automated gate

r37 adds JVM coverage that:

- one directly-owned `int -> void` writer is selected even when an inherited integer writer also exists;
- multiple directly-owned integer writers fail closed;
- the existing typed native delete-array tests remain unchanged.

## Next device pass

Run Track Mix once only.

Expected path:

- `MIX PLAY VERIFIED`
- `QUEUE DAO MAPPING | dao=vx3`
- `QUEUE ENTITY TYPE | ... model=cy3`
- `QUEUE MUTATION MAPPING | ... state=deferred-if-needed`
- `QUEUE DELETE ARRAY | ... runtime=cy3`
- `QUEUE MUTATION | seed isolation verified`
- normal Auto-DJ refill / Mix verification

If it fails after `QUEUE DELETE ARRAY`, the next investigation is downstream of the state-binding problem and should target only the native delete/update/postcondition boundary.

Queue Flip does not need another device test until Track Mix crosses seed isolation.
