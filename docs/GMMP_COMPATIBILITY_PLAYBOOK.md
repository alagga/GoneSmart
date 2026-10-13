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

The 2026-10-09 base-APK audit identified the remaining Playlist Link binding failure without another destructive/runtime probe. In 4.2.1 the Smart Playlist model stores `MatchAll` at `ts4.r` (historical fast path `s`) and a nested rule group stores `MatchAll` at `gt4.o` (historical fast path `p`). Both classes contain another boolean, so pure structural fallback is deliberately ambiguous. Required-field resolution now applies the same shape-validated accepted-alias ordering that optional editor-state fields already used. Static audit also reconfirmed `as4.W1(): es4`, `as4.Q1(dt4)`, `ct4.z(...)`, `hp3`, Playlist DAO `lo3` via `GMDatabase.E()`, `wn4` and `fc1`; these remain semantic/shape resolutions with obfuscated names only as tested fast paths. The earlier assumption that singleton-shaped `uf5` was the MaterialDialogs callback return value was later disproved by the 2026-10-11 runtime cast to `ag5`. `ps2.U(dt4)` is the 4.2.1 optional rule-label formatter and is not a binding gate.

Runtime `05f1d162cfec` on 2026-10-09 finally exposed both remaining failures without another speculative mapping round. Playlist Link never reached editor dispatch at all: startup failed at `GMDatabase Playlist DAO accessor ... ambiguous/missing`, leaving `BRIDGE BINDINGS ready=false` and `hooks=0`. The 4.2.1 APK still proves `GMDatabase.E(): lo3`, while stale historical-looking `ho3`/`ko3` classes remain loadable. Playlist-DAO resolution therefore now derives the unique Playlist DAO from `GMDatabase` **before** considering historical R8-name fast paths, and those fast paths must satisfy the full Playlist DAO contract rather than merely exposing a `List` method.

The same `05f1d162cfec` trace showed why Track Mix's first generated row sounded unrelated and why the second-track transition could hang. After the selected seed was correctly cleared to one Current row, the old bootstrap path requested one refill before the recommendation pipeline finished, immediately classified the empty pool as native fallback, and GMMP queried position 2 while that native refill was still preparing (`next audio source is null`). In the Kid Rock run, the unrelated Bob Marley row was inserted before ListenBrainz/Last.fm/local matching later produced 14 accepted GoneSmart matches. The repair therefore removes the post-clear bootstrap race: selected-current provider/local matching and native Auto-DJ candidate priming both complete while the old source queue is still intact; semantic Current-track continuity carries that prepared pool across GMMP's queue-row replacement; after the clear, prepared GoneSmart rows populate position 2 first and only an actual shortfall uses native fallback. Host tests cover the DAO-shape rejection, pre-clear refill policy and same-Current/new-queue-id session continuity. Device re-acceptance remains pending.

Runtime `14c601aba04b` on 2026-10-11 crossed the previous Playlist Link startup gate (`bindings=true`, four hooks) and exposed two interaction-level defects. First, the 4.2.1 toolbar override opened GoneSmart's type menu before GMMP could naturally reach the proven `as4$g.accept(...)` boundary, so choosing Smart Playlist had no captured callback/payload to resume and logged `no proven native continuation available`. The repair now leaves the 4.2.1 toolbar listener native; the natural callback is intercepted downstream, captured one-shot, and resumed unchanged for Smart Playlist. The direct toolbar listener remains only for accepted 4.2.0 `ds4.g2(boolean)`. Second, selecting a normal Playlist crashed in MaterialDialogs because GoneSmart returned `uf5` from the list callback while `PlainListDialogAdapter` cast the callback result to `ag5`. Callback-return resolution now derives the concrete singleton from the native dialog-event constructor's retained generic callback type when available, with `ag5` as the shape-validated 4.2.1 fallback rather than accepting any singleton-shaped obfuscated class. Device re-acceptance remains pending.

The same `14c601aba04b` trace proved that Track Mix now starts with the intended selected seed and can prepare/rank the first GoneSmart rows correctly, but `CLEAR_QUEUE` still creates a host playback-source race. During the short one-row interval GMMP queries `current+1` and caches `next audio source is null`; after that, the queue reaches Initial Size with valid position-2/3 rows and at natural end GMMP advances queue metadata/scrobbling, yet the new Current decoder does not start. The next candidate therefore leaves queue ownership unchanged and, only after Initial Size is present, re-dispatches the already passively verified native Current-position writer on the same verified position. This controlled refresh is accepted only if Current Track ID, position and non-empty upcoming queue are unchanged before/after; it exists solely to make GMMP rebuild current/next AudioSources and never substitutes a guessed playback method or direct Room mutation. Device re-acceptance remains pending.

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


