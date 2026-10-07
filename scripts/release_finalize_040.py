from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    if old not in text:
        raise SystemExit(f"Expected text not found in {path}: {old!r}")
    target.write_text(text.replace(old, new))


# User-facing notifications: readable words, never symbol-only fallbacks.
replace_exact(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixPlan.kt",
    "/** Original GMMP `started` when present; otherwise icon-only status. */",
    "/** Prefer GMMP `started`; otherwise use a readable GoneSmart fallback. */",
)
replace_exact(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixPlan.kt",
    'return if (native == null) "$menuLabel ✓" else "$menuLabel $native"',
    'return "$menuLabel ${native ?: "started"}"',
)
replace_exact(
    "app/src/test/java/io/github/alagga/gonesmart/TrackMixPlanTest.kt",
    '"Track Auto-DJ ✓"',
    '"Track Auto-DJ started"',
)
replace_exact(
    "app/src/test/java/io/github/alagga/gonesmart/TrackMixPlanTest.kt",
    '"トラック オートDJ ✓"',
    '"トラック オートDJ started"',
)

replace_exact(
    "app/src/main/java/io/github/alagga/gonesmart/NativeGmmpUiText.kt",
    "The installed GMMP 4.2.0 has\n * a native `error` resource; the symbol-only fallback is locale-neutral.",
    "The tested GMMP 4.2.1 has\n * a native `error` resource; readable English is the final fallback.",
)
replace_exact(
    "app/src/main/java/io/github/alagga/gonesmart/NativeGmmpUiText.kt",
    'val error = nativeError?.takeUnless(String::isBlank) ?: "⚠"',
    'val error = nativeError?.takeUnless(String::isBlank) ?: "Error"',
)
replace_exact(
    "app/src/main/java/io/github/alagga/gonesmart/NativeGmmpUiText.kt",
    'return if (native == null || native.contains("%")) "✓" else native',
    'return if (native == null || native.contains("%")) "Playlist moved" else native',
)
replace_exact(
    "app/src/test/java/io/github/alagga/gonesmart/NativeGmmpUiTextTest.kt",
    '@Test fun symbolFallbackDoesNotLeakAnotherLanguage() {\n        assertEquals("⚠", NativeGmmpUiText.errorLabel(null, null))\n        assertEquals("⚠ · DJ automatique",\n            NativeGmmpUiText.errorLabel(null, "DJ automatique"))',
    '@Test fun readableFallbackIsUsedWhenNativeErrorTextIsMissing() {\n        assertEquals("Error", NativeGmmpUiText.errorLabel(null, null))\n        assertEquals("Error · DJ automatique",\n            NativeGmmpUiText.errorLabel(null, "DJ automatique"))',
)
replace_exact(
    "app/src/test/java/io/github/alagga/gonesmart/NativeGmmpUiTextTest.kt",
    'assertEquals("✓", NativeGmmpUiText.playlistMoveSuccessLabel(null))\n        assertEquals(\n            "✓",\n            NativeGmmpUiText.playlistMoveSuccessLabel("Saved %s")\n        )',
    'assertEquals("Playlist moved", NativeGmmpUiText.playlistMoveSuccessLabel(null))\n        assertEquals(\n            "Playlist moved",\n            NativeGmmpUiText.playlistMoveSuccessLabel("Saved %s")\n        )',
)

# Release-facing README compatibility statements.
replace_exact(
    "README.md",
    "| **Latest tested GMMP version** | `4.2.0` |",
    "| **Latest tested GMMP version** | `4.2.1` |",
)
replace_exact(
    "README.md",
    "All three native operations were exercised on GMMP **4.2.0** on an actual\ndevice",
    "All three native operations were exercised on GMMP **4.2.1** on an actual\ndevice",
)

