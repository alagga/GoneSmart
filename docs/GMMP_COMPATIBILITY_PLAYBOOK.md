# GMMP compatibility playbook

This file is GoneSmart's maintained version ledger and repeatable update procedure for GoneMAD Music Player (GMMP). GMMP internals are obfuscated, so semantic ownership and observed behavior matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` evidence files.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original 0.3.x/early-0.4 mappings and device evidence. |
| 4.2.1 | **Accepted core target; Playlist Link repair pending re-acceptance** | Core 0.4.x features are accepted. A post-0.4.1 Playlist Link regression exposed stale 4.2.0-only bridge bindings; the 0.4.2 repair candidate uses shape-validated runtime bindings and still requires one device re-acceptance pass. |
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
- Playlist Link — post-0.4.1 regression discovered; 0.4.2 repair candidate pending device re-acceptance;
- Play flipped;
- Queue Flip;
- navigation/player sparkle badges and Status/Compatibility UI.

Resolvers prefer semantic adapter/model/writer ownership. Historical class names remain fast paths only where useful and safe.

For the still-pending Playlist Link re-acceptance, the native editor Link action itself must be passively correlated from a real user-triggered GMMP dialog/event chain. A historical method name plus the same parameter shape is insufficient evidence for interception.

For Playlist Link specifically, do not rely on a post-inflate `MenuItem` listener as the sole dispatch boundary. GMMP 4.2.1 can replace that listener later in the Smart editor lifecycle. The validated native presenter link action is therefore the authoritative fallback: add-link requests may be rerouted to GoneSmart's type chooser, while the Smart-Playlist option re-enters GMMP's original native linker through a bounded reentrancy bypass.

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
