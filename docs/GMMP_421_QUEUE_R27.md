# GMMP 4.2.1 queue mutation mapping — r27

Date: 2026-10-03

This note continues `docs/GMMP_421_QUEUE_R26.md` for the active 4.2.1 Queue Flip / Track Auto-DJ mutation boundary. Fold r26/r27 into `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` during the next documentation consolidation; until then this file is the current handoff for this boundary.

## Device evidence from the r26 build

The 2026-10-03 device log keeps the already-graduated read/playback boundaries green:

- Queue menu injection is present and Queue Flip reaches the verified read-only four-row Queue snapshot.
- Track Auto-DJ dispatches the selected row's original native Play action and reaches `MIX PLAY VERIFIED` for the selected native queue entry.
- Both actions then fail at the same `GmmpQueueMutationBridge.resolve` writer-entity boundary.
- The bounded runtime shape again shows Queue DAO `d85`, direct generated adapter fields `d85.v:d85$a`, `d85.w:d85$c`, `d85.x:d85$d`, and direct adapter methods `G(yb4,Object):void` plus `M():String`.
- No `QUEUE ENTITY TYPE` or `QUEUE MUTATION MAPPING` success marker was emitted. The fallback API witness remained `ww3`, which must not be treated as writer ownership.

This means Room/Cursor/current-entry mapping, selected-track playback verification and the menu/UI hook are not the cause of the current failures.

## r27 code defect found without another device probe

The r26 generated-adapter resolver intentionally included synthetic/bridge methods from the complete adapter hierarchy. It then required the resulting erased `bind(statement,Object)` candidate set to contain exactly one method.

That uniqueness check was too broad: an already-owned generated adapter may declare its own erased entity bind while inheriting additional erased helper/binder methods from a Room/R8 base class. Such inherited methods are not competing ownership evidence for the adapter's entity and can make the old `singleOrNull()` reject the adapter before the direct bind bridge is even invoked.

r27 narrows the boundary instead of adding another obfuscated-name mapping:

1. collect the same structural two-argument erased bind candidates;
2. if the already-owned generated adapter declares one itself, use only directly declared candidates for uniqueness;
3. consult inherited binders only when the adapter declares no matching binder;
4. retain the existing SQL/DAO-ownership proof, generic-metadata path, wrong-marker ClassCast proof, all-adapters-agree requirement, Cursor correlation and fail-closed behavior;
5. do not construct a concrete `yb4`, execute SQL or call a queue writer during discovery.

This is intentionally independent of the names `d85`, `d85$a`, `G` and `yb4`; those remain evidence from the tested host, not compatibility keys.

## Automated gate

`NativeQueueEntityAdapterTypeResolverTest` now includes an adapter whose superclass deliberately contributes a second erased binder with another entity type. The resolver must choose the directly declared adapter binder and recover the Queue entity, while the existing ambiguous-two-owned-adapters test must still fail closed.

All previous synthetic bridge, concrete-statement, generated binding, Queue Flip planning and Track Mix tests remain in the suite.

## Probe lifecycle and next device check

r27 adds no broad runtime probe. The existing failure-only `QUEUE MUTATION SHAPE`, `QUEUE REACTIVE SHAPE` and `QUEUE ENTITY FACTORY` diagnostics remain sufficient if the narrowed resolver still cannot prove a writer entity. A successful run should instead emit `QUEUE ENTITY TYPE` followed by `QUEUE MUTATION MAPPING`; after Queue Flip and Track Auto-DJ are both verified, the broad factory/reactive failure diagnostics can be retired.

Use one consolidated device pass for this build:

1. Track Auto-DJ once from a track/queue row and confirm the selected track becomes the fresh seed and native refill reaches the configured Initial Size.
2. Queue Flip once and confirm the complete queue reverses while the same current native entry remains current.
3. If Playlist / Smart-Playlist `Play Flipped` is still reported broken on GMMP 4.2.1, exercise one representative playlist in the same pass; this path is independent from the shared Queue DAO mutation bridge and should be diagnosed separately rather than mixed into entity discovery.

Do not repeat the already accepted Smart DJ, Playlist folders, Smart-Playlist folders, creation, Add picker, selection-color or navigation tests for r27.