Path("RELEASE_NOTES.md").write_text("""# GoneSmart v0.4.0

GoneSmart 0.4.0 expands the project from Smart Auto-DJ into a broader native-looking GMMP extension and completes compatibility work for **GoneMAD Music Player 4.2.1**.

## Highlights

- **GMMP 4.2.1 compatibility:** all enabled 0.4.0 feature families were migrated to the remapped internals and device-accepted on the maintainer setup.
- **Playlist folders:** browse nested physical playlist folders in the Playlists tab and Add-to-Playlist picker, create/delete folders, and move one or several playlists with native GMMP writers and verification.
- **Smart-Playlist folders:** independent physical folder view for Smart Playlists while GMMP keeps ownership of real Smart-Playlist rows, parsing and actions.
- **Multi-selection:** add songs to several ordinary playlists in one picker session and move multiple Smart Playlists where supported.
- **Playlist Link:** Smart Playlists can reference ordinary playlists as live membership rules; disabling GoneSmart leaves the saved Smart Playlist openable and the extension rule inert.
- **Flip queue / Play flipped:** reverse an existing queue or launch ordinary/Smart playlists in reverse order while preserving native playback ownership.
- **Track Auto-DJ:** start a fresh Auto-DJ session from an individual track, keep that track as the seed and fill to GMMP's configured Initial Size.
- **Status and compatibility UI:** one coherent Status card with independent GMMP, Xposed, GoneSmart and Compatibility health sections; untested GMMP versions are shown in amber.

## Smart Auto-DJ and playback hardening

- Recommendation pools prewarm and top up in the background so rapid skipping does not wait unnecessarily for another provider round.
- Successful pool fills are no longer throttled by the provider backoff intended for unproductive requests.
- Track Auto-DJ uses GMMP's verified native queue/refill paths, preserves the clicked seed across large Smart-Playlist rebuilds and repairs the 4.2.1 queue-continuation edge case without guessing an obfuscated append allocator.
- The first CURRENT exposed by Smart-Playlist playback is treated as provisional during the bounded native-Play settle window. If that transient row disappears, GoneSmart can retarget to the independently observed live CURRENT; outside that window it fails closed.
- Queue Flip learns the current-position writer passively from natural GMMP playback and verifies it independently before controlled use.

## Performance and cleanup

- Offscreen Playlist/Smart-Playlist ViewPager pages are effectively idle.
- Player and navigation badges reuse verified native UI anchors instead of repeatedly rescanning the full view tree.
- Accepted-version discovery probes retire their runtime cost as well as their verbose logs.
- Play-flipped verification uses one bounded postcondition read instead of a polling loop.
- Track Auto-DJ pre-action queue reads run off GMMP's main thread.
- User-facing success/error popups now use readable text fallbacks instead of symbol-only checkmarks/warnings; native GMMP translations are still preferred where available.

## Compatibility

- **GoneMAD Music Player:** 4.2.1 is the tested target for this release.
- **Android:** 8.0+ (`minSdk 26`).
- **Hooking API:** libxposed API 102.
- **Rooted setup:** JingMatrix Vector v2.2+ is the recommended path.
- **LSPatch v1.2:** remains experimental / less tested.

GoneSmart hooks obfuscated GMMP internals. Future GMMP versions are intentionally marked untested until the compatibility workflow in `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` has been completed.

## Installation

Download `GoneSmart-v0.4.0.apk`, install it, enable GoneSmart for `gonemad.gmmp` in Vector/LSPosed, force-stop GMMP and reopen it. See the repository README and `docs/INSTALLATION.md` for the full setup and troubleshooting guide.
""")

