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

#### Physical playlist creation (2026-11-11 correction, device recheck pending)

The 4.2.1 APK confirms two native create callbacks: `sp3.invoke(Object,Object)`
for the main playlist tab and **`go3.invoke(Object,Object)`** for Add to
Playlist (the old `fo3.invoke` is now unrelated). Both resolve the configured
`playlist_saveLocation` through `tp3.w:vp3 → vp3.E:r15.getValue() →
sa4.getValue()` (4.2.0 used `vp3.F → va4.getValue()`). The native writer remains
`hp3.d()` and GMMP's scanner is `t6.g(Context,String[])`. A scoped, identity-
checked getter substitution is allowed only while the exact native create
callback runs and only after the configured root matches the independently
indexed physical playlist root. Reject any ambiguous binding rather than
creating the playlist in the root. In 4.2.1 the create callback returns the
verified `ag5` Unit singleton; `uf5` is no longer Kotlin Unit. The picker
selection source is `go3.n:ho3 → ho3.z:io3 → io3.a:List`, which must be
restored after any create-only native transaction.

The 2026-11-11 log exposed two independent issues: physical-folder create
controls were unavailable because the old getter/picker hooks did not match,
and clicking the resulting root playlist failed GoneSmart's synthetic row
binding after twelve frames (`Summer 2027.m3u`, native position 189). The row
action now listens for the actual native RecyclerView bind/pre-draw event and
dispatches only to a holder with the verified exact playlist path, subject to
a bounded cancellation timeout. **An actual 4.2.1 device test must confirm**
main-tab creation, picker create-only, native index updates, actual M3U
location, save/reopen and off-screen entry navigation; source/CI checks alone
do not certify the host interaction.

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

### 2026-11-11 playlist creation follow-up: native row ordering

The device log for a newly created `Festival 2027.m3u` confirms GMMP
reloaded the playlist catalogue (257 native entries), but opening that playlist
and two older playlists from GoneSmart's folder overlay failed at the
native-row binding gate (`expectedPosition=96/95/104`). These are GoneSmart
navigation failures, not evidence that the M3U files are invalid.

`NativePlaylistSourceInspector` previously accepted a full playlist count from
reflection-based collection traversal as though its iteration order matched
GMMP's RecyclerView adapter positions. Completeness does not prove order.
For a live, independently correlated `NativePlaylistRuntimeBinding`, the
inspector now derives all paths/models from the verified native indexed
`getItem(position)` accessor in adapter order. If that established binding
cannot read the complete native list, the folder overlay fails closed instead
of inventing a path-to-position mapping from an unrelated collection. Native
row click handlers, GMMP playlist storage and the original adapter are not
modified. Device re-acceptance must verify newly created and older playlists
can be opened in both the physical folder view and root view.


### 2026-11-17 — structural porting follow-up and native multi-add feedback

- Playlist creation: `NativePlaylistCreateBinding` now traverses the verified
  callback/presenter/preference/selected-source **typed object graph**, using
  unique field-of-type correlation instead of R8 field names. The original
  GMMP writer, preference-root identity check and scoped getter substitution
  remain mandatory. An ambiguous create-only selected-source list **cancels**
  that special action rather than allowing an unintended playlist population.
  Callback/owner **class** mappings remain tested-version evidence, and an
  entirely renamed future APK must still pass passive mapping and device
  acceptance before any state-changing hook is enabled.
- Next-AudioSource recovery: `NativeNextSourceBoundary` accepts only
  unique static service and native setter method shapes; tested names are
  optional shape-validated fast paths. The bounded passive observation must
  see the *natural* refresh invoke its native source setter before GoneSmart
  invokes a verified post-append refresh. This replaces a version-string-only
  activation gate, but does not imply that a new untested GMMP player-owner
  class is discovered automatically. Unknown or ambiguous shapes fail closed.
- Normal Auto-DJ single-flight: joining workers now have a bounded
  timeout. Timed-out joins never start a second native refill and never
  unlock the original write gate; ownership remains with the original
  writer until its `finally`.
- New main Playlists pages are temporarily hidden only after their native
  adapter class/shape is verified. Unknown adapters stay visible; the
  retained-overlay and timed native-visibility restoration remain intact.
- Multi-playlist add: exactly one combined toast is emitted after the native
  result callbacks complete. Native individual toasts are tagged **by
  object identity at `Toast.makeText()`**, matched to the installed GMMP
  locale's exact `add_to_playlist_toast` result, and suppressed at
  `Toast.show()` even when GMMP shows them asynchronously. The scope is
  bounded, limited to an active multiple-destination operation, and must
  never swallow the combined summary or normal single-playlist adds.

**Acceptance still pending on device for these latest changes.** One bundled
GMMP 4.2.1 check should cover one/multiple tracks to two/three playlists,
including partial success and create-only, normal single-playlist addition,
folder create/open/back, and Track Mix initial/native fallback after the
passive source-boundary change. Future GMMP versions require the original
read-only self-test plus new native source/writer postconditions; no blanket
support promise is made.


### 2026-11-17 on-device multi-add confirmation correction (1e1db42 runtime log)

The 17:18 device trace for revision `1e1db42b47e8` confirms a **newly
observed 4.2.1 mapping**: the old picker constructor
`io3(ho3,boolean)` is absent; `io3(java.util.List)` exists instead, and the
expected legacy `jd(mode=4)` completion constructor was not resolved. The
read-only folder/picker overlay nevertheless dispatched two native playlist
row clicks successfully. GoneSmart logged `accepted=2/2 | sourceCount=0`,
then the host showed *two separate native toasts*. Thus the old batch guard
was never created; this is not evidence of a working per-toast suppression.

The correction makes explicit multi-destination confirmation independent of
R8 picker/finish callback ownership. During an active GoneSmart multi-add,
`Toast.makeText` identifies only GMMP's exact localized
`add_to_playlist_toast` string. If the source list is inaccessible, a
bounded, uniqueness-checked, **read-only** format/count match derives the
source count from the actual native result. Each uniquely identified Toast
object is suppressed at `show()` and provides one native-success receipt.
Only after the confirmed count reaches the number of accepted native row
dispatches does GoneSmart emit one combined summary. A short bounded timeout
reports partial/unknown outcomes without treating a dispatched click as a
confirmed playlist write. This must not consume ordinary single-playlist or
unrelated error toasts. No GMMP write method is guessed or hooked.

**Status:** code/tests and CI pending at this historical entry; device
re-acceptance required with one, two and several selected tracks and multiple
destinations, including delayed/partial callbacks. Preserve the 4.2.1
observed constructor mismatch in the next-version read-only self-test.

### 2026-11-17 — multi-playlist native close guard / Playlist toolbar regression

An on-device `ea5d774` log at 18:05:10 showed that the historical `jd(mode=4)` constructor ABI no longer resolves, so the old single `runCatching` block skipped **both** callback correlation and the independent `f2.b(Object)` native `j83` close-event guard. Two/three playlist destinations still wrote successfully, and all native Toasts were correctly suppressed and aggregated, but GMMP could receive multiple dialog-close events and navigate past the intended Playlist screen, leaving its native toolbar/page chrome inconsistent.

The close-event hook is now installed **first and independently** from the optional obsolete callback constructor. Within a short, user-initiated, multi-destination dispatch window the first exact `j83` passes, subsequent native closes are suppressed up to the accepted destination count; single additions and unrelated native event classes remain untouched. The bounded active batch outlives picker detachment/Toast completion but is cleared after five seconds. The native Toast still provides the independent localized success count; the gate does not manufacture a successful add.

The same log also showed the folder overlay trying to inspect an unrelated `trackListRecyclerView` with 7,426 tracks, yielding `models=0` and `attach retries exhausted`. A nearby picker FAB is no longer enough to classify the list as a playlist surface: a real `playlistListRecyclerView` identity or the verified native playlist adapter is required, with an explicit rejection of track lists. This eliminates the unnecessary repeated main-thread model traversal and potential cross-screen folder UI side effects.

