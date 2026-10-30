# GMMP compatibility playbook

This file is GoneSmart's maintained version ledger and repeatable update procedure for GoneMAD Music Player (GMMP). GMMP internals are obfuscated, so semantic ownership and observed behavior matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` evidence files.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original 0.3.x/early-0.4 mappings and device evidence. |
| 4.2.1 | **Accepted current target** | Playlist Link and Track Auto-DJ were re-accepted after the final R72 device/audit pass and are integrated into the 0.5.0 release candidate. |
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

The accepted 4.2.1 Track Auto-DJ contract is the semantic continuation of the last known-good native-ownership behavior, not a literal port of old R8 names:

1. Resolve the clicked track before native Play from unique semantic evidence and dispatch GMMP's exact native Play action.
2. Treat playback/current observations during the bounded Smart-Playlist/file/Queue settling window as evidence, not as permission to retarget to an unrelated track. Ambiguity fails closed.
3. Start a fresh GoneSmart recommendation session for every explicit Track Auto-DJ invocation.
4. Establish the selected native queue entry as the sole logical seed/root through the already verified queue/current boundaries. **Do not use `CLEAR_QUEUE` for GMMP 4.2.1 startup.**
5. Read GMMP's `autoDj_initialSize` dynamically; never hard-code a five-row queue.
6. Let GMMP's normal Auto-DJ/refill lifecycle own follower-row creation, ordering and decoder materialization. GoneSmart influences eligible follower identities only through the ordinary Smart Auto-DJ selection path.
7. Do not rewrite Track IDs after a next AudioSource/decoder may already exist, replay playback to repair a queue, or invoke a guessed next-source method.
8. The naturally observed MusicService integer event used by the accepted path is identified by context/correlation; its obfuscated name/argument are not general semantic authority.
9. Verify final queue size/positions, selected Current identity and any native writer postconditions before reporting success.

The final 4.2.1 path is device-accepted across ordinary playlists, Queue, playable file-browser rows and large Smart Playlists. The detailed Rxx chronology remains in `AGENTS.md`, `GMMP_421_COMPLETION.md` and the dated evidence files; those historical intermediate architectures are not the current implementation contract.

### Recommendation read boundaries

The 0.5 recommendation blend has no new guessed state-changing writer. Its read-side compatibility contract is:

- resolve the live Auto-DJ owner semantically/structurally, not by one obfuscated accessor name;
- resolve the GMMP Room database and compatible Cursor query boundary structurally and fail closed on ambiguity;
- discover the playlist-catalogue path/URI column from the actual data shape;
- treat the historical TrackDao field/method mapping only as an optional tested-version fast path; if it is unavailable, the structural read-only Cursor path must remain sufficient;
- cache successful structural bindings and playlist/library indexes so accepted-version runtime does not repeatedly perform broad discovery.

A future GMMP compatibility self-test must include these read-only Smart DJ boundaries in addition to the queue/UI mutation families.

### Recommendation-pool latency

- successful fills may top up again as soon as low-water requires it;
- the long provider backoff applies only after an unproductive fill;
- startup/session prewarm is asynchronous;
- Track Auto-DJ recommendation preparation is selected-seed-specific and never authorizes a stale session to leak into the new explicit mix;
- a non-empty prepared pool is consumed through the ordinary Auto-DJ selection boundary while GMMP retains ownership of native row creation;
- zero-suitable-track behavior follows the configured native fallback policy without inventing a Track-Mix-specific random padding path;
- accepted bindings/caches are reused so normal refills do not repeat broad reflection/database discovery.

### Playlist / Smart-Playlist surfaces

Accepted on 4.2.1:

- Playlist folders;
- Smart-Playlist folders;
- ordinary and Smart multi-selection;
- Playlist Link — re-accepted after the 4.2.1 repair;
- Play flipped;
- Queue Flip;
- navigation/player sparkle badges and Status/Compatibility UI.

Resolvers prefer semantic adapter/model/writer ownership. Historical class names remain fast paths only where useful and safe.