Path("AGENTS.md").write_text("""# GoneSmart — persistent coding and collaboration rules

**Purpose:** Canonical entry point for assistants and contributors. Read this file before changing code, then inspect the relevant source, current branch head, compatibility docs and exact-head CI.

**Last consolidated:** 2026-10-07. **Release line:** 0.4.0. **Accepted GMMP target:** 4.2.1.

## 1. Collaboration and repository discipline

- Current explicit maintainer instructions override this file.
- Communicate with the maintainer in **German**. Public repository documentation and the companion UI remain English.
- Work from the actual GitHub repository; never reconstruct current code from chat snippets alone.
- Check the current branch head before editing and do not overwrite commits that appeared during the task.
- **Do not create temporary branches unless the maintainer explicitly asks for one.** Work on the active feature branch. If one-off CI/helper infrastructure is unavoidable, remove it in the same task and leave no release-tree residue.
- Minimize device-test rounds. Mine existing logs fully, add host tests and bundle related fixes instead of shipping one probe APK per hypothesis.
- After code changes, the branch is not green until the normal Build workflow succeeds on the **exact head**: unit tests, Debug APK, unsigned Release smoke test and Debug APK upload.
- Do not merge to `main`, tag or publish a release unless explicitly requested.

## 2. Native-first is mandatory

GoneSmart extends GMMP; it does not replace existing GMMP behavior with parallel implementations.

- Reuse GMMP native writers, callbacks, adapters, transactions, resources and playback flows whenever they exist.
- Never write directly to GMMP SQL/Room tables when a native DAO/writer boundary is available.
- Obfuscated names are version evidence, not semantic identity.
- Resolver order: tested fast path → structural/semantic discovery → uniqueness → runtime/postcondition verification.
- Ambiguous state-changing boundaries fail closed. Never invoke a candidate merely to see what it does.
- Read-only observation may be broader than mutation discovery.
- One unavailable hook family must not disable unrelated GoneSmart features.

## 3. Compatibility workflow

Read `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` before adapting to a new GMMP build.

For an unknown version, use one bounded read-only compatibility self-test covering the relevant feature families. Safe evidence includes hierarchy/signatures, runtime types, generated Room adapter SQL, native adapter ownership and independent Cursor/state correlation. Destructive actions are never invoked merely for discovery.

Lifecycle:

`unknown → passive evidence → semantic resolver → uniqueness → real native action → postcondition/device acceptance → retire probe`

Graduation retires **runtime cost**, not just logging. Accepted-version hot paths must not keep broad reflection, SQL/Cursor readbacks, full view-tree scans or model reconstruction running continuously.

## 4. Accepted GMMP 4.2.1 queue contract

- Queue DAO accessor: `GMDatabase_Impl.F(): sx3`; runtime DAO: `vx3`.
- Queue entity: `cy3`.
- Native synchronous reader: `sx3.H1(): ArrayList`.
- Generated `vx3` adapters prove `queue_table` ownership.
- Verified update writer: `O0(List)`.
- Delete may reflect as `Object[]`, but the implementation casts to `cy3[]`; pass a real typed runtime array.
- Independent Cursor/native state remains authoritative for queue identity/order/current postconditions.
- `qr.z(int)` is the native Auto-DJ **refill** boundary. It is never a current-position setter.
- Queue Current writing is learned passively from natural playback; observed 4.2.1 evidence is `dx3.c2(int)`, but the name itself is not proof.

Queue Flip and Play flipped are accepted on 4.2.1. Once a writer/readback has been proved for the live Auto-DJ instance, discovery becomes a cheap pass-through.

## 5. Track Auto-DJ

The 4.2.1 flow is accepted from normal playlists and large Smart Playlists.

- Dispatch GMMP's exact native Play action for the clicked row.
- Treat the first CURRENT from the generic track menu as provisional during the bounded Smart-Playlist completion guard.
- If the first candidate survives the native rebuild exactly once, preserve it even if CURRENT temporarily drifts.
- If that provisional candidate disappears completely **inside the guard** and a different playback identity is independently observed, retarget and restart settling.
- Outside the guard, fail closed rather than following unrelated/manual playback.
- Isolate the exact native Queue entity through proven GMMP writers and verify that the seed remains Current.
- GMMP Initial Size includes the seed; request exactly the missing count through native `qr.z(count)`.
- Recommendation preparation must not strand playback on a one-row queue. Give Smart DJ only a short bounded head start; native GMMP may fill immediately while the same pool fill continues in the background.
- Track-Auto-DJ-owned normalized queues use the accepted event-driven continuation repair: on a verified natural CURRENT transition, read remaining rows once and invoke `qr.z(deficit)` only when GMMP's configured upcoming count is not satisfied. No polling and no guessed append allocator.

## 6. Playlist / Smart-Playlist / UI rules

- Playlist and Smart-Playlist folders preserve GMMP native rows, styling, parsing, actions and writers wherever those exist.
- Playlist Link is portable/fail-closed. Disabling it leaves old Smart Playlists openable and GoneSmart-only semantics inert.
- Multi-selection changes only the intended add/move/create action.
- UI extensions follow GMMP's live theme/navigation surfaces rather than fixed assumptions where possible.
- Attached but offscreen ViewPager pages are effectively idle.
- Prefer native adapter/scroll/layout events over polling.
- Coalesce visible-row synchronization to at most one posted animation-frame update per burst.
- Full decor/view-tree scans are bounded recovery paths, not normal layout work.
- Temporary `isShown == false` during a pager transition does not invalidate a verified native anchor; detach/replacement/semantic mismatch does.
- Cache class-level reflection and drawable/glyph analysis used by persistent badges.

## 7. User-facing notification contract

- A popup/Toast should exist only when it confirms an action, communicates a failure/fallback, or prevents an otherwise confusing native result.
- Prefer GMMP's complete localized native phrase when one exists.
- GoneSmart-owned notices may use concise English fallbacks.
- **Never use a checkmark, warning icon or other symbol as the entire fallback message.** Success/failure must remain understandable as text (for example `Track Auto-DJ started`, `Playlist moved`, `Error`).
- Suppression of native Toasts/Snackbars must be narrowly scoped, one-shot/bounded and limited to known duplicate or misleading transitional messages.
- Do not suppress unrelated GMMP notifications.

## 8. Status UI contract

The Home status UI is one large Status card. Aggregate health uses the worst state (red > amber > green), followed by four full-width sections:

- GMMP: missing red / installed-not-running amber / running green.
- Xposed: unavailable red / unsupported API amber / supported API green.
- GoneSmart: stopped/unavailable red / degraded amber / healthy green.
- Compatibility: missing/unknown red / installed untested amber / tested 4.2.1 green.

`GmmpCompatibilityPolicy.TESTED_VERSION` is the only tested-version source of truth.

## 9. Smart Auto-DJ performance invariants

- Keep the recommendation pool ahead of GMMP's visible queue through background prewarm/low-water top-up.
- Provider backoff applies to **unproductive** fills, not successful ones.
- Native queue insertion remains owned by GMMP's verified Auto-DJ refill path.
- Latency diagnostics distinguish queue-read-before, pool hit/fill, native refill, queue-read-after and total time.
- Reuse the already passively proved current-position hint for queue snapshots; do not restart broad getter discovery on transient CURRENT ambiguity.
- Pre-action Queue/Room race barriers run off GMMP's main thread.

## 10. Logging and diagnostics

Normal logs record meaningful runtime decisions, resolver success/failure, verified mutation/rollback, Track Auto-DJ phases, provider/fallback decisions and user-visible failures.

Broad class/method/recycler inventories are gated to unknown/failing compatibility boundaries. Do not hide native GMMP tags such as `w6`; remove GoneSmart-caused unnecessary work instead.

Accepted actions may retain one bounded postcondition check, not discovery-era polling loops.

## 11. Mutation safety

- Correlate native rows 1:1 with independent read state before mutation.
- Re-read immediately before a transaction when concurrent playback can change state.
- Use native Room writers and verify afterward.
- Retain rollback data until postconditions succeed.
- Never infer writer ownership from a historical class/field name alone.

## 12. Documentation hierarchy

- `AGENTS.md`: standing collaboration/architecture rules.
- `docs/GMMP_COMPATIBILITY_PLAYBOOK.md`: version ledger and future-update procedure.
- `docs/GMMP_421_COMPLETION.md`: concise final 4.2.1 / 0.4.0 acceptance state.
- `docs/NATIVE_GMMP_AUDIT.md` and dated `GMMP_421_*` files: historical reverse-engineering evidence.
- Feature docs: detailed user/developer behavior.

Keep chronology out of `AGENTS.md`; preserve it in the audit/evidence docs.

## 13. Release completion

A release candidate is complete only when:

1. intended behavior is device-accepted or a limitation is explicitly documented;
2. exact-head normal CI is fully green;
3. temporary probes/workflows/scripts are absent from the release tree;
4. tests cover durable resolver/policy behavior;
5. `AGENTS.md`, the compatibility playbook, completion matrix, README and release notes agree;
6. no enabled unsafe guessed writer remains;
7. accepted-version diagnostics impose no obvious persistent hot-path cost;
8. the merged `main` head is green before tagging/publishing.

After 0.4.0, new features should start on a normal named feature branch from the chosen clean `main` integration point.
""")