**Validation:** New pure regression tests cover the surface gate and bounded close-event policy. Native GMMP 4.2.1 device re-acceptance must confirm two/three destinations, exactly one combined Toast, correct return to native Playlists toolbar, then Back/forward navigation; one-playlist add and folder creation/opening must remain unchanged. Exact-head CI is necessary but not a substitute for that device check. **No new GMMP-facing strings/translations required.** Existing `[Playlists]` terminal event, Toast/logcat categories and companion Logcat filter remain unchanged.


### 2026-11-17 — Smart-Playlist first-frame verification follow-up

The 18:44:59 GMMP 4.2.1 log on `9cf2f6e` confirms runtime Smart-folder binding after the first Smart-tab entry, followed by `SMART FOLDERS READY` and `SMART FOLDERS RENDER` (85 native Smart rows and two folder rows). Re-entry also renders. A brief native/unfoldered view was reported once, but the log does not prove the exact visible frame that caused it. The first runtime binding took several hundred milliseconds; before structural binding is verified the native list must remain fail-open rather than be hidden solely on a resource-name guess.

Source review found an independent same-count release hole: `projectionReady` accepted an empty `visiblePaths` list due to vacuous `all(...)`, even when `adapterCount > 0`. That can reveal an old native list before any row confirms the intended projection. Require at least one verified bound native row for non-empty snapshots; keep empty snapshots valid, all observed paths constrained to the expected set, the frame-bounded wait and its native fail-open timeout. An existing timeout now logs expected/actual row counts and observed model count only when it fires. No additional per-frame scan, new translated GMMP text, Logs category or persistent polling is introduced. Exact-head CI and a Smart-tab-first-entry/re-entry device pass are required; this patch is not proof that every first-frame flash is eliminated.

The same log also shows `f2.b(Object)` is absent in GMMP 4.2.1, so the optional multi-add native close-event guard from `9cf2f6e` did not install. Native individual Toast identity suppression and the combined two-playlist Toast worked independently. A future navigation guard repair requires passive identification of the actual 4.2.1 native event owner/callback before any event suppression; this is not guessed as part of the Smart-list fix.


### 2026-11-23 — stale Smart-folder fallback timer during rapid navigation

A device recording on `87336e3` confirms the 4.2.1 Smart view initializes correctly with 85 native Smart rows and two physical folders, but a transient native/unfoldered flash remained under fast folder navigation. The exact sequence at 13:21:05–07 was: a fresh Smart browser attached (13:21:05.371), the root projected (13:21:05.516), child-folder navigation occurred (13:21:06.597), the user returned to root (13:21:07.638), and the *original attach-time* 2.5-second fail-open fired at 13:21:07.898, only 47 ms after the new root render. The warning recorded `expected=85 | actual=85 | visibleModels=16`, while the projection was not yet identity-verified. This is a concrete stale-timeout race, not evidence that native GMMP returned an incorrect row count.

The fallback is now scoped to the **latest refresh generation**: each refresh cancels the previous browser timeout and arms one new 2.5-second timeout only if the native content is not already ready. Successful reveal and browser detach cancel it. A late callback also verifies the current generation, live browser, attachment and readiness; if the native projection has become valid it reveals normally rather than forcing a mismatch. A pure regression policy/test covers the stale root → child → root case and completed/detached browsers. The existing bounded fail-open is preserved for a genuinely unresolved current projection, as is the frame-bounded native differ wait; no polling or extra UI traversal is introduced on success. No new GMMP-facing text/translations or companion Logs event is required; the failure-only diagnostic now identifies itself as `SMART FOLDERS PROJECTION TIMEOUT` under the existing tag.

The same November 23 recording confirms 3 tracks → 4 playlists and 1 track → 2 playlists with verified native result counts and one aggregated localized Toast in each case. Device reports confirm the other tested normal/Track Auto-DJ workflows; individual native `next audio source is null` informational lines alone do not prove an audible stall. Playlist/folder and Bluetooth-profile test 6 remains pending. Exact-head CI and a new rapid Smart-folder navigation device check are still required before declaring this UI race fixed on device.


### 2026-11-23 16:52 — native Playlist/Smart flashes and Move safety audit

Log capture from exact head `0d4dc9c` covers 16:48:11–16:52:11. Six `FOLDER EARLY HIDE | overlay unavailable, native restored` messages show the normal Playlists page lost its bounded prehide before the front overlay was established, often while Smart was in front. Re-arm native masking upon a verified foreground playlist-RecyclerView observation (still only with a verified playlist adapter), not only at early `setAdapter`. For Smart, the first entry to 4.2.1 resolves its native `is4` adapter/`ts4` model asynchronously; allow a **bounded** native-first-frame prehide only after independently checking the exact Smart resource, RecyclerView adapter superclass, differ submit shape and foreground state; restore after 2.5 seconds or unresolved binding. Never hide arbitrary/unmapped RecyclerViews or extend this into periodic scans.

Normal-Playlist Move reaches the confirm FAB but fails safely before mutation: `NativeGmmpPlaylistDeleteBinding.resolve(py0)` reports `GMMP playlist delete method unavailable`. The 4.2.0 native `py0.b(Context,List)` cannot be guessed on 4.2.1. Retain this guard and add a one-time bounded method-shape diagnostic of the existing owner to help passively identify a 4.2.1 delete action. **Normal Move remains unavailable until an original native delete/scanner owner is independently proved; do not bypass via file deletion or an arbitrary Room writer.**

Smart native context Move was injected by `onMenuInflated` but the historical `nt4.c` dispatcher does not exist on 4.2.1, leaving a dead action. Bind it only when the exact native row's verified `rvContextMenu` press proves its holder/model/path and the same row remains bound; attach a scoped MenuItem listener without consuming the native click. Otherwise do not advertise an unbound Move item. Smart Move's existing inbound native Smart-link guard still fails closed if the 4.2.1 rule graph cannot be resolved, and must be proven before moving any .spl files. This source-level route and guard do not constitute an on-device acceptance.

The Move chrome used `!mainColorAccent` yielding `#FF8E0E00` on the recorded skin, whereas the source already marks static `colorAccent` as stale red. Use live, verified native playlist FAB color first, verified native selection next, and the pre-existing native ActionMode bar last. Observe palette events for change timing only; never apply the raw unverified Aesthetic accent to the bar or confirm button. Keep native fallback when no independent color evidence exists. No translations, new companion Logs categories, new network calls or unbounded polling.


### 2026-11-24 — device report: Move failures and first-frame native lists

The 12:14:13–12:16:51 captured log on exact test revision `07d9d0f` confirms intermittent native-list flashes, with `FOLDER EARLY HIDE | overlay unavailable, native restored` at 12:16:01 and 12:16:09, while the Smart page was already the foreground. The normal page's 2.5-second mask lease was expiring *offscreen*. New policy defers this fail-open until a genuine foreground pre-draw; that foreground starts a new bounded 2.5-second native restoration, without periodic timers or permanently suppressing native failure fallbacks. For Smart, the proven `smartListRecyclerView` + RecyclerView-adapter + AsyncListDiffer shape is now observed **before** the original `setAdapter` call, not only afterward, masking its raw first frame while native structural ts4/is4 binding settles. Unknown adapters and other RecyclerViews remain untouched. Both paths still need direct device confirmation.

Both Move modes logged wrong `#ff8e0e00` styling. The old implementation recursively sampled its own new synthetic AestheticFab/ActionMode as a false native color. Capture the already-proven host selection accent **before** creating a Move ActionMode and retain that color as the initial source; react only to the independently observed Aesthetic `colorPrimary` stream when it reports a usable non-placeholder color. Do not resample the new Move FAB or its own bar as independent evidence.

The Smart move opened, but `leafRuleClass/groupRuleClass` from the 4.2.0 map are null in the 4.2.1 runtime structural `ts4` binding. The old blocker incorrectly described *mapping unavailable* as a *verified inbound Smart link*. The native model reader and a uniquely identified `List` rule field **are** available, so run a bounded, read-only recursive traversal of the actual parsed native rule graph with identity-cycle detection, upper bounds and fail-closed unreadable/unknown objects. Block only genuinely detected native-linked Smart paths as `REFERENCED`; keep `UNKNOWN` blocked and distinct. No arbitrary .spl edits or guessed GMMP writers. Verify on device with a non-linked test Smart Playlist, then a real inbound-linked one.

