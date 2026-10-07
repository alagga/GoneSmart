# GMMP 4.2.1 Queue compatibility — r32 DAO ownership correction

## Device evidence from r31

The 2026-10-03 r31 Track Mix pass reaches the expected safe failure boundary:

- native Play replaces the old 7213-row queue and the accepted read-only Cursor reports exactly two rows;
- `MIX PLAY VERIFIED` confirms the selected native queue entry is current;
- r31 does not invoke `W1()` / `X1()` during discovery;
- failure leaves the queue at two rows (`finalSize=2`, `refillObserved=false`) before the normal failure path releases the refill hold;
- therefore the r31 discovery itself no longer causes the earlier queue-expansion side effect.

The decisive new evidence is `QUEUE ROOM ADAPTER SHAPE | dao=d85`:

- `d85.v -> d85$a` describes `INSERT ... INTO tracks`;
- `d85.w -> d85$c` describes `DELETE FROM tracks WHERE song_id = ?`;
- `d85.x -> d85$d` describes `UPDATE ... tracks ...`.

Those are Track-table CRUD adapters. The historical Auto-DJ field `qr.q:y75 -> d85` is therefore **not sufficient evidence that `d85` is the queue writer DAO**. Calling its `N/O0/P0` family as Queue writers would be unsafe. The queue-table SQL ownership gate correctly rejected it.

Queue Flip in the same submitted log later reaches the same unresolved writer-owner boundary. Playlist / Smart-Playlist Play Flipped remains accepted and unrelated.

## r32 rule: prove the DAO owner before proving the entity

Queue mutation discovery now resolves ownership in this order:

1. Read Queue identity/order only through the already accepted Cursor path.
2. Treat immediate Auto-DJ custom objects as candidates only.
3. Structurally locate the already-live `GMDatabase` / `RoomDatabase` object from the Auto-DJ instance and inspect **already initialized object fields** cached by that database as additional candidates.
4. A candidate becomes the Queue writer DAO only when one of its generated Room adapters has an erased `(statement,Object)->void` binder shape, its own no-arg SQL description names `queue_table`, and one native entity class is uniquely provable.
5. No database DAO accessor, DAO query, reactive carrier or writer is invoked during candidate discovery.
6. Entity reconstruction must still produce exactly the live Cursor row count and correlate one-to-one on `queue_id + song_id + queue_position` before any original GMMP writer can run.
7. Mutation verification and rollback remain Cursor-based. No direct SQL mutation is introduced.

This deliberately demotes `qr.q` / `d85` from semantic Queue-DAO identity to historical/runtime evidence only.

## Duplicate virtual SQL correction

The r31 adapter diagnostic prints the same adapter SQL boundary twice because reflection can expose both the runtime override and an inherited virtual method. The previous entity resolver treated two `queue_table`-matching `Method` objects as ambiguity even when invoking both yields exactly the same SQL text.

r32 groups queue SQL by normalized SQL text:

- one distinct `queue_table` SQL string is one semantic ownership proof, even if several virtual `Method` objects expose it;
- two different `queue_table` SQL strings on one candidate adapter remain ambiguous and fail closed;
- a method declared directly by the runtime adapter is preferred only for evidence labeling, not semantic identity.

## Inherited generated adapters

`QUEUE ROOM ADAPTER SHAPE` previously described only adapter fields declared directly by the runtime class. r32 also describes generated adapter fields declared by DAO superclasses, but only when the runtime adapter is nested under the class that declares that field and exposes the erased binder shape. This remains failure-only and read-only.

## New failure-only diagnostic

If no already-initialized Queue writer DAO can be proven, r32 emits:

`QUEUE DAO DISCOVERY SHAPE`

It records, with bounded type-only metadata:

- candidate objects that actually expose generated Room adapter shapes;
- fields currently cached by the verified database implementation, including null vs runtime type;
- no-arg custom-return database accessor **signatures only**.

The accessor methods are not invoked. If the real Queue DAO is lazy and its cache field is still null, this one diagnostic should identify the exact remaining ownership boundary for the next resolver revision without another broad Room inventory.

## Automated gate

r32 adds/extends JVM coverage for:

- a Track DAO with `tracks` SQL cannot claim Queue ownership;
- one Queue DAO with `queue_table` SQL wins over that historical Track-DAO candidate;
- duplicate references to the same Queue DAO are deduplicated by object identity;
- two distinct queue-table owners fail closed;
- identical overridden/inherited queue SQL is accepted as one semantic boundary;
- inherited generated Room adapter diagnostics remain visible while unrelated String helpers are not invoked.

## Next device pass

After exact-head CI succeeds, run **Track Mix once only** and capture one full log.

Best-case success path:

- `MIX PLAY VERIFIED`
- `QUEUE DAO MAPPING | ... | proof=queue_table-generated-adapter`
- `QUEUE ENTITY TYPE | source=generated-room-adapter`
- `QUEUE MUTATION MAPPING`
- `QUEUE MUTATION | seed isolation verified`
- normal Auto-DJ refill

If ownership is still lazy/unresolved, the expected safe path is:

- `MIX PLAY VERIFIED`
- `QUEUE DAO DISCOVERY SHAPE | ...`
- Track Mix fails closed without invoking database accessors, `W1/X1`, a query or a writer.

Queue Flip does not need another separate test until this shared writer-owner/entity boundary is crossed.