For Playlist Link, the native editor Link action is passively correlated from a real user-triggered GMMP dialog/event chain; the accepted 4.2.1 binding must retain that proof. A historical method name plus the same parameter shape is insufficient evidence for interception. The 2026-10-08 device trace now proves the GMMP 4.2.1 chooser boundary at `as4$g.accept(...)`: that callback appears immediately upstream of GMMP's native event chain and resulting `MaterialDialog.show()`. The unrelated `as4.h2(boolean)` shape therefore remains untrusted.

For Playlist Link specifically, do not rely on a post-inflate `MenuItem` listener as the sole dispatch boundary. GMMP can replace that listener later in the Smart editor lifecycle. Keep the accepted 4.2.0 `ds4.g2(boolean)` path exact. On 4.2.1, intercept the proven `as4$g.accept(...)` callback for GoneSmart's type chooser and retain the exact callback instance/payload as a one-shot native continuation; choosing Smart Playlist resumes that original continuation. This avoids inventing an upstream mapping while keeping ordinary native edit/disabled paths pass-through.

For the Smart-editor add-rule action, resolve only a method declared directly by the resolved presenter whose single parameter is exactly the resolved Smart-rule base class and whose return type is `void`. Historical `P1` (4.2.0) and observed `Q1` (4.2.1) names are only fast paths. Broad assignability is intentionally rejected because 4.2.1 exposed false candidates such as inherited `equals(Object)` and `S1(...)`.

Historical 4.2.1 repair chronology follows; statements that say “pending” below describe their revision at that time, not the current release state.

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


### r44 native-fallback AudioSource continuity

The 2026-10-14 BUNT. device log proved that a visually correct five-row queue is insufficient. Seed-only isolation caused GMMP to query queue position 2 while absent and cache `next audio source is null`; after later refill/rebase, Track 2 changed metadata but never opened its decoder, and subsequent Track Mix playback inherited the poisoned service state. Therefore the old zero-provider seed-isolation + manual next-source-refresh sequence is retired. Native fallback must preselect its IDs before Play, prime the existing current+1 row before the natural AudioSource read, preserve that exact slot-2 Track ID through the source rebuild, and only then prune/rebase. No playback method is replayed.


## GMMP 4.2.1 Track Mix architecture correction (r45)

The 2026-10-14 r44 device trace proved that native-fallback preselection plus post-materialization `current+1` retargeting violates the accepted playback ownership model: BUNT. had zero GoneSmart recommendations but was presented as a successful four-track prepared pool, and the queue row for slot 2 diverged from GMMP's already cached decoder AudioSource. r45 retires that path. Track Mix again waits on the same provider/local-match pipeline up to the established 60 s preparation bound. A zero real-match result is FALLBACK/red and fails closed before native Play, leaving queue/audio state untouched. Device re-acceptance must first prove Levels uses real GoneSmart matches again; BUNT. must no longer turn green or mutate playback when the smart pool is empty. A native no-match fallback may be restored only after the 4.2.1 equivalent of the accepted 4.2.0 seed-isolation boundary is independently proven before decoder materialization.

## Permanent porting rule: preserve behavior, remap boundaries

For a GMMP version update, the previous accepted GoneSmart feature behavior is the specification. Update only hook discovery/binding and shape adapters required to reach the same semantic native boundary. Do not change feature algorithms as a substitute for finding the new hook. If the exact native replacement is not proven, fail closed or use a narrowly scoped compatibility shim that reproduces the old postcondition without guessing mutable obfuscated state, and document why it exists.

For Track Auto-DJ, GMMP 4.2.0 commit `6f7751cb9a5a9c5ebc1d48da23aecbb68ec32c01` is the baseline for queue ownership semantics: one exact native seed, logical position/current/append root at 1, dynamic GMMP Initial Size, GMMP-owned follower materialization, and a fresh GoneSmart recommendation session per explicit invocation.