### 2026-10-11 — r27 device re-test: pre-command source ownership

Device build `16a2d76fd574` (`gmmp421-r27`) disproved the same-position playback-refresh repair. Track Mix built the correct five-row queue and `dx3.c2(1)` refresh verified, but the runtime had already asked for queue position 2 while it was absent (`next audio source is null`). At the natural transition GMMP changed Current metadata to the GoneSmart-selected Track ID but never started that track's decoder. The durable 4.2.1 ordering is therefore prevention, not refresh: after `CLEAR_QUEUE`, refill the entire Initial Size through `qr.z(...)` while native Auto-DJ is still off, verify Current + queue size, then send `AUTO_DJ` and hold its delayed natural refill through the bounded command window. `gmmp421-r28` encodes that ordering and removes the r27 playback-refresh policy.

The same run also showed Playlist Link falling through to the native Smart-Playlist dialog because the proven `as4$g.accept(...)` callback reached `showLinkTypeMenu()` with a non-Activity Context (`active Activity unavailable`). The editor now preserves the real Activity and `menuLink` anchor during menu inflation and reuses those weak references at the callback boundary; the native toolbar dispatch/continuation remains intact.

### r29 device evidence and repair

The 2026-10-11 exact-head `f1fb341f32aa` / `gmmp421-r28` device trace disproved the r28 post-clear ordering. The bridge itself was healthy and reached the proven 4.2.1 Playlist Link callback, but the toolbar Add action logged `edit=true | intercepted=false`: GMMP's selected-index field can remain non-null during a toolbar Link action, so selected-rule state alone cannot distinguish Add from edit. r29 passively observes `View.performClick()` only for the exact captured native `menuLink` View. A recent exact toolbar click classifies the following proven `as4$g.accept(...)` callback as Add; without that marker, selected-rule state continues to identify a genuine edit. GMMP's original toolbar listener remains untouched.

The same trace proved `CLEAR_QUEUE` itself is too destructive for 4.2.1 Track Mix startup. Immediately after the clear GMMP queried queue position 2 and MusicService cached `next audio source is null` before the later r28 pre-command refill began. The final queue later contained the correct GoneSmart Track IDs and Current metadata advanced naturally, but the first generated Current still never opened its decoder. Therefore r29 retires `CLEAR_QUEUE` entirely from the 4.2.1 Track Mix start path.

On 4.2.1, native Play/`WAIT_PLAY` continuity rows now remain alive while the selected-current recommendation pool is prepared. If fewer than `Initial Size - 1` future rows exist, only the actual native-slot shortfall is requested before provider work. GoneSmart then retargets the first already materialized contiguous upcoming queue entities in place through the verified Queue DAO `O0(List)` writer, preserving their `queue_id` and `queue_position`. Rewritten `current+1` is independently verified while all old rows still exist; only afterwards are surplus rows removed through the typed native DAO delete. Prepared GoneSmart Track IDs fill the first upcoming slots, while untouched native Track IDs remain only for a real prepared-pool shortfall. There is no second full Initial-Size refill after recommendation preparation. Native `AUTO_DJ` is sent only after the retargeted queue verifies.

The four native rows visible during a slow recommendation calculation are therefore continuity/safety slots rather than a second Track Mix queue. r29 no longer deletes and re-adds them; it retargets those same native slots in place. Completely suppressing those rows before a robust pre-Play selected-Track identity exists would recreate the already proven missing-`current+1` playback failure, so continuity takes precedence over hiding the temporary native choices.

Device re-acceptance remains pending. Required evidence is: Playlist Link logs `BRIDGE TOOLBAR LINK` followed by `toolbarAdd=true | edit=false | intercepted=true`; Track Mix contains no 4.2.1 `CLEAR_QUEUE`, logs `TRACK MIX RETARGET | verified=true` and `MIX INITIAL RETARGET | verified=true`, never emits a startup `next audio source is null`, and the first generated Current opens a real decoder/audio source naturally.


### r30 pre-Play Track Mix bootstrap