Normal M3U Move still cannot resolve the historical `py0.b(Context,List)` delete owner. The new bounded diagnostic shows this runtime `py0` has only `a(pn3)`, `b(ws5)` and other incompatible signatures, not a Context/List writer. Do not invoke any guessed replacement, direct SQL, or unrecoverable file move. This operation remains safely disabled until the installed 4.2.1 APK or a passively proven native deletion/scanner callback supplies an independently verified mapping. The present source/CI fixes cannot certify it.

### 2026-11-24 — verified GMMP 4.2.1 dynamic accent root cause

The same supplied APK resolves the styling failure exactly: `oy0` on this build has no static `(a,String,de3)->de3` Aesthetic resolver, so GoneSmart's prior `NativeGmmpAccent.observe` silently subscribed to the static `a.b(colorAccent)` fallback and received the stale red `#ff8e0e00`. The native `com.afollestad.aesthetic.views.AestheticFab.onAttachedToWindow` invokes `qy0.i(com.afollestad.aesthetic.a,String,de3)` to resolve its own color value, then subscribes to the returned observable. The verified dynamic source is `!mainColorAccent`. The new shared bridge resolves **only the exact native qy0.i signature** and the standalone Move UI calls its strict method that fails closed on missing dynamic resolution rather than promoting static red as a verified source. The first-frame color is retained from pre-Move selection evidence (prefer any previously observed verified dynamic color), and every subsequent live native emission updates both the Move FAB and contextual bar. This is native-first, event-driven, and does not scan view trees on every color update. Device confirmation is still required for tint parity and dynamic color changes.


### 2026-11-24 15:27–15:32 — follow-up log `GoneSmart-last-5min-20261124-153231-802.zip`

The log is exact build `b968f713bebc` and shows confirmed orange native Move color `#fff06028` in both tabs; preserve `NativeGmmpAccent` and `PlaylistFolderMoveChrome`. Normal Move at 15:31:49 still resolves `py0` and blocks before work: the actual `ao3` native adapter explicitly logged `FOLDER MODEL SOURCE READY | model=yn3 | completeRows=263` at 15:31:09, whereas `NativeGmmpPlaylistDeleteBinding` incorrectly gated its APK-proven `ry0.f/ry0.a` worker on `xn3`, an unrelated `PlayingInfoUpdater`. Fix the fast path with the APK-verified `yn3` ID/path/title shape; do not call `py0` for verified 4.2.1 Playlist rows. The durable stage, native worker, index verification and rollback are unchanged.

Smart Move UI and dynamic color succeeded, but at 15:32:21 the read-only native rule traversal returned `UNKNOWN`. Re-reading the supplied APK proves `ts4.r(File)` calls `dw3.f(NodeList)`, whose native XML Rules parser constructs `ct4` (leaf: int,int,String,int,long) and `gt4` (group: List,boolean,long,boolean); both extend `dt4`. The generic reflective scanner wrongly continued into Android's `java.lang.Object.shadow$_klass_` field and rejected the unrelated `java.lang.Class` object as an unknown rule. Stop the class-hierarchy walk before Object (keep all native rule fields and fail-closed behavior), with APK-shaped nested leaf/group regression tests.

Smart first-frame masking at 15:31:12/19/32 and 15:32:13 ran BEFORE setAdapter even while the new fragment was not attached/visible. This unconditional alpha=0 is a credible cause of the maintainer's reported intermittently invisible Smart list, even though the log eventually reported `SMART FOLDERS RENDER | smart=86`. Require independently attached+visible+foreground evidence before this pre-setAdapter mask. The existing native onAttached observer still handles eligible surfaces before first draw. For the unchanged physical root (root directory, not virtual, groupRoot=false), allow an adapter-count-matched projection to reveal even before native holders appear; keep strict visible-holder path verification for nested/virtual/regrouped projections. This prioritizes actual playlist visibility while preserving stale-row protection for folder navigation.

There were no `FOLDER EARLY HIDE` or `SMART FOLDERS PROJECTION TIMEOUT` markers in this snapshot. Thus its reports of residual native-list flashes are a real device observation but **cannot yet be assigned to a specific remaining lifecycle edge** from these logs alone. Do not claim the above safety hardening alone has completely eliminated them. Maintain bounded fallback and do not change the native font/theme code or Auto-DJ.


### 2026-11-24 16:35–16:39 — alpha=0 Smart regression and first-frame tab flashes

The exact-head `d492b0e` log `GoneSmart-last-5min-20261124-163847-467.zip` proves both normal Playlist M3U moves succeeded with native `ry0` deletion/index verification (`PLAYLIST MOVE | result=true` twice) and that Smart .spl Move completed after `SMART MOVE LINKS VERIFIED | files=89 | nativeReader=r`, followed by folder-row counts root 86→85 and target 1→2. Maintain those native action paths and the accepted dynamic color, with no additional mutation, reflection or hot-path work.

It also conclusively identifies a real UI regression: the first Smart root refresh/reveal at 16:36:32 had `originalAlpha=1.0` and 86 projected/86 native/15 visible holders. After rapidly switching normal/Smart tabs at 16:36:46 onward, multiple `SMART FOLDERS REVEAL` messages report `originalAlpha=0.0`, despite a fully prepared native model list. The controller had captured *its own previously applied alpha=0 pre-draw mask* as if it were the native source's original alpha; later reveals faithfully restored invisible rows. NativeFolderAlphaLeasePolicy now retains only positive independently observed original alpha through a weak per-view cache, promotes any accidentally captured zero to the last positive native value or Android View's default alpha=1f, and uses that positive lease on pre-mask, attach, restore, reveal and cleanup. A zero must never enter a new Browser's `originalAlpha`.

The fastest first-frame interception is BEFORE `RecyclerView.setAdapter` on the verified native `smartListRecyclerView` shape, including when FragmentManager prepared the view but has not yet attached it; the previous head's unconditional nonattached-surface rejection could leave an early native frame visible. The new early gate requires a native RecyclerView adapter, AsyncListDiffer shape, named Smart list with a parent and a foreground-or-pre-attach lifecycle; no arbitrary other list/adapter is touched. Every pre-hide has one bounded 2.5s native fail-open: when attached to the accepted browser it transfers the positive alpha lease; otherwise it restores native rows, including offscreen/detached, and does not leave a permanent observer-mask. The fully prepared, independently verified native Smart projection remains the only successful reveal condition.

The same positive-alpha protection is applied to normal Playlists' pre-attach masks and browser cleanup to avoid re-entrant zero adoption there. A flash at the beginning of a newly constructed GMMP tab must still be tested on-device; the log proves the missing alpha but cannot itself prove that every native first-frame flash has been eliminated. Existing one-shot `SMART FOLDERS REVEAL` and `FOLDER EARLY HIDE` diagnostics are retained; no polling was added. Native Move and dynamic color are untouched.


### 2026-11-24 17:28–17:30 — native first-frame tab flashes after stable Smart alpha

The user-confirmed `01d1bad` build no longer loses the Smart list: `GoneSmart-last-5min-20261124-173010-318.zip` captures a Smart native projection with `originalAlpha=1.0` at 17:29:19, 17:29:25, 17:29:33, and 17:29:44, with 85–86 native rows or the exact two child-folder rows. The residual bug is a brief GMMP native view during switching *between* main Playlists and Smart Playlists, not missing models or a failed differ.

A **new, narrower lifecycle gap** is visible: the main Playlist browser performs `FOLDER INLINE CLEANUP` at 17:29:18.508 and 17:29:25.170, while the next `FOLDER EARLY HIDE` arrives 9–8 ms later, with `attached=false`; its on-detach code previously restored the original native alpha=1 immediately when hiding/removing the GoneSmart sibling overlay. The exiting fragment can thus expose the raw native list during tab transition. Preserve the positive original alpha lease and leave the native RecyclerView masked on main-tab **detach**, as was previously done only for the add-to-Playlist picker; normal attach and bounded fail-open still restore the original when no folder overlay is usable. Smart detach had the same unconditional original-alpha restore and is now given a positive-alpha detach mask with a distinct bounded 2.5s fail-open for abandoned views. No different unrelated surface is masked, and explicit feature disable/removal restores native UI.

