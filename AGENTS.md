# GoneSmart — persistent coding and collaboration rules

**Purpose:** Canonical entry point for assistants and contributors working on GoneSmart. Read this file before changing code, then inspect the relevant source, docs, current branch head and exact-head CI.

**Last consolidated:** 2026-10-07. **Compatibility target:** GoneMAD Music Player 4.2.1. **Development branch at consolidation:** `feature/playlist-bridge`.

## 1. Collaboration and repository discipline

- Current explicit maintainer instructions override this file. Update this file whenever a standing rule changes.
- Communicate with the maintainer in **German**. Public repository documentation and the companion UI use English.
- Work from the actual GitHub repository; never reconstruct current code from chat snippets alone.
- Check the current branch head before editing. Do not overwrite commits that appeared while work was in progress.
- After every code push, verify the GitHub Actions **Build** workflow for that exact head. The branch is not green until Unit tests, Debug APK, unsigned Release smoke test and Debug APK upload have succeeded.
- Do not merge to `main`, create a signed release or tag a release unless explicitly requested.
- Minimize device-test rounds. Mine existing logs completely, add host tests, and bundle related fixes. Do not produce one APK per small hypothesis when one bounded diagnostic can answer all remaining questions.

## 2. Native-first is mandatory

GoneSmart extends GMMP; it does not replace GMMP internals with parallel implementations.

- Reuse GMMP's native writers, callbacks, adapters, transactions, localized resources and playback flows whenever they exist.
- Never write directly to GMMP SQL/Room tables when a native DAO/writer boundary exists.
- Obfuscated names such as `qr`, `vx3`, `z`, `O0`, `dx3`, `c2` are evidence for a tested APK, **not semantic identity**.
- Resolver order: tested fast path → structural/semantic discovery → uniqueness check → runtime/postcondition verification.
- Ambiguous or unverified state-changing boundaries must **fail closed**. Do not choose a candidate merely because its name/signature looks plausible.
- Read-only observation may be broader than mutation discovery. State-changing code requires stricter evidence.
- Feature hook installation must be isolated: one unavailable hook family must not make unrelated GoneSmart features disappear.

## 3. Compatibility workflow for future GMMP updates

Read `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` before adapting to a new GMMP build.

For an unknown GMMP version, run one bundled, bounded, read-only compatibility self-test rather than many tiny probe builds. It may inspect structural call chains that would be destructive to execute, but must not invoke destructive actions just to discover them.

A compatibility probe has this lifecycle:

`unknown → bounded passive evidence → runtime correlation → semantic resolver → postcondition/device verification → retire/narrow probe`

Deep inventories are temporary. Once a boundary has graduated, ordinary runtime logs should retain only concise success/failure information; detailed reflection dumps should be emitted only for an unresolved/failing boundary or an unknown GMMP version.

**Graduation also retires runtime cost.** A probe is not truly retired if it stops logging but still performs broad reflection, Cursor/SQL readbacks, view-tree scans or model reconstruction on every frame/playback callback. Accepted-version hot paths must become cached/event-driven/pass-through wherever possible.

Diagnostics must be privacy-safe and bounded. Prefer class hierarchy, signatures, declared/runtime types and candidate counts. Do not dump whole libraries, unbounded playlists or filesystem paths merely for compatibility discovery.

## 4. GMMP 4.2.1 queue contract

The 4.2.1 queue mapping was established from independent read-only Cursor evidence and GMMP-generated Room adapter SQL.

- Queue DAO accessor: `GMDatabase_Impl.F(): sx3`, runtime implementation `vx3`.
- Queue entity: `cy3`.
- Native synchronous reader: `sx3.H1(): ArrayList`.
- Generated `vx3` adapters own `queue_table` INSERT/DELETE/UPDATE SQL.
- Native update writer used after proof: `O0(List)`.
- Delete reflection may expose `Object[]`, while the bridge internally casts to `cy3[]`; pass an actual typed runtime `cy3[]`.
- The independent queue Cursor is authoritative for `queue_id`, `queue_track_id`, `queue_position`, `queue_shuffle_position` and post-mutation verification.
- Reactive `W1/X1` DAO methods are not invoked during discovery.
- `qr.t -> ur.b()` remains useful read evidence from the 4.2.1 investigation, but Current mutation must use the passively verified state-writer contract rather than name/signature guessing.
- **`qr.z(int)` is the native Auto-DJ refill boundary. It is not a current-position setter. Never rediscover/use it as one merely because it is an `int -> void` method.**

### Queue Flip status