### 2026-10-16 Track Auto-DJ predecoder semantic port

The final post-decode `MusicService(Int)->void` replay experiment was retired. Device evidence showed that the visible five-row queue could be fully correct and the replay could return successfully while playback still froze on/after the second-track transition. Historical R49 evidence had already shown the same boundary is not a player-cache refresh.

GMMP 4.2.0 remains the semantic baseline, but its low-level implementation cannot be copied literally: `ex3.c(...)`, append allocator `ex3.p`, queue DAO mutation and `b2(int)` current-state movement formed one coupled owner. GMMP 4.2.1 splits those responsibilities and can ask for `current+1` during native Play before a later Track-Mix worker can isolate/refill the seed.

For all GMMP 4.2.1 song-menu starts, including Queue-context starts, the compatibility port therefore restores the already-proven predecoder ordering rather than repairing playback afterwards. Selected-seed GoneSmart preparation happens before Play. The natural `qr.z(int)` hook is allowed to establish a zero-match native Initial Size synchronously after rebasing the exact selected seed to the accepted position-1 invariant, or to consume the prepared Smart bootstrap. The original GMMP caller then owns its normal next-source/decoder creation. Existing R50/R51 guards may only preserve those already-selected identities across a later source rebuild. The 2026-10-16 R61 device trace retires the R59 Queue-context exception. That run independently verified the selected queue target, Current writer, seed isolation/rebase and final five-row native fallback queue, but GMMP reached `onTransitionComplete`, queried queue position 2 and cached `next audio source is null` before those four followers had been materialized. The later visible queue was correct but arrived after the next-source boundary. Queue-context Track Auto-DJ therefore now uses the same natural `qr.z(int)` predecoder ordering as the R60 path; the mutation semantics are unchanged, only their placement before decoder materialization changes. One Queue-context device re-acceptance remains required.

### 2026-10-17 R62 current+1 predecoder fallback

Device evidence shows that GMMP 4.2.1 does not deterministically issue a natural `qr.z(int)` refill before the first next-source lookup. In the failing BUNT. run the selected seed was already Current at logical position 1 and the unique natural `MusicService.I2(2)` current+1 boundary was observed, but no earlier `qr.z` occurred; GMMP therefore materialized the stale Smart-Playlist follower before Track Mix could prepare Initial Size. When that exact one-shot current+1 boundary arrives first, GoneSmart now reuses the same R60 seed-isolation/native-fill sequence synchronously with `originalRequested=0`. The full dynamic follower count is still requested through the verified native `qr.z` path and verified before returning to GMMP's original current+1 lookup. This is a predecoder fallback trigger, not a playback refresh: no MusicService/decoder method is replayed after materialization, and ambiguity still fails closed.

### 2026-10-17 R63 prepared Queue seed isolation

The first post-R62 Queue-context test exposed a prepared-pool-specific semantic gap rather than another decoder timing failure. Native Queue Play correctly selected Green Day and GoneSmart independently pinned the exact queue entry, but Current remained at absolute position 4 of the existing five-row queue. The prepared bootstrap path treated the old position-5 row as an available upcoming slot, requested only three additional rows, and appended three prepared Smart tracks. Verification then correctly failed because the first post-seed slot still contained the stale prior-queue track (`expected=1470`, `actual=12819`). Queue-context prepared starts now restore the accepted 4.2.0 invariant before bootstrap: isolate the exact pinned selected entry, rebase it to logical position 1, verify a one-row queue, then request the full dynamic `Initial Size - 1` through the existing native `qr.z` selection hook. Non-Queue prepared starts are unchanged, and no playback/decoder replay is introduced.

### 2026-10-17 R64 preworker current+1 race