The first Smart tab at 17:29:18 does **not** emit an early mask before the runtime binding becomes ready at 17:29:19, whereas the second tab creation at 17:29:22 emits `SMART FOLDERS EARLY MASK | before setAdapter | attached=false`. The pre-attach adapter check previously required the RecyclerView to already have a parent, despite checking a verified GMMP Smart resource ID, native RecyclerView adapter superclass and AsyncListDiffer layout. That parent prerequisite is removed for the not-yet-attached case; the three independent identity witnesses remain mandatory. This closes the first construction gap without bypassing model/adapter safety or the independent native fail-open.

Both established Move operations and the GMMP native dynamic palette remain unchanged. Existing host CI and device tests cannot prove absence of a one-frame visual artifact; require repeated fast Playlists↔Smart tab switches in the next on-device pass, plus checking actual Smart row visibility and navigation into/out of child folders. No polling or busy-loop is added.


### 2026-11-24 18:08–18:13 — first native tab frame still flashes after detach masks

The user reports the original GMMP tab still appears once at EVERY entry in both tabs on exact build `994982f`. The uploaded `GoneSmart-last-5min-20261124-181314-652.zip` verifies that both tab projections and Smart alpha remain stable: normal `FOLDER INLINE READY` at 18:12:45.718, 18:12:51.891, 18:12:56.688 and 18:13:01.780 (263 playlist models), and Smart `SMART FOLDERS REVEAL` at 18:12:48.928, 18:12:54.208, 18:12:58.938 (85 physical Smart models, 15 verified bound holders, `originalAlpha=1.0`). The prior detach mask fix is firing (`FOLDER DETACH MASK`, `SMART FOLDERS DETACH MASK`), so extending its timeout or repeating RecyclerView alpha changes is not a sound fix. The first Smart creation at 18:12:48.128/18:12:48.141 lacked ANY `SMART FOLDERS EARLY MASK` event; after the native Smart fragment was created, runtime bindings were not promoted until 18:12:48.721 and GoneSmart's view was only revealed at 18:12:48.928. Main Playlists show 0.3–0.7-second work after the `FOLDER EARLY HIDE`, with repeated `Choreographer: Skipped 41/57 frames` before native folder overlay readiness. User-visible flashes are not captured graphically by logcat, but these are verified lifecycle gaps.

The uploaded exact GMMP 4.2.1 APK contains `androidx.fragment.app.f.performCreateView(LayoutInflater,ViewGroup,Bundle):View`; native main Playlist fragment `op3` and native Smart fragment `ls4` are verified independently against their actual logged fragment-creation tags. Their shared AndroidX fragment `performCreateView` boundary runs before FragmentManager attaches the returned root to the window. The NEW one-time lifecycle hook resolves this method structurally from ls4's native superclass hierarchy and verifies its return type is View. On completion, it only handles runtime type-matched native op3/ls4 fragments. From their returned root it locates the matching `playlistListRecyclerView` or `smartListRecyclerView` by actual host Resources and requires the original host `androidx.recyclerview.widget.RecyclerView` superclass. This is independent of whether setAdapter or onAttachedToWindow have run yet. It grants exactly the same positive original-alpha native mask lease with a single 2.5s bounded native restoration on unhandled/failed attach, transferring the existing lease to the browser when a real native projection is committed. The old RecyclerView checks remain as runtime fallback if the new fragment method is unavailable. Crucially, there is no per-frame method hook or reflective scanning of arbitrarily many native fragments, just two exact fragment types at initial view creation.

Check the next device log for `FOLDER FRAGMENT MASK | before native first attach` and `SMART FOLDERS EARLY MASK | Fragment.performCreateView` BEFORE the first `FOLDER INLINE READY`/`SMART FOLDERS REVEAL`, plus verify no missing rows, native UI fallback after attach failure, and fast Playlist↔Smart transitions. If those early events fire and the native visual still flashes, suspect an independently drawn parent-level view rather than the proven RecyclerView row surface; do not add further blind alpha masks. Both accepted Moves, color and Smart projection remain untouched.


### 2026-11-25 17:10–17:14 — exact host APK proves Fragment early hook never installed

The maintainer installed commit `b9b9aea42167` and reports the raw GMMP Playlist and Smart views still flash at every switch. In the attached `GoneSmart-last-5min-20261125-171434-544.zip`, capture-info confirms exact app SHA prefix `b9b9aea42167`; `gmmp-gonesmart-logcat.txt` records `FOLDER FRAGMENT MASK | install unavailable; RecyclerView fallback retained` at 17:13:56.319 and a concrete `IllegalArgumentException: GMMP native Fragment.performCreateView return type unavailable`. There are ZERO `SMART FOLDERS EARLY MASK` messages in this session, although 3 normal native adapter `FOLDER EARLY HIDE` events are logged. Three normal-folder readiness events and three Smart reveal events at 17:14:03 through 17:14:22 all complete normally, Smart native model count=85 and positive originalAlpha=1.0. These logs prove that the **new fragment early hook never installed**, not that a functioning hook was too late. No more speculative alpha timers are justified.

Re-examining the actual attached GMMP 4.2.1 `base.apk` DEX method/field tables confirms that `androidx.fragment.app.f.performCreateView(LayoutInflater,ViewGroup,Bundle)` returns **void** (`V`), while the same Fragment class exposes `getView():android.view.View` and field `mView:android.view.View`. The former code incorrectly demanded `performCreateView` return `View`, so it always threw at hook installation. This fix validates both actual signatures, preserves the original void result of `chain.proceed()`, then obtains the created native root via cached `getView()` reflection on the exact Fragment owner after the original implementation. The already-existing runtime checks restrict the result to host fragments `op3` (normal Playlist) or `ls4` (Smart Playlist), and the already-existing `NativePlaylistFragmentList.find` verifies resource identity and native RecyclerView superclass before a bounded first-frame alpha mask; other native fragments remain untouched. One-shot positive diagnostics distinguish installed-but-missing-root, installed-but-missing-native-list, and successfully found tab. Tests now lock the exact `void` creation / `View` getter contract. Both previous functioning Move paths, native colors, Smart projection and alpha=1 preservation are unchanged. New device evidence is required to confirm visual first-frame correctness; only now is the hook actually eligible to install.


### 2026-11-26 16:50 — Smart-only native tab entry flash despite active fragment masking

The device log `GoneSmart-last-5min-20261126-165055-076.zip` is an exact-head `6ca24293fe14` recording; only the last ~32 seconds survive Android's buffer, but it captures 4 repeated Smart-tab entries. The previously broken Fragment.performCreateView hook IS NOW ACTIVE: at 16:50:25.586, 29.990, 34.235 and 38.605 it logs `SMART FOLDERS EARLY MASK | Fragment.performCreateView | attached=false | retainedAlpha=1.0`, immediately followed by `FOLDER FRAGMENT MASK | verified smart surface ... alpha=0.0`. The nested native ls4 list consistently reaches `SMART FOLDERS REVEAL` with 85 rows, 15 bound holders and `originalAlpha=1.0`; the user confirms no disappearing rows and normal Playlist tab no longer flashes. The remaining visible native first frame is thus *not evidence* the RecyclerView early hook is missing, not a model/drop issue and not a native ListAdapter count issue.

There is a separate native parent Fragment `os4`, logged as created before nested `ls4` at 16:50:25.533 vs 16:50:25.573. Only the nested ls4 list was masked in 6ca2429, while its surrounding os4 page stayed visible throughout native tab entry; the user reports a flash only in Smart. The new fix adds a **separate, exact-type-matched os4 page-level alpha mask** at the same once-per-fragment `Fragment.performCreateView` lifecycle boundary, before the page can attach/draw. It does not change the inner list, its adapter or any native model. The os4 page's original positive alpha is captured in a weak lease, guarded against transient native enter animation overwrites using a short-lived O(1) OnPreDraw listener (no repeated reflection/view-tree walking), and restored atomically with the independently verified nested ls4 current-folder projection in `revealInitialContent`. The Smart foreground detector explicitly recognizes an os4 root hidden by **our own** pending page lease so its nested projection can still finish; unowned alpha=0 fragments remain excluded. A separate bounded 2500-ms native fail-open and immediate option-disable cleanup protect against blank UI on unsupported/failed binding. Only the GMMP 4.2.1 os4 fragment receives this mask. User verification remains necessary to confirm this accounts for the remaining visible native flash; if os4 does not contain the nested ls4 view or a separately animated sibling is responsible, the new diagnostic distinguishes missing matching ancestor and native fallback.

