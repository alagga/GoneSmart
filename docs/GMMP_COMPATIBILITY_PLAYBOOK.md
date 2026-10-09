# GMMP compatibility playbook

This file is GoneSmart's maintained version ledger and repeatable update procedure for GoneMAD Music Player (GMMP). GMMP internals are obfuscated, so semantic ownership and observed behavior matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` evidence files.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original 0.3.x/early-0.4 mappings and device evidence. |
| 4.2.1 | **Accepted core target; Playlist Link / Track Mix repair pending re-acceptance** | Core 0.4.x features are accepted. Post-0.4.1 device traces exposed stale Playlist Link bindings and a Track Mix first-row/continuity regression; the bundled 0.4.2 repair candidate still requires one device re-acceptance pass. |
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

Compare the installed GMMP version with the tested version. Missing/unknown versions are never green Compatibility. For every device acceptance/debug run, first verify the runtime `BuildConfig.GIT_REVISION` against the intended GoneSmart branch head; a `-dirty` suffix identifies local uncommitted source. Do not diagnose a feature regression from a log whose GoneSmart build identity is absent or mismatched.

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
6. an unchanged CURRENT may count as a replay only when an independent native playback/queue-update signal confirms the action; a successful menu callback alone is not playback identity;
7. isolate through a GMMP-owned native boundary: the accepted legacy native transaction on 4.2.0 or GMMP's public `CLEAR_QUEUE` command on 4.2.1;
8. verify the selected seed is the sole Current track; native `queue_id` is not a stable identity across a GMMP-owned clear;
9. request exactly `Initial Size - seed size` through native refill;
10. verify exact Initial Size and preserved selected track.

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

The 2026-10-08 `d5ff3ddabff2` trace exposed two Track Mix regressions around the already accepted native boundaries. First, a queue-menu click that had actually started track `9772` was later reclassified as an unchanged replay of stale CURRENT track `9761`; the GoneSmart verification line itself showed `nativeSignal=false` and `changed=false`. Same-current replay therefore now requires the independent native playback signal instead of treating a successful menu dispatch as proof. Second, after isolating the seed, GMMP 4.2.1 populated its Auto-DJ pool in about 86–95 ms but could query queue position 2 and reach `next audio source is null` as early as about 208 ms. The historical 1.8 s command-settling guard is retained only for the accepted legacy `ex3` path. The 4.2.1 path crosses the already proven `qr.z(initial-deficit)` boundary after a short bounded native preparation window, before playback can observe an empty next position.

Runtime `3cf7c504dc7d` then proved that this 90 ms 4.2.1 command boundary was actually active, but also exposed a second wait **inside GoneSmart's Smart-DJ refill interceptor**: after the queue had already been reduced to the single seed, recommendation-pool preparation blocked the explicit `qr.z(4)` path for another 1500 ms. During that interval GMMP requested `queue_position=2`, received `next audio source is null`, and only several hundred milliseconds later received the four fallback rows. The repair therefore no longer waits for the remote Smart-DJ pool at all once an explicit Track Mix seed is isolated. It starts or reuses the recommendation fill, immediately allows GMMP's native Initial Size refill to proceed, and keeps the recommendation work running only for later refills. This removes the GoneSmart-created empty-next window instead of tuning another delay.

The 2026-10-09 device trace then proved a different ownership detail in the 4.2.1 public-clear path: after `CLEAR_QUEUE`, the independent queue reader repeatedly showed exactly one row whose Current track was the selected seed, while the old verifier still timed out because it also required the pre-clear `queue_id`. GMMP is allowed to recreate its own queue row while preserving playback identity. The accepted postcondition is therefore exactly one row, exactly one Current, and that Current's Track ID equals the selected seed. Queue-entry identity is diagnostic only. GoneSmart continues to leave post-clear/refill queue ordering to GMMP instead of rewriting Room positions behind an already prepared AudioSource.

### Recommendation-pool latency

- successful fills may top up again as soon as low-water requires it;
- the long provider backoff applies only after an unproductive fill;
- startup/session prewarm is asynchronous;
- for Track Mix on 4.2.1, recommendation preparation is selected-seed-specific and happens before seed isolation while the original source queue still protects `current+1`;
- the same pre-clear window primes GMMP's native Auto-DJ candidate machinery through a native-only `qr.z(1)` call; that disposable row is removed with the source queue and does not consume GoneSmart's prepared pool;
- after `CLEAR_QUEUE`, the recreated sole Current row remains the same semantic recommendation session when its Track ID is unchanged even if GMMP assigned a new `queue_id`; prepared GoneSmart rows are inserted first and only a real shortfall falls back to native GMMP. Legacy 4.2.0 keeps the accepted full native initial refill.

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