A post-R63 BUNT. trace exposed a scheduling race inside the accepted R62 fallback rather than a new queue semantic failure. `MusicService.I2(2)` was naturally intercepted before GMMP read position 2, but the Track-Mix worker had not yet published `provisionalCurrentQueuePosition`; the command could only be promoted to CURRENT+1 after the original call had already returned a stale Smart-Playlist follower. The fix keeps the same predecoder boundary and adds no replay: on that first natural Int command, zero-provider Track Mix may synchronously prime Current=1 from the exact unique selected row plus the independently verified Current-position writer/readback. This early proof is intentionally restricted to an already-position-1 selected Current with natural argument 2, because the existing R62 isolation restores the position-1 invariant before returning to the original caller. Any wider coordinate or identity/readback disagreement stays fail-closed.

### 2026-10-17 R65 prepared Queue coordinate normalization

The first R64 combined device pass accepted the BUNT. zero-provider path end-to-end, including a real non-null CURRENT+1 AudioSource and final five-row `MIX VERIFIED`. Two subsequent `menu_gm_context_queue` starts also proved that R63 now selects and materializes the correct recommendation identities: the selected Queue row was pinned, isolated/rebased to Current position 1, and four prepared Smart Track IDs were inserted. Both runs failed later and identically at `planNativeRetarget` with `Native upcoming queue positions are not contiguous`. The native queue already had the correct five Track IDs/order; only GMMP's hidden append coordinates remained high/stale (the second run exposed Current at 7215 before isolation). R65 therefore reuses the accepted position-only anchored compaction immediately after the prepared bootstrap and before prune, normalizing the already-correct five native rows to `1..Initial Size` without changing queue IDs, Track IDs, Current identity/state or playback. No recommendation or decoder behavior changes. One Queue-context device re-acceptance is still required.

### 2026-10-17 R66 queued writer dispatch + no-match notice gating

Device evidence after R65 showed three successful Track-Mix starts and one prepared Queue-context failure. The failed run had 14 suitable local candidates and four prepared Track IDs, but the already passively verified `dx3.c2(int)` Current writer did not begin/finish inside the shared 1.5 s controlled-dispatch budget while GMMP logged >2.3 s worker contention. R66 separates native scheduling from semantic verification: controlled dispatch may wait up to 4 s on the proven native looper/executor, while post-invocation readback remains 1.5 s and unchanged. Queued work that has not started is removed/cancelled on timeout; no alternate writer is guessed.

The same log contained two genuine zero-prepared starts and two Queue starts with four prepared matches. Track Mix now defers `no-matches-native-fallback` while preplay is unresolved, suppresses it if the completed pool is non-empty, and shows the owned notice only after a completed empty preplay result.

### 2026-10-17 R67 file-browser folder menu scope

Post-R66 device testing accepted Track Auto-DJ as robust across repeated starts from multiple playlists/queue contexts and file-browser files. The remaining UI defect was isolated to GMMP's Folder tab: `menu_gm_context_file_browser` is reused for both a real audio-file row and a directory row. The real-file path still resolves and starts Track Auto-DJ, while a directory click reaches the same GoneSmart action but has no unique single-track target and correctly fails closed at `MIX PREPLAY TARGET | resolved=false`.

R67 keeps the accepted real-file action but adds a pre-menu semantic gate. Only a bounded selected-row callback graph proving an existing regular filesystem file may expose Track Auto-DJ. A directory or unknown identity hides the action; adapter collections/child rows are deliberately not traversed so files inside a folder cannot authorize Track Auto-DJ on the folder itself. All playback, recommendation, refill and queue-mutation semantics remain unchanged.

### 2026-10-17 R68 file-browser selected-row collection gate

R67 correctly removed Track Auto-DJ from directory rows, but the next device pass showed that it also hid the action from real songs. All tested `menu_gm_context_file_browser` openings reported `browserContext=unknown`, because GMMP 4.2.1 keeps the selected playable row behind a small native collection while R67 intentionally refused to traverse containers. Historical accepted song starts independently prove that the pre-Play resolver reaches exact path/ID/title/artist evidence through that bounded collection, while a directory row still resolves no unique Track.