Do not alter functioning normal Playlist lifecycle, Smart list model submission, Move, native theme/palette or Auto-DJ. Include exact-head build and test results. The new UI protection uses existing Android view interfaces and lifetime-bounded callbacks; no new localized text required.


### 2026-11-26 — shared first-frame opacity for Playlist and Smart screens

The maintainer requested one implementation of **early native-frame masking, original opacity preservation and safe restoration**, without merging GMMP's distinct native Playlist and Smart-Playlist models. The verified `op3` and `ls4` Fragment.performCreateView hooks remain the only authority to intercept each native RecyclerView before its first frame; the native `os4` outer Smart page is a third verified, nested surface. The new `NativeFolderFirstFrameGate` is shared by both controllers across the three scopes `PLAYLIST_LIST`, `SMART_LIST` and `SMART_PAGE`, with weak View references, the previously tested positive-alpha recovery policy, an independent 2500ms native fail-open per lease, idempotent mask acquisition, generation-safe cancellation and optional native-animation alpha enforcement only for the outer Smart page.

Browser adoption cancels the first-frame timeout **without** restoring raw native rows. The normal Playlist controller retains its picker and foreground exceptions, while the Smart controller retains the native AsyncListDiffer, native-holder identity verification and atomic outer-page reveal. Those distinct readiness tests are intentionally *not* unified. The old separate fragment timers and duplicated weak original-alpha caches are replaced by the shared mechanism, and Smart's observed-root/detach fallback also uses the shared lease. The existing normal Playlist adapter-only fallback remains separate because its offscreen/foreground picker handling has different, device-accepted semantics. Scope and generation predicates have regression tests; disabling either feature restores any early native view still awaiting its controller.

No native Playlist Move, Smart-rule, native theme, playback, localization, or periodic reflection logic changed. A successful build cannot prove the remaining `os4` first-frame flash visually resolved; acceptance requires rapid Playlist↔Smart switches, first opening, Smart folder/detail navigation, full native rows and bounded fallbacks.


### 2026-11-26 17:35–17:41 — native-like, all-at-once Playlist/Smart first frames

The device log `GoneSmart-last-5min-20261126-174055-865.zip` is exact commit `0755ed59f38d`. The maintainer confirms native GMMP tab flashes have stopped, but the synthetic folder views still visibly populate and shift in stages when entering either tab and when navigating into/out of directories. The log confirms repeated clean Smart outer-page mask/reveal at 17:40:02/06/10/14 (85 native rows, 15 correctly bound holders, positive originalAlpha=1.0, no native projection timeout). In contrast, normal Playlists attaches 263 native models and seven top-level folders, with an approximately 400–550ms interval between `FOLDER FRAGMENT MASK` and `FOLDER INLINE READY` on repeated tab entries. Smart transitions from root to a child and back take approximately 100–250ms, including background XML-model loading and two unconditional posted scroll frames. These observations indicate visible staged work, not evidence for a missing native fragment hook.

The Smart directory navigation path previously called `prepareFolderScrollForNavigation`, setting `nativeContentReady=false`, `projectionPrepared=false`, `list.alpha=0` and resetting folder scroll *immediately on tap*, before the worker could scan/parse the destination directory. Its existing overlay was therefore dismissed for the whole asynchronous IO interval. Now the last verified Smart projection remains painted while the destination snapshot is prepared on the worker; only after a new complete snapshot passes the existing generation+directory checks does the main-thread commit mask the old native rows/header, submit the replacement via the unchanged GMMP differ, update the folder header, and wait for independently verified new bound holders. A guarded policy applies the same transactional mask if a native list changed without folder navigation. The previous two unconditional `postOnAnimation` scroll-reset waits are reduced to one native request + one layout frame; the existing model identity check still owns the actual reveal. The foreground pre-draw listener is explicitly prevented from exposing content before that scroll reset. The existing 2500ms native fallback remains.

The Smart model parsing path now uses `NativeSmartModelCache`: a bounded (256 native model objects), worker-only, short-lived (15 seconds) in-memory cache keyed by native .spl file path, file size, file mtime and independently verified native model class. Every access rechecks file metadata and re-runs the original GMMP XML model constructor/parser on mismatch or expiry. GMMP's original native sort, AsyncListDiffer adapter, model-identity verification and file observer remain the source of truth. The cache is never persisted and adds no foreground file IO or native adapter changes. Host tests cover unchanged-file reuse, file mutation, model-type mismatch, removal and expiry. If other native metadata can change without changing the underlying .spl file or affecting its size/mtime, that is an unverified cache-coherency edge and should be explicitly retested with Smart rule edits in the device pass.

The normal Playlist folder overlay previously attached as `VISIBLE`, invoked `positionOverlay`, and only THEN synchronously built all synthetic rows and breadcrumb. It now stays `INVISIBLE` until `safeRender(browser)` completes, and is positioned/revealed only after successful full row construction on the current browser. To narrow the remaining normal-Playlist first-entry delay, DEBUG logs `FOLDER INLINE FIRST FRAME PERF` with durations for native inspector, index construction, native overlay construction, and row rendering; this is a diagnostic on actual build events only and does not add periodic scans or per-frame reflection. The normal Playlist index/bound model readers and native picker/move behavior are otherwise unchanged.

This is a practical reduction in staged visual work, **not** a promise of byte-for-byte native GMMP transitions or zero UI latency. A device pass is still required to confirm smooth first opening, rapid tabs, Smart root→child→root navigation and native rule edits (including external file changes), and to verify that the existing successful no-flash and Smart-holder/alpha behaviour survives.

### 2026-11-28 — residual QuickNav first-frame alignment

The exact-head device export `GoneSmart-last-5min-20261128-005849-983.zip` confirms `0a9dd9acb9ba` and retains only 00:57:40.808–00:58:49.862 of the requested five minutes. The maintainer reports substantially more native-like transitions, with occasional residual flashes, particularly the upper normal Playlist bar. All 16 Smart reveals match expected native row counts (85 or 1), retain originalAlpha=1.0 and report fallback=false, without a projection timeout. Preserve this working first-frame/outer-page ownership.

Normal Playlist first attach logs 424ms (10ms native inspection, 144ms index, 36ms layout construction, 234ms render, 263 native models), followed by 49 skipped Android frames. The first directory navigation at 00:58:04.522 is followed at 00:58:04.541 by `FOLDER QUICKNAV INSET | nativeX=24 | measuredX=0 | paddingStart=24 | source=verified/fallback`. This proves a delayed alignment change, not by itself the exact visual flash or a live Files-tab qg1 measurement. The native toolbar is a distinct surface; the export records menu capture before folder readiness, but no later native Add visibility change. Do not mask or replace the toolbar speculatively.

Both folder headers now share the same measured, generation-scoped pre-draw alignment via PlaylistFolderUiKit. The native XML holder, existing sampled/cached native start and existing bounded correction remain authoritative. Only when a real padding correction requests layout is one obsolete draw deferred; no synthetic animation, fixed pixel offset, new host hook, polling or opacity change is introduced. Missing geometry never blocks drawing, retry work is bounded to actual pre-draw events, a scrolled header is not calibrated, and detachment or a superseding navigation cancels the callback. At most one correction is applied per alignment; subsequent measurement is read-only. Native Playlist/Smart model, row/differ, picker, Move, menu, theme, and playback ownership remain unchanged. Tests cover the one-frame deferral, already-aligned and unavailable geometry, and retry exhaustion.