Path("docs/GMMP_COMPATIBILITY_PLAYBOOK.md").write_text("""# GMMP compatibility playbook

This file is GoneSmart's maintained version ledger and repeatable update procedure for GoneMAD Music Player (GMMP). GMMP internals are obfuscated, so semantic ownership and observed behavior matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` evidence files.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original 0.3.x/early-0.4 mappings and device evidence. |
| 4.2.1 | **Accepted / 0.4.0 target** | Smart DJ, Playlist/Smart-Playlist features, Flip, Track Auto-DJ and compatibility/performance cleanup device-accepted. |
| Any other version | Untested | Show amber Compatibility status and run the bounded workflow before claiming support. |

Source of truth: `GmmpCompatibilityPolicy.TESTED_VERSION = "4.2.1"`.

## 2. Resolve semantics, not names

A durable resolver combines, as applicable:

1. owner/runtime type reached from a known live object;
2. signature/hierarchy;
3. generated Room adapter SQL ownership;
4. independent Cursor/native-state correlation;
5. uniqueness among candidates;
6. postcondition after the real native action;
7. rollback/fail-closed behavior for mutation.

Tested obfuscated names may be fast paths. They are never proof by themselves. If ambiguity remains, stop rather than invoking candidates experimentally.

## 3. Workflow for a new GMMP version

### Detect

Compare the installed GMMP version with the tested version. Missing/unknown versions are never green Compatibility.

### Run one bounded read-only self-test

Cover the feature families that depend on internals in one pass. Safe evidence includes class hierarchy/signatures, runtime types, generated Room SQL, RecyclerView adapter/holder ownership, native call-chain existence without destructive invocation, and Cursor/state correlation.

### Graduate each boundary

- encode semantic resolution in a shared resolver/policy;
- add JVM tests for uniqueness and false positives;
- validate the real native action once on device;
- retain a bounded independent postcondition;
- retire the broad probe.

### Retire runtime cost

Accepted versions must not continuously perform discovery-time reflection, SQL/Cursor readbacks, model reconstruction, delayed probes or full view-tree scans. Logging less while keeping the same expensive work is not cleanup.

## 4. GMMP 4.2.1 accepted boundary map

### Auto-DJ refill

- Runtime owner observed as `qr`.
- Native refill boundary observed as `qr.z(int)`.
- Semantic role: request N Auto-DJ tracks.
- It is explicitly excluded from current-position writer discovery.

GoneSmart owns recommendation selection; GMMP owns insertion/playback. Native fallback remains valid when no suitable local smart candidate is available.

### Queue read / DAO / entity

- database accessor: `GMDatabase_Impl.F(): sx3`;
- runtime DAO: `vx3`;
- queue entity: `cy3`;
- synchronous reader: `sx3.H1(): ArrayList`;
- generated adapters naming `queue_table` establish ownership;
- verified update writer: `O0(List)`;
- delete bridge must receive a real typed `cy3[]` even when reflection exposes `Object[]`.

Independent Cursor/native state remains the oracle for queue IDs, track IDs, positions, shuffle positions and mutation postconditions.

### Current-position writer / Queue Flip

The writer is learned passively from **natural GMMP playback**, correlated with an independent current-position signal and promoted only after exact agreement. No candidate is invoked merely to test it.

Observed 4.2.1 evidence is `dx3.c2(int)`. Treat the name as evidence only.

Queue Flip then correlates native entities 1:1, reverses through proven native writers, updates Current only through the proved writer when necessary, verifies the independent postcondition and retains rollback data until success.

### Track Auto-DJ

Accepted from ordinary playlists and large Smart Playlists:

1. dispatch GMMP's exact native Play action;
2. use the bounded generic-track completion guard while native list playback settles;
3. preserve the clicked/provisional target if it remains uniquely identifiable through a large rebuild;
4. if the first provisional CURRENT disappears completely inside the guard, retarget to the independently observed live CURRENT and restart settling;
5. outside the guard, fail closed on competing/manual playback;
6. isolate the exact native Queue entity through proven writers;
7. verify the seed is sole Current;
8. request exactly `Initial Size - seed size` through native refill;
9. verify exact Initial Size and preserved seed.

#### 4.2.1 queue-root continuity (r42–r43)

Seed isolation changes the logical queue root. Device evidence showed that rebasing Queue DAO row positions does not prove GMMP's hidden append/end allocator was reset. GoneSmart therefore does **not** guess that allocator.

For a Track-Auto-DJ-owned normalized queue only:

- use the already verified natural CURRENT transition as the event trigger;
- read the actual remaining queue once;
- compare it with GMMP's configured upcoming count;
- invoke the existing native `qr.z(deficit)` refill boundary only for a real deficit;
- deduplicate by verified queue position and allow only one managed refill in flight;
- disarm on unrelated native list playback.

This continuation path is device-accepted for 0.4.0.

### Recommendation-pool latency

- successful fills may top up again as soon as low-water requires it;
- the long provider backoff applies only after an unproductive fill;
- startup/session prewarm is asynchronous;
- Track Auto-DJ's explicit Initial-Size path gives smart preparation only a short bounded head start and never cancels the still-useful background fill when native GMMP must proceed immediately.

### Playlist / Smart-Playlist surfaces

Accepted on 4.2.1:

- Playlist folders;
- Smart-Playlist folders;
- ordinary and Smart multi-selection;
- Playlist Link;
- Play flipped;
- Queue Flip;
- navigation/player sparkle badges and Status/Compatibility UI.

Resolvers prefer semantic adapter/model/writer ownership. Historical class names remain fast paths only where useful and safe.

## 5. Accepted-version performance contract

- Offscreen ViewPager pages do not perform row/model/style work.
- Native adapter/scroll/layout events are preferred over periodic polling.
- Visible-row synchronization is coalesced.
- Persistent UI helpers cache weak references to proven native anchors.
- `isShown == false` during pager transitions does not invalidate anchor identity.
- Full view-tree discovery is bounded recovery only.
- Drawable/glyph analysis and class-level reflection are cached.
- Queue-writer discovery becomes pass-through after proof.
- Play-flipped uses one delayed independent postcondition read plus at most one legacy fallback, not a retry loop.
- Track Auto-DJ pre-action Queue/Room barriers never block GMMP's main thread.

Do not hide `w6` or other native GMMP logging to make logs look clean. Remove GoneSmart-caused unnecessary queries instead.

## 6. Notification / host-UI discipline

- Prefer complete GMMP localized phrases when available.
- GoneSmart-owned notices may use concise English fallback text.
- Symbol-only status (`✓`, `⚠`) is not an acceptable entire popup fallback.
- Suppress native Toasts only in a bounded, exact scope when they are known duplicates or misleading transitional results.

## 7. Probe retirement ledger

4.2.1's broad class/recycler/Room probes are retired or gated. Reusable diagnostics remain available for unknown versions, but accepted runtime keeps only concise resolver/result/failure markers.

The dated `GMMP_421_*` files retain the investigation chronology. Do not move that chronology back into hot runtime code or `AGENTS.md`.

## 8. Acceptance checklist for a future version

- [ ] bundled read-only self-test completed;
- [ ] every enabled mutation has a proven native action/writer;
- [ ] independent read/postcondition mapping verified;
- [ ] JVM tests cover ambiguity and failure;
- [ ] changed semantic boundaries device-accepted;
- [ ] failure paths fail closed;
- [ ] broad diagnostics and runtime discovery cost retired;
- [ ] Compatibility source-of-truth updated;
- [ ] `AGENTS.md`, completion matrix, README and this playbook agree;
- [ ] exact-head CI fully green.

## 9. 0.4.0 closure

GMMP 4.2.1 is the accepted target for GoneSmart 0.4.0. The final migration includes the queue/entity remap, passive Current writer proof, Track Auto-DJ r42–r44 continuity/settling corrections, recommendation-pool latency cleanup and ViewPager/badge hot-path cleanup. No known enabled 4.2.1 feature depends on an unsafe guessed state-changing writer.
""")

