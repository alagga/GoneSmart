# GMMP 4.2.1 Queue compatibility — r29

Date: 2026-10-03
Branch: `feature/playlist-bridge`

## Device evidence from r28

The r28 host pass confirms that Playlist/Smart-Playlist `Play Flipped` is working normally and is no longer part of the open compatibility issue.

Track Mix and Queue Flip still fail at the same 4.2.1 Queue DAO read boundary. The important new evidence is:

- read-only Cursor mapping is still healthy;
- Track Mix successfully dispatches/verifies the selected native Play before isolation;
- the r28 fallback reports `entityHint=pw3` and cannot find native rows;
- the concrete Queue DAO is still `d85`;
- the inherited native surface contains `M1(ww3[]): List`, alongside other `ww3[]` query-family methods;
- r28's attempt to interpret the unique custom constructor component of `ww3` as an embedded Queue entity was wrong.

GoneSmart already uses `ww3` elsewhere as GMMP's native where/predicate object. The observed constructor shape `ww3(pw3, String, Object)` is consistent with predicate `(column, operator, value)`, so `pw3` is not valid Queue-entity ownership evidence.

## r29 correction

`NativeQueueEntityTypeResolver` no longer unwraps a custom constructor component from the nearest array contract. The nearest unique array component remains only a query-boundary witness; it is never promoted to Queue writer-entity ownership.

`NativeQueueEntityReconstructor` now prefers GMMP's own structurally unique predicate-array `List` reader before synthetic reconstruction:

1. Find a non-static DAO method with exactly one custom array parameter and `List` return type.
2. Invoke it read-only with a zero-length array of that exact native predicate component type.
3. Require the returned row count to equal the verified Queue Cursor count.
4. Require a homogeneous runtime model.
5. Prove unique `queue_id`, `song_id` and `queue_position` numeric fields one-to-one against the verified Cursor snapshot.
6. Fail closed if more than one independently correlated predicate-list reader exists.
7. The mutation bridge repeats the full Cursor correlation before it can select or invoke the existing native update/delete writers.

No SQL mutation is added. No obfuscated class or method name is used as semantic identity. The observed `M1(ww3[])` shape is evidence only; runtime selection remains structural.

The previous generated Room fake-binder reconstruction remains a secondary fallback if no native predicate-list reader can be proven.

## Host tests added/changed

- Predicate-like array contracts no longer nominate their constructor's custom column descriptor as a Queue entity.
- A generated DAO-style `List` reader with a native predicate vararg is exercised with an empty typed array.
- The test intentionally supplies the predicate type as the old model hint; the native rows must still be recovered and Cursor-correlated from the DAO reader.
- Existing generated-binder reconstruction tests remain in place.

## Next device pass

Only two actions are needed:

1. Track Mix once from a queue row. Expected: native Play verifies, seed isolation succeeds, then Auto-DJ refill proceeds from the selected seed.
2. Queue Flip once. Expected: the entire queue reverses and the same native queue entry remains current.

Do not retest Playlist/Smart-Playlist `Play Flipped`; the user confirmed that it works normally. Do not retest already accepted Smart DJ, Playlist folders, Smart folders, creation, picker, selection colors, or navigation flows.

Useful success markers are `QUEUE ENTITY FACTORY` with a `native-predicate-list:` proof, followed by `QUEUE MUTATION MAPPING` and then either seed-isolation or reverse verification. If the native predicate-list path is absent or rejected, use the existing bounded Queue diagnostics before adding any broader runtime probe.