Audit: no new translations required. Existing companion Logs categories and Logcat filters remain unchanged; the existing QuickNav diagnostic is retained and no routine per-frame log is added. No temporary workflows or probes. The 144ms index and 234ms synthetic row render remain measured first-entry costs; this targeted change does not claim to eliminate them or every flash. Exact-head normal Build and an on-device comparison with the native Files-tab QuickNav, cold/warm Playlist entry and root/child/back navigation remain necessary. If the actual app toolbar (rather than the folder breadcrumb) still flashes, request a short recording and matching log before altering native menu lifecycle.

### 2026-11-28 — retire whole-page opacity and hidden QuickNav animations

Exact-head `b98d44fb9d69` export `GoneSmart-last-5min-20261128-010929-175.zip` covers 01:04:43.744–01:09:29.052; the actual restarted GMMP process begins at 01:08:28. The maintainer reports the normal breadcrumb briefly showing the previous folder on root → different folder and the initial Smart page appearing black including its header. On first Smart entry the log records `SMART PAGE MASK` at 01:08:47.549 and `SMART PAGE REVEAL` at 01:08:48.278 (729ms), followed by verified 85/85 native rows and 15 holders. That is a concrete self-imposed whole-page blackout, not a missing native list or an incorrect model. Warm entries are much faster (130ms/132ms). The earlier whole-page mask solved one visual flash but violates the more important native chrome/transition ownership requirement. Retire the os4 page-alpha hook, pre-draw native-alpha override, ancestor lease exception, page policy/test and related unused lease code. Keep the independently verified ls4/op3 list-only gates, existing bounded native fallbacks, native model/differ and title verification, and let GMMP animate its own page without GoneSmart changing its alpha. A transient raw native parent/background during entry is preferable to suppressing the entire native header. Cold list projection can still have loading latency; do not describe this as a guarantee of zero visual latency.

Normal breadcrumb source shows root navigation submits animated removals to the adapter and then sets the entire header GONE. RecyclerView need not run the hidden layout before the header is visible for a different folder, leaving old holders/removal animations eligible for the next draw. The report reproduces that exact sequence (Festival at 01:08:38.943, Artists at 01:08:41.055, Handsup Hardstyle Hardcore at 01:08:43.048); the log does not identify individual drawn pixels. Both controllers now share an explicit hidden-root reset: mark the header GONE first, stop the header's own scroll, end its own animator, detach only the GoneSmart-owned adapter via the standard RecyclerView API (discard old children/pending updates), clear its data while detached and reattach the same empty adapter. An already empty hidden header is a no-op. Reopening has no old removal rows to animate; visible nested paths still use the native XML and existing shared-prefix insert/remove animation. Smart root no longer retains a hidden one-item breadcrumb. No fixed durations, new host hooks, arbitrary opacity changes or native adapter mutations.

The first normal cold attach still takes 435ms (9ms inspection, 129ms index, 74ms layout, 223ms render) after the initial 2.5s native fallback; warm attaches take 277/340ms. This remains a separate main-thread performance limitation, not addressed by suppressing more UI. The root reset has pure regression coverage; the retained native list gates keep scope/token tests. Audit: no new translations required; no companion support category or tag/filter change, removed obsolete outer-page diagnostics, existing list fallback diagnostics and QuickNav parity remain. No changes to Move, multi-select, theme, playback, writers or release workflows. Exact-head CI must pass before a test APK is provided. Actual native device rendering remains unverified by host tests; avoid requesting repeated scripted test rounds unless normal use reveals a remaining issue.

### 2026-11-28 — viewport rendering and installed Files QuickNav parity

The maintainer accepts the previous no-stale-header/no-black-page fixes on exact head `07e295ef8d97`. Export `GoneSmart-last-5min-20261128-012616-378.zip` shows three main Playlist first-frame builds of 442/389/406ms (native model inspection 9/3/4ms, index 159/118/165ms, render 237/261/227ms), with 45/47 skipped frames at the latter two. Root contains seven folders and 80 loose playlists out of 263 models. Smart projections are correct (85 or 1 native rows, fallback=false); navigation-to-reveal is approximately 92–212ms. Preserve list-only first-frame gates and the hidden-root reset. Do not restore whole-page masking.

The normal main Playlist folder browser now hosts a real independent vertical AndroidX RecyclerView instead of a ScrollView with every row eagerly inflated. It binds native GMMP XML only for visible/prefetched positions; container holders recycle, old model-specific row views are discarded, current model click/long-click/context forwarding remains the verified native path. Native main selection mapping is identity-checked at attach/recycle and geometry is updated on actual row layout. The accepted picker ScrollView and its FAB/selection-target mapping remain unchanged. Small same-folder insertion diffs use the real RecyclerView animator if safely cloneable from the native playlist surface; no guessed animations when unavailable. Whole folder replacements are not fabricated insertion/removal animations and reset scroll through the RecyclerView. No native GMMP adapter is replaced. Native bind failures restore the native list via a deferred UI callback. The index filesystem/canonicalization work still occurs synchronously; the new render metric excludes deferred holder inflation, so it is not directly comparable to the previous complete-build metric. Device first-frame and selection checks remain necessary.

The new trace also identifies the original Files-tab native `quickNavRecyclerView` adapter as `pg1` on GMMP 4.2.1 (old 4.2.0 `qg1` fast path). The previous exact-name gate silently prevented fresh runtime animation/style sampling. Keep the known fast path and accept other types only after read-only alternating metadata/separator item-type evidence plus actual laid-out first metadata and separator holders on the exact native resource. Cache accepted runtime type. Both breadcrumbs now apply sampled native scroll/animator physics; compare animator durations as well as implementation class. Unknown animator types retain existing safe fallback, never invent native duration values. The log also contains recoverable exceptions from GMMP's original `pg1` SafeGridLayoutManager; the observer is read-only, and the cause cannot be established from this export. Do not modify the native adapter to suppress those exceptions.

Audit: no new translations required; existing Logs categories and filters unchanged; no new routine per-frame logging; native row-bind failure is an existing error family. No temporary workflow or release action. The exact native vertical Files transition implementation is not established by this log, so this change does not claim frame-for-frame animation parity or altered Smart model projection timing. Exact-head normal Build and normal device usage are required to validate the viewport change.

### 2026-11-28 — QuickNav transition visibility and outgoing Smart lifecycle

The latest export `GoneSmart-last-5min-20261128-015921-280.zip` includes two builds; use the new process beginning at 01:57:07 (`86ee8bcc6d09`), not the old `07e295e` process. Main Playlist synchronous first-frame work is now 282/150/168ms with 13/9/9ms submit/render (deferred viewport binding is not included); index remains 212/130/147ms. Native Files `pg1` is independently verified at 01:58:07.886: 58.8px text, 144px height, 24px start, 192px separator, original AndroidX animator `o`. At 01:58:06.859 GMMP logs its installed `me.zhanghai.android.materialprogressbar.IndeterminateHorizontalProgressDrawable$RectTransformX` animation. That proves the actual loading drawable family, not its exact layout, timing or native presenter callback.

The Smart controller was still setting the ENTIRE GoneSmart overlay INVISIBLE during differ projection even after a prior header was ready. Native item animation therefore ran under an invisible ancestor. Keep prepared QuickNav chrome visible independently of native row readiness and hide only the folder viewport plus the verified native list until the existing model/holder/scroll check releases them. The original GMMP outer page/toolbar remains entirely native; no parent alpha guard. Both breadcrumbs now use one generation-scoped layout/pre-draw completion to request AndroidX smooth scrolling to the last path segment without forced intermediate scrollToPosition jumps. Hidden-root adapter reset remains intact, so root entry cannot resurrect old holders. Whole root visibility transitions and exact native presenter animation are not proven; do not invent timing.

Smart shows the original installed indeterminate horizontal drawable, independently constructed (constant-state copy if observed and safe, otherwise verified class with Context constructor), only during real directory scan/projection. A bounded nearest-container read-only source capture on the original Files QuickNav samples height/tint/intrinsic padding when available; before a live sample, the installed drawable's own intrinsic height and current native accent are used. No unrelated view-tree scans, fixed delay, custom spinner, borrowed running drawable or extra header height. Progress is generation-owned, hidden on current reveal, scan/load failure or detach; normal synchronous Playlist navigation deliberately has no fake loading interval. The actual source layout may differ until sampled and a device pass is still necessary.