r29 removed the `CLEAR_QUEUE` null-source gap, but the r28 device trace also proved a deeper race: during the long selected-current recommendation calculation GMMP had already materialized, selected as `next audio source`, and opened the decoder for an unrelated native Track 2. Therefore changing that queue row's Track ID after recommendation preparation is not playback-safe even when `current+1` never disappears. r30 moves Track Mix selection before native Play. A bounded semantic resolver walks only the native context-menu callback graph and compares direct scalar evidence against the read-only GMMP library cache; it accepts only a unique strong target and otherwise fails closed before touching playback. Recommendations are then prepared from a synthetic selected-current session. For the first `qr.z(...)` refill whose native Current independently matches that resolved Track ID, GoneSmart wraps the existing refill with an immutable bootstrap selection window. The already accepted native Auto-DJ selection hook replaces `kr.F1(int)` result Track IDs before queue rows and AudioSources are materialized. Any prepared-pool shortfall remains native. Later queue mutation is prune-only and must not rewrite the first upcoming Track IDs.


## r31: parameter-free Playlist Link membership and pre-Play qr recovery

The r30 device log accepted the Playlist Link two-way chooser but exposed two independent follow-ups. Ordinary `.m3u` parsing succeeded while the subsequent parameterized `track_uri IN (...)` lookup failed because the accepted 4.2.1 direct Room carrier has no uniquely writable non-zero argument-count field. r31 therefore builds a short-lived parameter-free `song_id`/`track_uri` index and performs canonical-path membership matching in GoneSmart. Native Smart-link labels and ordinary Playlist-link labels are also separated at the formatter boundary.

Track Auto-DJ r30 could fail before its first pre-Play diagnostic when no `qr` instance had yet been captured by the constructor/refill hooks. r31 adds passive recovery on already-proven natural `qr` state-writer calls and a bounded read-only context-menu graph lookup. It does not issue a command to discover `qr`, does not restore `CLEAR_QUEUE`, and keeps the pre-Play recommendation/bootstrap invariant.


### r32 evidence: Playlist Link main-thread evaluator and Track Mix receiver ownership

The r31 device pass proved that the 4.2.1 Smart-Playlist evaluator calls Playlist Link compilation synchronously on GMMP's main thread. Even a parameter-free direct Room query therefore fails with Room's main-thread guard. Playlist Link must consume the prewarmed in-memory `GmmpLibraryReader` snapshot and must not query Room from that evaluator. The same pass proved that Track Mix can resolve the clicked track and prepare four GoneSmart recommendations correctly while still failing when the module-level refill reference has been overwritten by a `dx3` state object. Explicit Track Mix refill now receives the controller-verified `qr` instance directly.

- r32 performance guard: the complete `track_uri -> song_id` index is constructed together with `GmmpLibraryReader`'s startup/cache refresh off the UI thread. Smart-rule compilation may only consume the finished immutable index; it must not scan/canonicalize the whole library synchronously.


## r33 focused repair
- Pending Track Mix now pins CURRENT by the uniquely proven native queue entry ID across Smart-list materialization/refill/prune. The hint is request-scoped and fails closed if no longer unique.
- Smart-Playlist link type text is corrected only after the native `smartRuleListRecyclerView` row bind and only for a uniquely proven native Smart-Playlist reference. Ordinary Playlist bridge rules are untouched.
- r32 Playlist Link membership evaluation remains accepted and unchanged. `CLEAR_QUEUE`, post-materialization Track-ID retarget and same-position playback refresh remain retired.


## r34 focused repair
- Preserve the native styled Smart-rule `CharSequence`; relabel only its proven `Playlist` prefix. No TextView geometry/typography mutation is allowed.
- If Track Mix has zero prepared GoneSmart recommendations and GMMP is about to materialize a larger Playlist/Smart-Playlist through the accepted `MusicService.w1(action=0)` boundary, bound that native source pre-materialization to the unique selected target plus the available Initial-Size suffix. This prevents the selected temporary queue entry from being discarded by the full source rebuild while preserving GMMP ownership of current+1/AudioSource preparation.
- Prepared-recommendation Track Mix (including the r33 Levels acceptance path) is unchanged. Missing/duplicate targets fail closed. `CLEAR_QUEUE`, post-materialization Track-ID retarget and same-position refresh stay retired.

## GMMP 4.2.1 r35 Track Mix CURRENT identity
- r34 device evidence proved the BUNT. context-track Play does not traverse the `MusicService.w1(action=0, List, ...)` list boundary: `prepared=0` was observed but `MIX NATIVE PLAY WINDOW` never fired. That source-window experiment is retired and must not be revived as a Track Mix dependency.
- On 4.2.1 a Smart-Playlist rebuild may replace the selected row's native `queue_id` while the already-proven selected audio keeps playing. After Track Mix has first proven CURRENT by native queue entry, and only inside the existing Smart-source completion guard, a vanished entry hint may rebind CURRENT to the pre-resolved exact `track_uri` iff that exact path occurs exactly once in the rebuilt native queue.
- The exact-path rebind is request-scoped, one-shot, case-sensitive, and non-normalizing. Zero or multiple exact path matches fail closed. Track ID alone never authorizes the rebind. Once accepted, the replacement native `queue_id` becomes the new pinned identity for settle/prune/Auto-DJ verification.
- The accepted r34 Smart-Playlist row-label behavior remains invariant: preserve GMMP's native styled `CharSequence` and replace only the localized prefix; never mutate row gravity, text size, typeface, or layout.