For the still-pending Playlist Link re-acceptance, the native editor Link action itself must be passively correlated from a real user-triggered GMMP dialog/event chain. A historical method name plus the same parameter shape is insufficient evidence for interception. The 2026-10-08 device trace now proves the GMMP 4.2.1 chooser boundary at `as4$g.accept(...)`: that callback appears immediately upstream of GMMP's native event chain and resulting `MaterialDialog.show()`. The unrelated `as4.h2(boolean)` shape therefore remains untrusted.

For Playlist Link specifically, do not rely on a post-inflate `MenuItem` listener as the sole dispatch boundary. GMMP can replace that listener later in the Smart editor lifecycle. Keep the accepted 4.2.0 `ds4.g2(boolean)` path exact. On 4.2.1, intercept the proven `as4$g.accept(...)` callback for GoneSmart's type chooser and retain the exact callback instance/payload as a one-shot native continuation; choosing Smart Playlist resumes that original continuation. This avoids inventing an upstream mapping while keeping ordinary native edit/disabled paths pass-through.

For the Smart-editor add-rule action, resolve only a method declared directly by the resolved presenter whose single parameter is exactly the resolved Smart-rule base class and whose return type is `void`. Historical `P1` (4.2.0) and observed `Q1` (4.2.1) names are only fast paths. Broad assignability is intentionally rejected because 4.2.1 exposed false candidates such as inherited `equals(Object)` and `S1(...)`.

The 2026-10-08 `70b44c814aef` runtime probe then advanced past `Q1(dt4)` and exposed the next stale assumption: a generic two-argument query-helper shape admitted six 4.2.1 methods (`ot0.E/F/G/H/J/K`) for the old equality-helper role. Playlist Link does not guess one. Equality was needed only to manufacture the fail-closed `ID = Long.MIN_VALUE` predicate, so the repair now builds that already accepted native leaf rule and asks the resolved native leaf-rule evaluator to compile it. The same centralized sentinel encoding is used by the portability compatibility rules. This removes an unnecessary R8-specific operator mapping while keeping predicate semantics owned by GMMP.

The following `efc069cead61` device run advanced beyond that repair and failed only because the track-column holder still required the literal historical field name `URI`. Historical accepted traces identify that native value semantically as `track_uri`, so 4.2.1 now resolves a unique static query-field object whose native representation is exactly `track_uri`; `URI` remains only a fast path. The same run again observed `as4$g.accept(...)` with payload `bk3`, and its native event stack identifies `fc1` as the live 4.2.1 EventBus family, so `fc1` is included as a shape-validated fast path. Native OR grouping remains optional at binding time: ordinary links fitting one bounded IN clause must not be disabled by an unproven remapped group class, while a source requiring multiple chunks fails closed unless that native group constructor is uniquely resolved.

Runtime `e95176162041` then showed that semantic-value matching was still too strict because it tested the declared field type before reading the static value. The resolver was changed to accept a candidate only when the **runtime value** is an instance of the already resolved native query-field class and its semantic representation is `track_uri`, preserving uniqueness and fail-closed ambiguity.

Runtime `d5ff3ddabff2` proved that this runtime-type repair still stopped at the same `w75/z75` holder boundary: neither static candidate exposed `track_uri` through the query-field object's direct representation. The next resolver therefore remained read-only and bounded and inspected the in-memory value graph of type-compatible static constants for the exact native identifier.

Runtime `3cf7c504dc7d` finally proved that the failure occurs one level earlier still: both historical holder classes `w75` and `z75` are rejected before any semantic `track_uri` field can be selected, while the independently proven chooser callback `as4$g.accept(...)` continues to fire. The holder names are therefore no longer a gate. They remain fast paths, but on miss the resolver enumerates only class names from the target ClassLoader's dex files without initializing them, limits discovery to the native query-field package and structurally compatible static fields, and requires one unique semantic match. Direct bounded in-memory value inspection remains first choice; if the query-field object is opaque, only the already resolved native IN predicate builder may be used as a read-only in-memory semantic probe. No database query or unknown method is executed, and ambiguity still fails closed. Device re-acceptance remains pending.