R68 keeps the cheap filesystem-only menu gate and does not move the expensive library resolver onto the UI thread. The gate now traverses collections/maps/arrays only under the same bounded selected-context philosophy (depth 5, 240 objects, 48 items per container). Eligibility is decided only by the unique filesystem target at the shallowest proven depth: one existing regular file exposes Track Auto-DJ; one existing directory hides it; multiple distinct shallow targets or no target fail closed. A selected directory therefore outranks any deeper child file, and a broad adapter-style collection of multiple songs cannot authorize the action. Playback, recommendation, queue mutation and refill behavior remain unchanged.

### 2026-10-17 R69 deferred native file-browser menu shape

The R68 device pass disproved the remaining filesystem-graph assumption: every tested file-browser row still ended as `browserContext=unknown | evidence=none | inspected=240`, including a real song. The same log gives a stronger host-owned discriminator. With GoneSmart hidden, the real song popup is 1440 px high while directory popups are 1152 px; the accepted pre-R67 trace shows the same two native shapes becoming 1584 px and 1296 px respectively when GoneSmart's single 144 px row was inserted. That establishes 10 visible native actions for a playable file and 8 for a directory on the accepted GMMP 4.2.1 build.

R69 therefore retires the unsuccessful reflective filesystem scan from the menu hot path. The MenuInflater hook first inserts Track Auto-DJ hidden, because GMMP applies row-specific native-item visibility only after `inflate(...)` returns. On the next main-loop turn GoneSmart counts only GMMP's visible native items, excluding its own row: exactly 10 exposes Track Auto-DJ, exactly 8 keeps it hidden, and every other cardinality fails closed. This signal grants UI exposure only. A click still runs the existing worker-side semantic resolver and must uniquely prove the selected Track from strong path/ID/title/artist evidence before native Play can be dispatched; playback, recommendation, queue and refill semantics are unchanged.

### 2026-10-17 R70 opaque I2 argument + Queue-tail zero-pool boundary

The first post-R69 file-browser failure finally disproves the remaining R62/R64 coordinate assumption without disproving the callback itself. The selected Track (`18165`) was independently Current at `queue_position=2`. GMMP then naturally invoked `MusicService.I2(2)`, but inside that same original call immediately queried `queue_position=3` and materialized that row as the next AudioSource. Therefore `I2(int)` remains the repeatedly correlated 4.2.1 next-source materialization event, while its integer argument is not queue position; the older `argument == Current+1` proof worked at Current=1 only by coincidence.

R70 keeps the callback passive and fail-closed. The accepted 4.2.1 `MusicService.I2(int):void` alias/signature may identify the natural event, but the argument grants no coordinate authority. Before the original call proceeds, GoneSmart must uniquely locate the pre-Play-selected Track, read the independently verified Current-position writer, and prove that selected row, Current row and writer readback all agree on one positive absolute `queue_position`. The existing anchored zero-pool native fill then preserves that Current coordinate, and the first guarded follower position is derived as verified `Current+1`. No MusicService method is replayed.

The same device pass exposed a separate Queue-tail case. A Queue click selected exact Track `4843` at position 5 of a five-row queue with zero prepared matches; no natural `qr.z` and no accepted next-source callback occurred before the worker verified the exact Queue target, so the request failed even though GMMP requested Auto-DJ only later. R70 permits the existing position-1 zero-pool native fill at that point only when the source is `menu_gm_context_queue`, the selection proof is `queue-context-target`, the selected row is the final queue row, no zero-pool boundary has been attempted/prepared, and no accepted next-source event has been observed. Because no follower exists in that exact state, this remains before any follower AudioSource can be materialized. Prepared-match starts and all non-tail/non-Queue cases are unchanged. Device re-acceptance remains required.

### 2026-10-17 R71 anchored I2 zero-pool isolation