Playlist **Play flipped** and Queue-menu **Flip queue** are both device-accepted on 4.2.1.

Queue Flip resolves the current-position writer passively from **natural GMMP playback behavior**. A candidate is never invoked just to test it. The writer is promoted only after an independent native position signal/readback proves that the natural call moved Current to the same queue position; controlled use still requires postcondition verification and rollback support. The observed 4.2.1 writer is currently `dx3.c2(int)`, but that obfuscated name is evidence only.

After the writer has been proved for the live Auto-DJ instance, the observer must become a cheap pass-through. Do not keep reflection, delayed discovery callbacks or queue SQL running on every natural playback invocation.

## 5. Track Auto-DJ / song-based Auto-DJ

The 4.2.1 Track Auto-DJ flow is accepted for both normal Playlists and large Smart Playlists.

- Dispatch GMMP's exact native Play action for the selected row.
- Large Smart Playlists can expose a temporary small queue and then asynchronously rebuild the full source list. Preserve the originally selected track identity across that rebuild; do not retarget to a transient CURRENT row.
- Wait for bounded queue quiescence/completion before isolation.
- Isolate the exact native Queue entity through GMMP's proven queue DAO writers and verify the selected track remains Current.
- GMMP Initial Size includes the seed track. After isolation, request exactly `initialSize - seedQueueSize` tracks through the same native `qr.z(count)` refill boundary.
- Suppress GMMP's transitional normal `upcoming` refill during this initial-fill transition, allow only GoneSmart's scoped intentional initial request, and release the hold only after exact Initial Size + Current seed are independently verified.
- If GoneSmart recommendation providers return no suitable local matches, using GMMP's native Auto-DJ fallback is correct behavior; do not lower matching quality merely to avoid fallback.

## 6. Playlist, Smart-Playlist and UI feature rules

- Playlist folders and Smart-Playlist folders must preserve GMMP's native row behavior, styling, localization and writers. Synthetic UI may group/navigate but must not replace native actions where GMMP already supplies them.
- Playlist Link rules must stay portable/fail-closed. When the feature is disabled, previously saved Smart Playlists must remain openable by GMMP and GoneSmart-only semantics must become inert rather than corrupting native parsing.
- Multi-selection must alter only the intended add/move/create action, not suppress unrelated native menu items or navigation.
- UI extensions must adapt to GMMP theme/navigation modes through native styling/evidence rather than fixed colors/layout assumptions where avoidable.
- Attached but **offscreen ViewPager pages must be effectively idle**. Do not do row reflection, model scanning, expensive style sampling or refresh fallback work merely because the native view remains attached.
- Prefer native adapter/scroll/layout events. If a pre-draw listener is unavoidable, keep only genuinely frame-dependent geometry/overscroll work there and gate it to the visible foreground surface.
- Coalesce visible-row synchronization to at most one posted animation-frame update per scroll/refresh burst.
- Long-lived `OnGlobalLayout`/layout listeners must fast-path already proven native anchors. A full decor/view-tree scan is a recovery path for detached, hidden or semantically rebound targets, not normal work during pager animation.
- Periodic visual diagnostics (for example drawable/glyph raster analysis) must be invalidation/state-driven and cached. A bounded safety recheck is acceptable; rebuilding bitmaps, shaders or paths every monitor tick/frame is not.

## 7. Companion Status screen contract

The Home status UI is **one large Status card**, not nested cards. The top overall-status section is colored from the aggregate health (worst status wins: red > amber > green), followed by exactly one divider and then four full-width colored sections inside the same card.

Rows:
- **GoneMAD Music Player:** missing = red; installed but not running = amber; installed + running = green.
- **Xposed framework:** service unavailable = red; connected but unsupported libxposed API = amber; supported API = green.
- **GoneSmart state:** unavailable/stopped = red; degraded/fallback = amber; healthy normal/idle or Smart runtime = green.
- **Compatibility:** tested GMMP = green; installed but untested version = amber; unavailable/unknown GMMP = red.

Compatibility source of truth is `GmmpCompatibilityPolicy.TESTED_VERSION`; do not duplicate a second tested-version constant in the Activity.

## 8. Logging and diagnostics

Normal logs should explain meaningful runtime decisions, not continuously dump implementation internals.

Keep concise markers for:
- resolver success/failure;
- native mutation verification/rollback;
- Track Auto-DJ start/isolation/initial fill/final verification;
- recommendation source/fallback decisions;
- user-visible feature failures.

