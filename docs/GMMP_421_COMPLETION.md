# GMMP 4.2.1 completion matrix

Date: 2026-10-07

This document records the final device-verified state of the GMMP 4.2.1 compatibility migration before the `feature/playlist-bridge` branch is retired. Investigation chronology remains in the dated `GMMP_421_*` documents; this file is the concise acceptance state.

## Accepted

| Feature/boundary | 4.2.1 state | Evidence / implementation note |
| --- | --- | --- |
| GoneSmart Smart DJ queue read | Accepted | Independent read-only Cursor mapping; Current correlated through the verified native position signal. |
| GoneSmart Auto-DJ refill | Accepted | Native refill boundary `qr.z(int)`; GoneSmart selection remains native-insertion compatible. |
| Queue DAO/entity ownership | Accepted | `GMDatabase_Impl.F(): sx3 -> vx3`, entity `cy3`, native `H1()` reader, generated adapters naming `queue_table`. |
| Queue mutation bridge | Accepted | Native typed delete/update writers, exact Cursor correlation, postcondition verification and rollback data. |
| Queue menu: Flip queue | Accepted | Current-position writer is learned passively from natural GMMP playback and independently verified before controlled use; observed 4.2.1 writer is `dx3.c2(int)`, but the name is evidence only. Device verification confirmed queue movement works. |
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
| Companion Status card | Implemented + JVM covered | One status card; overall status and individual GMMP/Xposed/GoneSmart/Compatibility sections use independent red/amber/green health. |
| Compatibility logging | Accepted cleanup | Broad class/runtime/Recycler inventories are disabled for accepted 4.2.1; compact success/failure diagnostics remain. |
| Accepted-version performance cleanup | Accepted implementation | Queue-writer discovery retires after proof; folder row work is event-coalesced/foreground-only; persistent player/navigation badges reuse verified native anchors; Play-flipped verification is a single bounded postcondition read. |

## Queue Flip final contract

Queue Flip is intentionally stricter than ordinary Queue reading because reversing the queue can move the currently playing row to another position.

The final 4.2.1 flow is:

1. start from an independently Cursor-verified Queue snapshot;
2. correlate native Queue entities 1:1 with `queue_id`, `queue_track_id`, `queue_position` and `queue_shuffle_position`;
3. learn the native current-position writer only from a **natural GMMP playback transition** — no candidate is invoked merely to test it;
4. require an independent native position signal/postcondition before promoting the writer;
5. reverse positions through GMMP's proven native Room writer;
6. update Current through the passively verified native state writer when required;
7. re-read the Cursor and verify reversed queue IDs plus the same Current queue identity;
8. retain rollback data until all postconditions succeed.

Observed names such as `dx3.c2(int)` are version evidence, not semantic identity. `qr.z(int)` remains explicitly excluded because it is the Auto-DJ refill boundary.

After the writer has been proven for the live Auto-DJ instance, all discovery hooks become cheap pass-throughs. They must not continue reflection/readback work on every playback callback.

## Final cleanup status

- [x] Status health is one large card with an aggregated overall status plus per-section red/amber/green backgrounds.
- [x] Compatibility source-of-truth is GMMP 4.2.1.
- [x] Broad 4.2.1 discovery logs are retired/gated; compact self-test/failure diagnostics remain.
- [x] `AGENTS.md` and the compatibility playbook describe native-first/semantic resolver rules.
- [x] Track Auto-DJ is verified from normal Playlist and large Smart Playlist, including exact Initial Size.
- [x] Queue Flip is device-verified and no longer an open 4.2.1 boundary.
- [x] Queue-writer discovery no longer performs broad state/readback work after proof.
- [x] Playlist and Smart-Playlist folder overlays avoid expensive row/model work on attached offscreen tabs.
- [x] Smart-folder visible-row reflection is coalesced from native scroll/refresh events instead of every pre-draw.
- [x] Player/Now-Playing badge no longer rescans the decor tree or rerasterizes the glyph on every monitor/layout tick.
- [x] Playlist/Smart navigation badges reuse semantically validated native TextViews during layout waves and fall back to discovery only when needed.
- [x] Play-flipped postcondition verification no longer polls Queue state repeatedly after an accepted native launch.
- [x] Temporary cleanup workflow/script removed from the repository.
- [ ] Before branch retirement, re-check the normal Build workflow on whatever commit is then the exact branch head.