The first R70 device pass proves its new coordinate detection was correct but exposes an implementation mismatch downstream. For Track `18165`, GoneSmart proved the selected row, native Current and verified writer/readback all at absolute `queue_position=2`, then correctly derived `nextPosition=3`. The natural-boundary path nevertheless reused the older generic seed-isolation helper, which rewrites Current to position 1 and therefore attempted a controlled `dx3.c2(1)` state write. That dispatch was queued behind GMMP's executor (`started=false`) and timed out; the original `I2` then resumed, queried position 3 and received `next audio source is null`. A later file-browser invocation succeeded through the independent native-refill path specifically because its seed was already position 1 and no state write was required.

R71 restores the R70 ownership contract instead of weakening writer dispatch. At the accepted natural `MusicService.I2(int)` event, after exact Current/target/writer agreement, GoneSmart removes only the old source history/followers and preserves the selected Current row at its proven absolute queue position. It never rewrites that row or native Current state. Native `qr.z` supplies the `Initial Size - 1` follower identities; before the original I2 resumes, the existing anchored position-only compactor normalizes those follower rows to `Current+1..Current+N` while preserving queue IDs, Track IDs and Current state. The existing native-refill and Queue-tail fallback keep the accepted position-1 invariant and are unchanged. No MusicService replay, decoder mutation or executor-inline writer exception is introduced. Device re-acceptance should show `TRACK MIX SEED ISOLATION ANCHORED ... currentPosition=2 | stateWrite=false | rebased=false`, anchored native refill compaction, `MIX ZERO-POOL NEXT SOURCE PREPARE ... currentPosition=2 | nextPosition=3`, no `QUEUE POSITION DISPATCH TIMEOUT`, and final `MIX VERIFIED`.

### 2026-10-17 R72 + final 4.2.1 acceptance audit

- R72 corrects only the file-browser menu eligibility evidence: playable files are accepted at 9 or 10 visible native actions, exact 8 is the proven directory shape, and 7/other shapes remain unknown/fail-closed. Menu shape never supplies Track identity.
- The final bundled device pass accepted the R70/R71 natural next-source coordinate model and Queue-tail fallback. `I2(int)` remains an event identity only; its argument is never a queue coordinate.
- Probe graduation is now explicit implementation policy: once a concrete runtime shape is accepted, successful mapping/discovery logs are one-shot and the cheapest proven binding is reused. A new runtime type or failed postcondition re-enters semantic discovery and may emit rich diagnostics.
- The obsolete r45 `MenuItem,List -> boolean` context-Play observer and dead zero-pool list placeholder are removed after acceptance. They were diagnostic only and supplied no mutation authority.
- The accepted library Cursor fallback is cached per concrete Auto-DJ runtime type after the native TrackDao path is proven unavailable. This does not turn Cursor data into write authority; all state-changing queue work remains behind verified native callbacks/DAOs.
- Final release-readiness auditing also includes GMMP-host localization, hot-path reflection/view scans, repeated logging, dead probes/APIs, exact-head CI and temporary-file residue.

### 2026-10-30 normal Auto-DJ deferred refill and extra Next (experimental)

The accepted 4.2.1 native refill boundary `qr.z(int)` remains the sole queue
insertion owner. An empty recommendation pool may now be prepared off the
native transition stack; the pending native retry reuses the *same hooked
`Method` object* and runs only after revalidating the original queue session.
This reduces the accepted fast path's dependence on a second literal R8 lookup
but does not magically resolve a future remapping of `qr.z` itself. A new GMMP
version must prove its Auto-DJ refill/selection boundaries before enabling
their mutations; shape-only guesses remain prohibited.

The optional extra-Next continuation observes only a visible, resource-name-
recognized native player click while cold refill is pending; the original
click always proceeds. Once the native method has appended new queue entries,
the worker may send GMMP's public `gonemad.gmmp.command.NEXT` broadcast if the
session/entry/current/next proofs still hold. Each additional command requires
bounded native Current readback. Unknown button IDs, remote/hardware actions,
new playback, missing queue additions and lost Current fail closed. The 2026-
10-30 source-only audit cannot establish the installed GMMP player button ID;
device acceptance and exact-head CI remain prerequisites for shipping.
