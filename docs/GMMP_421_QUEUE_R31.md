# GMMP 4.2.1 Queue compatibility — r31 safe discovery

## Device evidence from r30

The 2026-10-03 r30 device pass still fails before `QUEUE MUTATION MAPPING` for both Track Mix and Queue Flip.

Track Mix sequence:

- the pre-click queue can already contain 7213 rows from the prior failed state;
- native Play correctly replaces that queue and the verified Cursor reports exactly 2 rows;
- `MIX PLAY VERIFIED` confirms the selected track is current;
- mutation discovery then reaches the opaque `d85.W1():xp4` and `d85.X1():jm1` carriers;
- no Cursor-correlated native entity set is obtained;
- before the failure diagnostic completes the queue is again observed with 7213 rows;
- `QUEUE MUTATION MAPPING` never appears and seed isolation fails.

The log does not prove which exact GMMP action causes the second expansion, so r31 does **not** assign causality to W1 or X1. It does prove that invoking unknown DAO/reactive boundaries during mutation discovery is not acceptable: they are not independently proven read-only and the queue changes during the same discovery window.

Queue Flip reaches the same unresolved native entity boundary and fails closed. Playlist/Smart-Playlist Play Flipped remains accepted and is unrelated to this boundary.

## Aborted intermediate path

A temporary r31 experiment aggregated partial W1/X1 emissions without an entity hint. It was reverted before being released for device testing after the full timing sequence above was reviewed. Do not restore that path unless the involved DAO methods are independently proven read-only.

## r31 rule

Queue mutation discovery is now side-effect-free by construction:

1. Queue identity/order comes only from the already verified `GmmpQueueReader` read-only Cursor path.
2. Unknown no-arg Queue DAO methods are metadata only and are never invoked by `GmmpQueueMutationBridge` discovery.
3. A writable model can be nominated only by `NativeQueueEntityAdapterTypeResolver` from a directly owned generated Room adapter whose **own SQL string names `queue_table`**.
4. Entity construction uses the existing fake-binder/constructor proof only after that `queue_table` ownership proof.
5. The reconstructed complete row set still has to correlate one-to-one with the live Cursor on `queue_id`, `song_id`, and `queue_position` before any native writer is eligible.
6. Mutation verification and rollback remain Cursor-based.

No direct SQL mutation is introduced.

## New failure-only diagnostic

If the queue-table adapter cannot yet be resolved, r31 emits:

`QUEUE ROOM ADAPTER SHAPE`

For each directly owned nested generated DAO adapter this records, bounded and read-only:

- adapter field/runtime type;
- its no-arg SQL-description string(s);
- generic superclass/interfaces;
- binder method signatures.

The diagnostic deliberately does not invoke Queue DAO query/reactive methods, create a SQLite statement, call a binder, or invoke a writer.

`QUEUE MUTATION SHAPE` now also records `reactiveInvocation=disabled`.

## Next device pass

Run **Track Mix once only** and capture one full log.

Expected success path:

- `MIX PLAY VERIFIED`
- `QUEUE ENTITY TYPE | source=generated-room-adapter`
- `QUEUE MUTATION MAPPING`
- `QUEUE MUTATION | seed isolation verified`
- normal Auto-DJ refill

Expected unresolved-but-safe path:

- `MIX PLAY VERIFIED`
- `QUEUE ROOM ADAPTER SHAPE | ...`
- `QUEUE MUTATION SHAPE | ... | reactiveInvocation=disabled`
- Track Mix fails closed without the discovery code invoking W1/X1.

If unresolved, the adapter SQL/generic/binder shape is the next mapping input. Queue Flip does not need a separate device pass until this shared native Queue entity boundary is resolved.