## GMMP 4.2.1 r36 zero-provider Track Mix fallback
- r35 device evidence accepted the exact-path CURRENT rebind: BUNT. moved from temporary `queue_id=5680869` to rebuilt `queue_id=5680876`, then reached `MIX PLAY VERIFIED`, `MIX CURRENT PIN`, `MIX AUTO-DJ` and `MIX VERIFIED`. The remaining defect was downstream: pruning 7,214 source rows to five retained absolute CURRENT position 6 (`6 of 5`) and retained four source Smart-Playlist followers because the prepared GoneSmart pool was empty.
- A genuine `prepared=0` Track Mix must not treat arbitrary source-list followers as its four Initial-Size Auto-DJ tracks. After the rebuilt exact CURRENT is stable, r36 keeps only that pinned native row, rebases it to position 1 through a Track-Mix-only position writer that was first observed during natural GMMP state calls, then lets the native `qr.z(count)` refill own the replacement Track IDs. Prepared GoneSmart bootstrap paths are unchanged.
- A same-value natural state-writer call is only a provisional Track Mix candidate. Queue Flip does not consume it. The first controlled rebase write must independently verify both GMMP's native position signal and a unique queue row at the new position before the writer is promoted. Ambiguity fails closed.
- r36 does not guess an obfuscated next-source method. During the natural zero-provider start it records a bounded set of `MusicService(Int)->void` calls; once the transient selected CURRENT is independently known, exactly one call may be promoted iff its observed argument equals `currentQueuePosition + 1`. After native refill and position compaction, that same naturally observed boundary is invoked for position 2 so GMMP rebuilds `current+1` from the new native Auto-DJ row instead of retaining the already-cached Smart-Playlist AudioSource. Multiple/stale candidates fail closed.
- `CLEAR_QUEUE`, post-materialization Track-ID rewriting, and the retired same-position CURRENT refresh remain forbidden.

## GMMP 4.2.1 r37 Track Mix bootstrap/current-coordinate repair
- r36 device evidence proved the Levels recommendation pipeline itself remained healthy: ListenBrainz and Last.fm produced 125 merged candidates and 20 accepted exact local matches, but only the first prepared Track ID materialized. After `MIX CURRENT PIN`, a second native refill re-read CURRENT without the pinned queue entry, got `current=null`, skipped the remaining prepared IDs and fell back to GMMP's random native Auto-DJ pool. r37 stores the verified `queue_id` on the pending request and supplies it to every later prepared bootstrap read.
- The same r36 pass proved the zero-provider BUNT. path reached `MIX CURRENT PIN` but failed before mutation because the Track-Mix Current writer was assumed to share `queue_position` coordinates. Natural device evidence showed the structural state call with argument 4 while GMMP owned queue position 5. r37 therefore treats the Track-Mix-only state writer as request-scoped and infers only offset 0 or 1 between the natural method argument/read signal and the exact pinned queue position. Wider, stale or ambiguous mappings fail closed.
- The r37 mapping never promotes the weaker Track-Mix state candidate into Queue Flip's global writer proof. `CLEAR_QUEUE`, post-materialization Track-ID retarget and same-position CURRENT refresh remain forbidden.

### GMMP 4.2.1 Track Mix owner retention

State-writer hooks are installed on child/state-host methods discovered from fields of the native Auto-DJ owner. The hook receiver is therefore evidence for the writer boundary, not a replacement Auto-DJ owner. Preserve the independently captured controller-owned Auto-DJ instance across child callbacks; otherwise request-scoped Current-writer correlation will inspect the child as though it were the owner and fail closed even when the natural writer call was observed.

### GMMP 4.2.1 Track Mix pre-Play target gate

r38 device evidence exposed a Smart-Playlist race before zero-pool isolation: native Play first changed CURRENT to a transient non-target row even though the clicked track had already been uniquely resolved before Play. A foreign first CURRENT is now transition evidence only. Its native queue entry may be retained as a bounded rebind anchor; if that entry disappears inside the Smart completion guard, the exact pre-Play target path may recover the materialized row only when the recovered Track ID equals the pre-Play target. This initial recovery does not consume the existing one-shot post-target exact-path rebind, because GMMP may replace the first materialized target row once more while completing the source rebuild.