Smart outgoing view evidence: DETACH MASK at 01:57:36.650 then immediate HOST/READY at 36.666/668, reveal 36.783, DETACH MASK again 36.790; the same sequence repeats at 01:57:58 and 01:58:06. The detach callback runs before the native view has completed detachment. Mark it retired before removal, prevent observation/queued attach/runtime binding from adopting it again, clear retirement only at actual native onAttachedToWindow entry. Keep the verified no-adapter first-frame lease until a real incompatible adapter or bounded fallback; previously a transient null adapter prematurely restored the list. Bounded pre-draw guard applies only to Smart native list leases and the current unrevealed adopted browser, never the os4 parent. All verified model identity and fail-open logic stays. The log cannot locate the exact flashed pixels.

Audit: no new translations required. Existing log tags/categories unchanged; one live progress source observation or unsupported-source diagnostic, no per-frame logging. Tests cover independent chrome readiness. No native writer, Move, selection, playlist viewport or playback changes, no temporary workflow. Exact-head build required; host tests cannot prove visual identity with Files or the absence of a flash.

### 2026-11-28 — shared QuickNav loading and forward additions

The maintainer confirms the native Smart loading indicator works on `178e90ffbccd`, but normal Playlists have no indicator, and both breadcrumbs animate removals on Back but appear immediately on forward entry. Export `GoneSmart-last-5min-20261128-021800-247.zip` actually covers 02:17:44.909–02:18:00.122 (90 lines, 128 KiB Android ring buffers), with native Files queries followed by the companion export. No GoneSmart breadcrumb animation or Playlist navigation trace is retained. Do not infer frame pixels or timing from this export.

Both tabs now share one NativeQuickNavLoading presentation, the same installed native drawable factory, geometry and generation-owned finish/clear. Normal main Playlists start at initial render/folder change and finish after the real replacement RecyclerView layout has had a draw opportunity, not on model submission; Smart retains its existing worker/projection completion authority. No fixed minimum display interval, fake delay or additional directory scanning. Fast normal navigation may show the indicator for only a frame; the picker remains unchanged. Root resets and existing native first-frame list gates remain intact.

The hidden root intentionally resets its adapter, so RecyclerView can skip simple add animations on the first layout; even with an existing strip, an add is only animated if the relevant holder was scheduled by RecyclerView. The shared breadcrumb submit path now distinguishes pure forward appends from removals, replacements and no-op style refreshes. Use an independent subclass of the verified AndroidX DefaultItemAnimator solely to record scheduled add holders and supplement missing visible additions on the first actual layout; standard RecyclerView add callbacks are not replayed. Use the host's sampled durations with the same underlying implementation, no manual alpha/translation/delay, and balance the RecyclerView holder recyclability contract. Unknown custom native animators retain their own path, not forced standard animations. Path revisions guard stale work; detach/root clear and bounded natural pre-draw prevent recurring work. Existing Back behavior remains native. This is a structural explanation and regression fix, not evidence of exact installed Files presenter parity.

Audit: tests cover forward/hidden-root vs back/sibling/unchanged classification. No new text translations, log categories, hooks, playback, native model, picker or move mutation changes. Exact-head normal CI required. Device rendering and duration of the fast normal indicator remain unverified.

### 2026-11-28 — retire forced QuickNav adds; preserve actual native animation ownership

Export `GoneSmart-last-5min-20261128-130139-242.zip` from exact head `118f06a68d8c` covers 12:59:05.226–13:01:39.120 (1807 Logcat lines). Device report: loader no longer perceptible, forward/back motions regress in both tabs, especially Smart Back. All 17 Smart reveals match expected row counts and fallback=false; normal synchronous viewport submission is 12ms (180ms attach including 132ms index). No reason to change the playlist model/differ or native write/selection/move behavior.

Original Files trace independently samples live loader height 6px at 13:01:18.014 and pg1 metadata 58.8px, 144px header, 24px start, 192px separator, host `androidx.recyclerview.widget.o`. At 13:01:27.379 a transient firstTextX=-414 contaminates the in-memory reference even though persistence rejects it; normal and Smart must reject invalid/translated geometry before using it, retaining last valid measurement. Do not calibrate an animating overlay. Use the same fixed measured viewport height, actual native title paint when observed, stable default animator and actual durations for both. Cache verified stock animator durations by GMMP version, update the existing instance rather than replacing it during render; separate style and path identity. Native style observation must not rerender the entire Playlist viewport.

Delete NativeQuickNavItemAnimator and forced holder animateAdd/recyclability bookkeeping: the maintainer reports regression and these are not native Files presenter callbacks. Use ordinary native RecyclerView insert/remove notifications for all visible nested navigation. The hidden-root special case is staged: show the actual Storage holder first, then append its child after the first actual layout through the same adapter update path. Generation/path guards prevent old paths reappearing; retain immediate hidden-root reset on Back (original Files has no hidden-root transition). Existing smooth native scrolling remains; avoid redundant geometry calibration on nested navigation. Add only bounded read-only event logging to the already-installed verified host Adapter notification hooks for known native pg1/qg1 identity, not a new mutation hook. This can establish actual source event order on normal use without dedicated test rounds; cap at 24 events per native adapter. Exact original presenter method/animation ordering remains unavailable without the original 4.2.1 APK/source, so no claim of frame-for-frame parity.

Loader: share existing NativeQuickNavLoading but create a fresh installed drawable, prefer the original sampled native MaterialProgressBar class, native support tint API and observed padding/height. Remove implicit framework padding/minimum height; bring the bar above content. Keep real work completion ownership (normal viewport layout / Smart native projection) and ensure an already requested visible first draw is not cancelled before one animation frame has had the opportunity to render. No invented minimum loading interval, delays, or repeated scans. Debug logs identify actual created type/height and once per visible generation whether the drawable reports running. The recorded PropertyValuesHolder missing set() warning at 13:00:15.177 is in the installed library; no proof of attribution to the injected bar. Do not patch original library animator targets. Native loader appearance and timing need device confirmation.

Audit: no translation or playback/writer/picker changes, no new companion category or log filter; bounded native events use existing tag, per-generation loading line only in debug. Tests cover invalid source geometry and root seed/append/removal. Exact-head normal CI required. Native GMMP recoverable pg1 invalid-position warning at 13:01:28.768 is still present, original adapter is never modified. The additional outgoing Smart re-adoption at 13:01:17.149 is noted; do not broaden this animation-focused change to list lifetime without confirmed new flash.

### 2026-11-28 — native vertical AppBar scroll and overlay alignment

Exact-head `daaaeb3cc612` export `GoneSmart-last-5min-20261128-140226-683.zip`: 2600 lines, 13:57:30.129–14:02:26.558. Maintainer reports transient black top strip in normal Playlists while scrolling and Smart top bar not collapsing correctly. Native host is `AestheticCoordinatorLayout#mainCoordinatorLayout` on both surfaces. Smart attach repeatedly logs nestedScroll=true->false; at 14:00:33.184 and 14:01:03.659 the real host scroll callback consumes 14/21px with nestedScroll=false. Restore ownership to GMMP: do not disable or change original native nested-scroll flag. Native CoordinatorLayout/AppBar may consume pre-scroll, native onScrolled(dy) remains the sole source of consumed list distance for the synthetic folder band. Existing touch relay, direct touch guard, selection, overscroll and projection behavior unchanged. No manual toolbar translation/height/visibility/behavior calls.

Both overlays are siblings of the original native list/page, not CoordinatorLayout's own scrolling child. OffsetTopAndBottom/translation/ancestor motion need not dispatch native OnLayoutChange; the normal overlay was otherwise only repositioned on the 1200ms style fallback. Introduce a shared allocation-free (reused int buffers) coordinate alignment helper called from each already-installed foreground pre-draw listener and existing explicit positioning. Follow actual list window position relative to its host, including host scroll; mutate overlay x/y only if moved, never force measure/layout or resample style. Offscreen pages remain idle; picker retains its prior draw-time behavior. This addresses a credible stale-coordinate cause of the black bar; logcat does not provide pixel evidence. The host's native AppBar decides the actual collapse policy, including short lists.