Retire or gate deep `GMMP COMPAT CLASS/RECYCLER ... FIELDS/METHODS/CTORS/NESTED` inventories after a version is accepted. Preserve the reusable diagnostics utilities for the next unknown version; do not keep the full discovery flood active on every normal 4.2.1 launch.

Do not “fix” noisy native GMMP tags such as `w6` by hiding their logger. If GoneSmart caused unnecessary native SQL, remove the repeated work. Native queries that GMMP itself legitimately performs while opening/refocusing a library tab may remain visible in logcat.

Accepted user actions may keep a bounded postcondition check, but must not retain discovery-era polling loops. Repeated Cursor/Queue reads after a device-accepted native action are reserved for an unknown/failing compatibility boundary, not ordinary runtime verification.

## 9. Safety for queue/library mutation

- Before mutation, correlate the native rows 1:1 with the independent Cursor snapshot.
- Re-read immediately before a transaction when concurrent native playback could have changed state.
- Use native Room writers and verify the Cursor after mutation.
- Keep rollback data until verification succeeds.
- Never invoke an unknown query/reactive/writer candidate merely to see what it does.
- Never infer queue ownership from a historical field/class name alone; generated adapter SQL naming `queue_table` is ownership evidence.

## 10. Documentation hierarchy

- `AGENTS.md`: standing rules and current accepted architecture.
- `docs/GMMP_COMPATIBILITY_PLAYBOOK.md`: current version ledger and future-update procedure.
- `docs/GMMP_421_COMPLETION.md`: concise final 4.2.1 acceptance/performance state.
- `docs/NATIVE_GMMP_AUDIT.md` and dated `GMMP_421_*` files: reverse-engineering chronology/evidence.
- Feature-specific docs: detailed behavior where needed.

Do not bloat `AGENTS.md` with every historical probe. Move chronology to docs and keep this file actionable.

## 11. Branch completion / release preparation

A development branch can be called complete only when:

1. intended feature behavior is device-verified or explicitly listed as an open limitation;
2. exact-head CI is green;
3. temporary probes/debug logging are retired or scoped to failure/unknown-version paths;
4. tests cover durable resolver/policy behavior;
5. `AGENTS.md` and compatibility docs match the actual code;
6. no known unsafe guessed writer remains enabled;
7. accepted-version discovery and UI diagnostics no longer impose obvious persistent hot-path cost.

Before the 0.4.0 release, new features should be developed on a new branch from the chosen clean integration point, not appended indefinitely to a completed compatibility branch.

## Runtime hot-path invariants

- Full view-tree scans may remain as a bounded recovery path when a cached native target is detached/replaced, but must not run continuously during normal pager/layout waves.
- `View.isShown == false` during a ViewPager transition does **not** invalidate a semantically verified native UI anchor. Treat visibility as a rendering/discovery condition, not target identity; only detach/replacement/resource mismatch should force structural rediscovery.
- Version-specific legacy fallbacks must never be reached merely because the accepted-version reader has a transient unresolved state. In particular Track Mix on 4.2.1 must retry the semantic Queue reader when CURRENT is temporarily unresolved; it must not reinterpret an obfuscated Auto-DJ field as the 4.2.0 queue wrapper.
- Pre-action Room/Queue reads used as race barriers must not block GMMP's main thread with `Future.get(...)`. Preserve before/after ordering by doing the read on a worker and dispatching the native action back to main afterward.
- Smart Auto-DJ provider backoff is for **unproductive** recommendation fills, not successful fills. A successfully consumed session pool must be allowed to top up asynchronously before it empties; never make rapid skipping wait solely because an earlier successful fill happened less than a minute ago.
- Keep the GoneSmart recommendation pool ahead of GMMP's visible Auto-DJ queue. Prefer background recommendation prewarm/low-water top-up over speculative early mutation of GMMP's playback queue. Native queue insertion remains owned by GMMP's verified Auto-DJ refill path.
- Smart Auto-DJ latency diagnostics must distinguish queue-read-before, pool hit/fill, native refill, queue-read-after and total time so future regressions can be localized without multiple probe APKs.
- If GMMP has already passively proved a native queue-position writer/readback for the live Auto-DJ instance, all Track Mix CURRENT checks and queue mutations must reuse that proof. Do not restart broad integer/getter discovery on every settling poll; transient structural CURRENT ambiguity is not evidence that the selected native Play failed.
- Negative UI discovery is cacheable. If an initial scan plus one bounded delayed recovery prove that a navigation surface has no alternate Playlist/Smart-Playlist targets, persistent global-layout callbacks must become no-ops until the decor/options/cached target identity actually changes.