Runtime `4cfe88ea5430` then exposed three independent defects in one bundled run. First, Playlist Link's broad semantic fallback encountered a GMMP class whose declared-field metadata referenced unavailable `android.view.ScrollFeedbackProvider`; semantic dex discovery now skips an individually uninspectable holder instead of aborting the whole resolver. Second, the remaining Track Mix null-next window occurred **before** seed isolation: while stage `WAIT_PLAY` was active GoneSmart suppressed GMMP's natural 4.2.1 Auto-DJ refill, and GMMP then queried a missing next queue position. `WAIT_PLAY` is now native pass-through on 4.2.1, while only `CLEARING`/`FILLING` remain held; the accepted legacy `ex3` behavior is unchanged. Third, the first Track Mix invocation had already deleted stale rows successfully and failed only when GoneSmart unnecessarily moved an already naturally Current seed from absolute position 5 to 1 through the passive Current writer. Seed isolation therefore keeps the verified absolute position, and sparse post-refill rows are compacted around that Current anchor (for example `5,11,12,13,14 -> 5,6,7,8,9`) rather than rebasing the queue to 1. Host regression tests cover these ownership/state rules; device re-acceptance remains pending.

Runtime `9c90a8e9e752` verified that anchored sparse compaction restored playback continuity but exposed two user-visible regressions. The Queue UI showed counters such as `8/5` because the Current anchor remained absolute after Initial Size existed, and the immediate full native refill filled all four post-seed rows before the asynchronous GoneSmart recommendation pool became useful. The next repair therefore splits 4.2.1 Initial Size into one immediate native continuity row plus a bounded GoneSmart remainder, then performs a final `1..N` rebase only after all rows exist and verifies Current identity through the proven writer. The same runtime still failed Playlist Link solely at semantic `track_uri` discovery, so the 4.2.1 bridge no longer depends on that column constant: it resolves source entries to Track IDs through verified read-only GMMP SQL and builds native Track-ID predicates from the already compiled fail-closed rule. Device re-acceptance remains pending.

The 2026-10-09 base-APK audit identified the remaining Playlist Link binding failure without another destructive/runtime probe. In 4.2.1 the Smart Playlist model stores `MatchAll` at `ts4.r` (historical fast path `s`) and a nested rule group stores `MatchAll` at `gt4.o` (historical fast path `p`). Both classes contain another boolean, so pure structural fallback is deliberately ambiguous. Required-field resolution now applies the same shape-validated accepted-alias ordering that optional editor-state fields already used. Static audit also reconfirmed `as4.W1(): es4`, `as4.Q1(dt4)`, `ct4.z(...)`, `hp3`, Playlist DAO `lo3` via `GMDatabase.E()`, `wn4`, `fc1` and `uf5`; these remain semantic/shape resolutions with obfuscated names only as tested fast paths. `ps2.U(dt4)` is the 4.2.1 optional rule-label formatter and is not a binding gate.

Runtime `05f1d162cfec` on 2026-10-09 finally exposed both remaining failures without another speculative mapping round. Playlist Link never reached editor dispatch at all: startup failed at `GMDatabase Playlist DAO accessor ... ambiguous/missing`, leaving `BRIDGE BINDINGS ready=false` and `hooks=0`. The 4.2.1 APK still proves `GMDatabase.E(): lo3`, while stale historical-looking `ho3`/`ko3` classes remain loadable. Playlist-DAO resolution therefore now derives the unique Playlist DAO from `GMDatabase` **before** considering historical R8-name fast paths, and those fast paths must satisfy the full Playlist DAO contract rather than merely exposing a `List` method.

The same `05f1d162cfec` trace showed why Track Mix's first generated row sounded unrelated and why the second-track transition could hang. After the selected seed was correctly cleared to one Current row, the old bootstrap path requested one refill before the recommendation pipeline finished, immediately classified the empty pool as native fallback, and GMMP queried position 2 while that native refill was still preparing (`next audio source is null`). In the Kid Rock run, the unrelated Bob Marley row was inserted before ListenBrainz/Last.fm/local matching later produced 14 accepted GoneSmart matches. The repair therefore removes the post-clear bootstrap race: selected-current provider/local matching and native Auto-DJ candidate priming both complete while the old source queue is still intact; semantic Current-track continuity carries that prepared pool across GMMP's queue-row replacement; after the clear, prepared GoneSmart rows populate position 2 first and only an actual shortfall uses native fallback. Host tests cover the DAO-shape rejection, pre-clear refill policy and same-Current/new-queue-id session continuity. Device re-acceptance remains pending.

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