Native QuickNav evidence now includes actual installed animator timings (13:59:44.529: add/remove 120ms; move/change 250ms) and verified pg1 notifyItemRangeInserted(start=3,count=2) at 13:59:45.965, removal start=3,count=2 at 14:00:53.734. Current shared suffix diff matches those nested operations. Native root removal is irregular (two disjoint removals at 14:00:54.376, followed by native SafeGridLayoutManager invalid-position exception); do not reproduce this inconsistent state. Maintainer considers residual forward animation difference tolerable; preserve previous QuickNav and loading implementation instead of another speculative animation. 43 draw opportunities report running=true; 31 Smart reveals, all fallback=false and counts match. Loader initial fallback height 48px, live sample 6px at 14:00:38.295; not changed in this vertical fix. No claim exact visual parity. Tests cover movement in both directions and stable geometry; native device collapse and black strip remain to be validated in normal use. No new hooks, logs, I18N, release actions or playback changes. Exact-head CI required.

### 2026-11-28 — Storage return, native add-layout eligibility, visible loading lifecycle

Exact-head `202eae82a975` export `GoneSmart-last-5min-20261128-174050-316.zip` has 2661 lines (17:35:51.810–17:40:51.264). Device accepts Smart vertical scroll; 17:40:17.266 nestedScroll=true->true and consumed dy=75 at 17:40:18.255. All 22 Smart reveals match counts and fallback=false, no FATAL EXCEPTION. Preserve these changes. The export contains no original Files QuickNav sample/events, so rely only on previously documented measured native 120ms add/remove and 250ms move/change, and actual suffix insertion/removal. Device reports forward motion still missing, most parent removals visible, Storage return lacks animation, loading bar no longer visible.

The Storage return previously bypassed the animator through immediate GONE/endAnimations/adapter reset. For both tabs, retain Storage while removing exactly the old path suffix using normal RecyclerView updates, wait for layout and actual ItemAnimator completion, then hide and retire the module-owned adapter. Smart updates the native inset when the strip finally hides. A superseding navigation resets the retiring strip while hidden first; callbacks are guarded by adapter ownership, path revision and rootClosing state, no old folder resurrection. The staged Storage-only opening remains, with rootOpening ownership and cancellation. Disable the fixed-size optimization only on horizontal strips (not the normal vertical viewport); the parent is allowed to resize and must not race the posted fixed-size update path. Use the layout manager's public requestSimpleAnimationsInNextLayout before actual structural notifications, not direct holder animator calls, arbitrary translation, or a fixed time. This addresses layout eligibility, not proof of visually identical native add frames.

Loading trace: 37 first-draw opportunities, 36 running=true, one false; both created MaterialProgressBar wrappers report height=48 (17:39:41.795, 17:40:17.264); installed PropertyValuesHolder missing set() warning at 17:39:41.874. A running flag does not prove visible pixels. Return to the framework horizontal ProgressBar wrapper with independent installed native drawable as device-confirmed in `178e90f`; no MaterialProgressBar wrapper constructor. Derive no-sample geometry from the drawable's own unpadded intrinsic height, use live geometry when available, persist only measured native height/padding/intrinsic flag by installed version+density. Sampled tint/current native accent remain live, not persistently cached. If the native padding method is unavailable, retain its actual intrinsic height, no hard-coded 6px. Keep the loader through the first real frame opportunity and the current header transition's layout/ItemAnimator completion as well as existing real row-work completion. Fix the race where first-draw observer removal before next-frame callback made finish clear prematurely. No fake loading timer or duplicate file scan. Bounded natural pre-draw and generation/detach ownership, no new host hooks. Actual pixel visibility and the exact original Files presenter order cannot be proven from logcat or JVM tests; installed GMMP 4.2.1 APK not available in this task. Tests verify root suffix/reset plans, exact-head normal CI required. Existing first-frame gates, native model/selection/move/playback and vertical scroll untouched; no I18N/log-filter changes.

### 2026-11-28 — direct GMMP 4.2.1 Files APK audit (supersedes inferred QuickNav implementation)

Reference: maintainer-supplied private `base.apk`, package `gonemad.gmmp`, versionName 4.2.1, versionCode 95618, SHA-256 `1a877efb65522c57a8f65c7e7f44bdeaa0aee086dc324c76a91af64086c8c891`. ZIP CRC verified; three DEX files and binary resources/manifest inspected directly. Private ChatGPT Library file `/GMMP-reference-1a877efb.apk` (library_file_id `libfile_6b65b3cfa98c81919d7378db90cfb47c`). Retrieve this exact private Library file for later APK investigations, verify SHA, do not publish the proprietary APK, decompiled source, or resource dump in the public repo. These are compatibility findings, not bundled GMMP code.

Bytecode evidence (R8 aliases apply to this exact version only):
- `wg1.W1(File)` builds the path; `wg1$f.apply` interleaves equal empty-path `on1` separators via `za3.m`, compares complete old/new lists with `qg1` (DiffUtil callback equality for both item/content), calculates `androidx.recyclerview.widget.p.a(callback,false)` with move detection disabled, stores the new presenter snapshot, delivers list+DiffResult.
- `mg1.t3(List,p$d,u23)` creates native `pg1` once (stable IDs), on updates swaps the COMPLETE `pg1.v` list then dispatches DiffResult. Calls `quickNav.scrollToPosition(size-1)` in the same transaction; no delayed smoothScroll or forced animator callback. `pg1` uses metadata path hash as stable ID and position for separators; metadata/layout resources are `rv_horiz_metadata` / `rv_horiz_separator`. RecyclerView version 1.4.0; stock DefaultItemAnimator `o` / `f0`. `mg1.R(Context)` creates SafeGridLayoutManager(spanCount=1, orientation=HORIZONTAL). SafeGrid only wraps the normal grid layout in exception recovery; the module must not hide inconsistent notifications.
- `frag_file_browser` binary XML (resource 0x7f0c0065) is a vertical LinearLayout with animateLayoutChanges=true and transitionGroup=true; a wrap-content FrameLayout contains `quickNavRecyclerView` with 8dp horizontal padding, then `mainProgressBar` (MaterialProgressBar, BOTTOM gravity, match-parent width, 2dp height, initially GONE, native horizontal style, mpb_progressStyle=horizontal, mpb_setBothDrawables=true, mpb_useIntrinsicPadding=false, gmDynamicColor=!mainColorAccent). `mg1.u0(boolean)` sets indeterminate then VISIBLE; `mg1.z1()` sets GONE; `jt3.onProgressBarEvent` owns event dispatch. Earlier constructors without XML attributes were not equivalent. The original progress bar overlaps the bottom of the header, not a translated overlay below it.

Implementation: share a one-span horizontal GridLayoutManager and stock animator, read live installed header XML attributes, atomically publish the full interleaved adapter dataset before stock DiffUtil dispatch (detectMoves=false, equal separators) and immediately scroll to last like mg1. Use distinct stable-ID namespaces for separators/metadata to avoid collisions; preserve all original native holder XML. Remove the previous delayed smooth scroll and manual requestSimpleAnimations calls. Both use the same wrap-content FrameLayout with the original progress view constructed using the actual live XML AttributeSet and native style, native layout params (no manually guessed height/translation), verified installed drawable shape, dynamic !mainColorAccent subscription scoped to active load. Missing/ambiguous resource binding omits bar rather than inventing style. Start indeterminate then show; hide GONE at completed real data/layout/animation generation (GoneSmart has distinct async projection lifecycle). Native loader tint, dimensions, style and placement are no longer guessed. Smart's native inset uses the measured shared frame height; preserve original native vertical nested scrolling and projection masks. Do not attach the native Files presenter/adapter to module-classloader holders or reparent native views; keep the module's own view model actions. Keep staged Storage entry and actual animator-completion Storage removal because GoneSmart intentionally hides Storage at root while Files always shows it. Native XML's root LayoutTransition applies to the original Files root; do not turn it on indiscriminately on Smart overlay (would animate its masked native projection/folder viewport and risk regressions). That root-only difference is deliberate. No fixed new animation times. Test full item snapshots and existing root lifecycle policies; JVM checks cannot establish frame-by-frame device parity. No new host hooks, unrelated features, release or original APK redistribution. Exact-head CI required.
