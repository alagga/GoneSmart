# GMMP 4.2.1 completion matrix

Date: 2026-10-05

This document records the final device-verified state of the GMMP 4.2.1 compatibility migration before the `feature/playlist-bridge` branch is retired. It is a concise acceptance matrix; investigation chronology remains in the dated `GMMP_421_*` documents.

## Accepted

| Feature/boundary | 4.2.1 state | Evidence / implementation note |
| --- | --- | --- |
| GoneSmart Smart DJ queue read | Accepted | Independent read-only Cursor mapping; Current correlated through `qr.t -> ur.b()`. |
| GoneSmart Auto-DJ refill | Accepted | Native refill boundary `qr.z(int)`; GoneSmart selection remains native-insertion compatible. |
| Queue DAO/entity ownership | Accepted | `GMDatabase_Impl.F(): sx3 -> vx3`, entity `cy3`, native `H1()` reader, generated adapters naming `queue_table`. |
| Queue seed isolation for Track Auto-DJ | Accepted | Native typed delete/update writers + Cursor verification. |
| Track Auto-DJ from normal Playlist | Accepted | Native Play, exact seed isolation, single Initial-Size fill, final Current/size verification. |
| Track Auto-DJ from large Smart Playlist | Accepted | Original target survives asynchronous source queue rebuild; bounded completion/quiescence; exact seed isolation afterward. |
| GMMP Initial Size handling | Accepted | Seed counts toward Initial Size; request exactly `initialSize - seedSize` through native refill boundary and verify exact total. |
| Playlist folders | Accepted | Native rows/actions/styles/writers preserved. |
| Smart-Playlist folders | Accepted | Native model/writer behavior preserved with GoneSmart grouping/navigation. |
| Playlist multi-selection | Accepted | Native add/create/navigation boundaries retained. |
| Smart-Playlist multi-selection | Accepted | Native selection/action semantics retained. |
| Playlist Link | Accepted | Portable/fail-closed Smart-Playlist integration; disabled behavior remains GMMP-compatible. |
| Play flipped | Accepted | Structurally resolved native `MusicService` playback flow verified on device. |
| Companion Compatibility policy | Accepted | Tested version source-of-truth is 4.2.1; other installed versions are warning/untested. |
| Companion per-row health colors | Implemented, host-tested/CI required | Neutral parent Status card, one overall divider, independently colored GMMP/Xposed/GoneSmart/Compatibility rows. |

## Explicit open boundary

### Queue menu: Flip queue

The queue rows and native update writer are resolved, but a 4.2.1 native writer for the **current queue position** is not yet semantically proven. Reversing a queue can move the currently playing row to another position, so a correct implementation must update both native row positions and GMMP's Current pointer atomically/consistently.

Known facts:

- `qr.t -> ur.b()` is the verified current-position **reader**.
- `ur` exposes no proven writer.
- `qr.z(int)` is the native Auto-DJ **refill** boundary and must not be used as a current-position setter.
- A previous value-correlation with another host was a false positive and has been removed.
- The current 4.2.1 mutation bridge therefore fails closed rather than guessing a writer.

This is isolated from Playlist **Play flipped**, which is accepted.

## Final cleanup requirements before retiring the branch

- exact-head CI green;
- Status screen compile/behavior clean;
- broad 4.2.1 discovery logs retired/gated, keeping compact mapping/failure diagnostics;
- `AGENTS.md` and compatibility playbook reflect this matrix;
- decide whether to resolve Queue Flip now with one targeted, bundled test or carry it explicitly as the only known 4.2.1 limitation.

## Durable 4.2.1 lessons

1. Generated Room adapter SQL is stronger ownership evidence than historical field/class names.
2. Read-only Cursor state should remain the independent oracle before and after native mutation.
3. Native entity readers are preferable to reconstructing Room entities manually when ownership is proven.
4. R8 bridge signatures may erase array component types; use the verified runtime entity type when the generated/native implementation requires a typed array.
5. A method's shape alone is not its semantic identity: `qr.z(int)` is the canonical example.
6. Large Smart-Playlist playback may expose stable-looking intermediate queues; Track Auto-DJ must preserve the clicked target across the complete asynchronous native rebuild.
7. GMMP Initial Size includes the seed; use the native refill boundary once with the exact missing count instead of chaining ordinary upcoming refills.