## Dynamic / structural audit result

No known enabled 4.2.1 feature still depends on an unsafe guessed state-changing writer.

- Critical Queue ownership/entity discovery is semantic: generated `queue_table` adapter SQL + native entity reader + independent Cursor correlation.
- Current-position writing is passively learned from natural GMMP behavior and independently verified; candidate methods are never actively probed.
- Playlist native playback resolves by structural method shape, retaining tested obfuscated names only as preferred fast paths where useful.
- Auto-DJ selection tries tested names only as fast paths and falls back to a unique structural boundary; ambiguity fails closed.
- Exact obfuscated hooks remain in some high-risk UI/native boundaries where choosing an arbitrary signature-equivalent method would be less safe. Those hook families are isolated and fail closed rather than taking down unrelated GoneSmart features.
- Future unknown GMMP versions use the bundled compatibility self-test/probe workflow; accepted-version deep inventories are no longer emitted continuously.
- Graduated discovery must also retire its **runtime cost**, not just its log messages. Accepted-version rendering/playback hot paths may not keep broad reflection, SQL readbacks or model reconstruction running merely for diagnostics.

## Performance audit result

The final 4.2.1 log/code review found four GoneSmart-side hot-path classes worth retiring:

1. Queue current-position writer discovery could repeatedly invoke generic zero-arg state getters. Some GMMP getters perform queue SQL internally, producing hundreds of `w6` queries per second and visible frame loss. The accepted path now prefers the cheap verified native position signal, bounds generic fallback once per candidate, uses cheap-only delayed checks and becomes a no-op after proof.
2. Playlist/Smart-Playlist folder overlays retained some pre-draw work even on attached offscreen ViewPager pages. Expensive visible-row reflection/alignment is now coalesced from scroll/refresh events and periodic fallback refreshes run only on the foreground page.
3. Persistent player/navigation badge helpers could repeat full view-tree discovery during layout waves. The player badge now caches its native Now-Playing/playback-mode anchors, caches drawable analysis and reuses drawing objects; the Playlist/Smart navigation badge keeps weak semantically validated target references and caches adapter-getter reflection. Structural discovery remains the recovery path when native views change.
4. Device-accepted Play-flipped playback still ran a discovery-era postcondition polling loop. Normal runtime now performs one delayed Queue verification plus at most one legacy fallback, with no repeated polling.

The supplied pre-cleanup log also showed that GoneSmart's own explicit log noise was concentrated in the player-badge diagnostics; those accepted-version target/glyph/mode/hidden info lines are now retired. The remaining `w6` lines emitted when GMMP itself legitimately opens/refocuses native library tabs may remain visible. The goal is to stop causing unnecessary queries, not hide them.

## Durable 4.2.1 lessons

1. Generated Room adapter SQL is stronger ownership evidence than historical field/class names.
2. Read-only Cursor state should remain the independent oracle before and after native mutation.
3. Native entity readers are preferable to reconstructing Room entities manually when ownership is proven.
4. R8 bridge signatures may erase array component types; use the verified runtime entity type when the generated/native implementation requires a typed array.
5. A method's shape alone is not its semantic identity: `qr.z(int)` is the canonical example.
6. Large Smart-Playlist playback may expose stable-looking intermediate queues; Track Auto-DJ must preserve the clicked target across the complete asynchronous native rebuild.
7. GMMP Initial Size includes the seed; use the native refill boundary once with the exact missing count instead of chaining ordinary upcoming refills.
8. Passive discovery is not free: after a boundary graduates, remove both diagnostic noise and repeated reflection/SQL work from normal runtime hot paths.
9. UI observation is not free either: cache semantically proven native anchors and make full view-tree scans a recovery path, especially from `OnGlobalLayout`/pager callbacks.
10. Device-accepted actions still need verification, but that verification should be one bounded postcondition check rather than a discovery-era polling loop.