Path("docs/GMMP_421_COMPLETION.md").write_text("""# GMMP 4.2.1 completion matrix

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

## Known release limitations

- GoneSmart hooks obfuscated GMMP internals; versions other than 4.2.1 are not automatically supported.
- Provider/network/metadata availability still affects Smart DJ recommendation coverage; native GMMP fallback remains intentional behavior.
- LSPatch is documented but less thoroughly tested than the recommended rooted Vector setup.

## Release gate

The release may be tagged only after the final feature head and the merged `main` head both pass the normal Build workflow. The signed public APK is produced only by `.github/workflows/release.yml`.
""")

Path("docs/RELEASING.md").write_text("""# Releasing GoneSmart

GoneSmart public APKs are built and published by GitHub Actions from tagged source.

## Required repository secrets

- `LASTFM_API_KEY`
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the original release keystore backed up offline; every update must use the same signing key.

## Stable release flow

1. Finish code and documentation on the active feature branch.
2. Set `versionCode` / `versionName` in `app/build.gradle.kts` and finalize `RELEASE_NOTES.md`.
3. Confirm the normal **Build** workflow is green on the exact feature head.
4. Open/review the feature → `main` pull request and merge it without discarding unrelated `main` history.
5. Confirm the normal **Build** workflow is green on the exact merged `main` head.
6. Open **Actions → Release APK → Run workflow** with **prerelease** disabled.
7. Verify the resulting `v<versionName>` tag, GitHub Release and signed APK asset.

The release workflow verifies secrets, restores the release keystore, builds the signed APK, derives the tag from Gradle, creates the tag for a manual dispatch, uploads `GoneSmart-v<version>.apk` and uses `RELEASE_NOTES.md` as the release body.

## Prereleases

Use a version such as `0.5.0-beta1`, update release notes and dispatch **Release APK** with **prerelease** enabled.

## Compatibility gate

Before declaring a new GMMP version supported, follow `docs/GMMP_COMPATIBILITY_PLAYBOOK.md`: prove mutation boundaries, validate changed semantics on device, retire discovery/runtime overhead, update the tested-version source of truth and run exact-head CI.

## Local development

Keep the Last.fm key in ignored `local.properties`. For local release signing use ignored `keystore.properties` as described in `BUILDING.md`; never place signing secrets in source.
""")

# Release-tree invariants.
gradle = Path("app/build.gradle.kts").read_text()
assert "versionCode = 46" in gradle
assert 'versionName = "0.4.0"' in gradle
assert "GmmpCompatibilityPolicy.TESTED_VERSION" in Path("AGENTS.md").read_text()
for path in [
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixPlan.kt",
    "app/src/main/java/io/github/alagga/gonesmart/NativeGmmpUiText.kt",
]:
    if "✓" in Path(path).read_text():
        raise SystemExit(f"Checkmark-only text remains in audited popup helper: {path}")
