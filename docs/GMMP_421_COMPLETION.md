# GMMP 4.2.1 completion matrix

Date: 2026-10-07
Release: GoneSmart 0.4.0

This is the concise final acceptance state for the GMMP 4.2.1 migration. Historical investigation detail remains in the dated `GMMP_421_*` files and `docs/NATIVE_GMMP_AUDIT.md`.

## Accepted feature/boundary matrix

| Feature / boundary | State | Final note |
| --- | --- | --- |
| Smart DJ queue read | Accepted | Independent queue read with verified Current-position hint. |
| Native Auto-DJ refill | Accepted | `qr.z(int)` semantically verified as refill, never reused as Current writer. |
| Queue DAO/entity | Accepted | `GMDatabase_Impl.F(): sx3 -> vx3`, entity `cy3`, native `H1()` reader, generated `queue_table` adapters. |
| Queue mutation bridge | Accepted | Native typed delete/update writers, independent verification and rollback data. |
| Queue Flip | Accepted | Current writer learned passively from natural playback; controlled reversal independently verified. |
| Play flipped | Accepted | Native playlist/Smart-Playlist playback path with bounded postcondition verification. |
| Track Auto-DJ — normal playlist | Accepted | Native Play, exact seed isolation, Initial Size fill and continuation. |
| Track Auto-DJ — large Smart Playlist | Accepted | Bounded rebuild settling, provisional-CURRENT retarget rule and exact seed preservation/isolation. |
| Track Auto-DJ queue continuation | Accepted | Event-driven `qr.z(deficit)` repair for Track-Auto-DJ-owned normalized queue; no guessed append allocator. |
| Recommendation pool | Accepted | Background prewarm/top-up; successful fills are not subject to failure backoff. |
| Playlist folders | Accepted | Native rows/actions/styles/writers preserved. |
| Smart-Playlist folders | Accepted | Native Smart models/rows remain authoritative; GoneSmart owns grouping/navigation only. |
| Playlist multi-selection | Accepted | Native add operations and one aggregate result. |
| Smart-Playlist multi-selection | Accepted | Verified move/selection behavior with link safety. |
| Playlist Link | Accepted | Live ordinary-playlist membership rule; disabled state remains GMMP-compatible/fail-closed. |
| Navigation/player badges | Accepted | Verified anchors cached; recovery scans bounded. |
| Status / Compatibility UI | Accepted | One Status card; tested version is 4.2.1; untested installed versions amber. |
| Accepted-version diagnostics | Accepted | Broad probes/log floods retired or gated; hot-path cost retired. |

## Track Auto-DJ final behavior

1. GMMP's real native Play action is dispatched for the selected row.
2. The generic track menu gets a bounded completion guard because Smart-Playlist playback can expose temporary queue states.
3. A first CURRENT is provisional: if it survives the rebuild uniquely, it is preserved; if it disappears completely inside the guard and another playback identity is independently observed, GoneSmart retargets and restarts settling.
4. After the guard, playback changes fail closed rather than being followed.
5. The exact native Queue entity is isolated through proven DAO writers and verified Current.
6. Initial Size includes the seed, so only the missing count is requested.
7. Recommendation preparation gets a short head start but never blocks native playback for a long provider round.
8. Once the normalized Track-Auto-DJ queue reaches a real upcoming deficit, a verified natural CURRENT event triggers one native `qr.z(deficit)` continuation request. No polling and no guessed hidden allocator.

The maintainer's final device pass reports the 0.4.0 feature set functioning after r44.

## Performance / cleanup result

- Queue-writer discovery becomes a no-op after proof.
- Playlist and Smart-Playlist pages do not perform expensive work while offscreen.
- Full view-tree scans are bounded recovery, not normal pager/layout behavior.
- Player/navigation badge target and glyph work is cached.
- Play-flipped no longer carries discovery-era Queue polling.
- Track Auto-DJ pre-action queue barriers run off the UI thread.
- Smart recommendation pools prewarm/top-up before depletion.
- User-facing success/error popup fallbacks are readable text rather than symbol-only status.
- No temporary workflow/script belongs in the release tree.

## Dynamic / structural audit

No known enabled 4.2.1 feature still depends on an unsafe guessed state-changing writer.

- Queue ownership is established from generated `queue_table` adapters plus independent queue read evidence.
- Current-position writing is passively learned from natural playback and verified before use.
- `qr.z(int)` is permanently classified as refill, not Current mutation.
- Playlist/Smart-Playlist features reuse native models/actions/writers where available and fail closed where a native boundary is unresolved.
- Unknown future GMMP versions use the bundled compatibility workflow and are shown as untested until accepted.

## Main integration audit

The divergent `main` history was integrated with a real merge commit instead of being overwritten, squashed or force-updated. The post-merge audit explicitly checked for non-conflicting duplicate implementations as well as ordinary conflict markers. Three stale merge leftovers were removed: an older duplicate player-badge color method, a duplicate live multi-selection settings call and an obsolete diagnostic reporter; a second legacy `buildUiPage()` implementation was also removed after exact-head compilation detected it.

For future divergent-branch integrations, a clean textual merge is not sufficient evidence by itself: run the full compile/test gate and inspect for duplicate methods or older parallel implementations that Git can merge without raising a conflict.

## Known release limitations

- GoneSmart hooks obfuscated GMMP internals; versions other than 4.2.1 are not automatically supported.
- Provider/network/metadata availability still affects Smart DJ recommendation coverage; native GMMP fallback remains intentional behavior.
- LSPatch is documented but less thoroughly tested than the recommended rooted Vector setup.

## Release gate

The release may be tagged only after the final feature head and the merged `main` head both pass the normal Build workflow. The signed public APK is produced only by `.github/workflows/release.yml`.


## Post-release compatibility corrections

- 8 October 2026: Playlist Link was found broken on the shipping 4.2.1 path because its bridge still depended on the old 4.2.0 obfuscated presenter/rule/parser/query/DAO map. A planned 0.4.2 repair replaces that all-or-nothing name map with shape-validated runtime bindings. This item is **not** re-accepted until the repaired add/save/reopen/evaluate/edit flow is verified on device.
