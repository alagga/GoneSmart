# GMMP compatibility playbook

This document is the maintained compatibility ledger and update procedure for GoneSmart's integration with GoneMAD Music Player (GMMP).

It exists because GoneSmart deliberately reuses GMMP internals to preserve native behavior, UI, localization and data transactions. Those internals are obfuscated and are **not a stable API**. A small GMMP release can therefore keep the same visible behavior while R8 renames classes, methods, fields, constructors or generated Room DAO implementations.

Use this file for the **current cross-version map and repeatable update strategy**. Keep detailed reverse-engineering chronology in `docs/NATIVE_GMMP_AUDIT.md`; keep standing contributor rules in `AGENTS.md`. Do not duplicate long investigation histories into AGENTS.

## Maintained probe ledger is part of the compatibility contract

Every compatibility probe is temporary code but **permanent process knowledge**. Whenever a probe is added, changed, narrowed, promoted into a resolver or removed, update this playbook in the same commit. For each probe or resolver family, preserve enough information that a future GMMP update can reuse the method without rediscovering it from chat logs:

- revision/marker and the feature/native boundary being investigated;
- when it runs and what event/surface triggers it;
- exactly which structural/runtime facts it records;
- privacy/performance bounds and why the probe is safe;
- the observation that caused the next probe or resolver;
- the condition under which the probe can be retired;
- the resolver/postcondition that replaces it after graduation.

A probe should not become permanent background logging merely because it is useful. The intended lifecycle is **unknown boundary → bounded passive probe → runtime-correlated candidate → unique structural/semantic resolver → postcondition/device verification → retire or narrow the probe**.

## Compatibility states

- **Accepted** — maintainer device testing has verified the relevant GoneSmart feature boundaries on that exact GMMP version.
- **Observed / unaccepted** — GoneSmart has run on the version and diagnostics exist, but one or more native boundaries are still unresolved or untested.
- **Unsupported / unknown** — no reliable runtime evidence exists.

The companion Home status must warn for every installed GMMP version other than the explicit tested version. The warning belongs on the **entire full-width bottom Compatibility section** of the status card, not on the overall module/injection card and not as an inset warning box inside the green area: module injection can be healthy while one or more GMMP-native boundaries are unverified.

## Current version ledger

| GMMP | State | Evidence / notes |
|---|---|---|
| 4.2.0 | **Accepted baseline** | Current v0.4 development features were implemented and device-tested against this version. |
| 4.2.1 | **Observed / unaccepted** | Install version code 473 observed on 2026-10-01. The public update is small, but the runtime shows a broad R8 remap across Smart DJ, Playlist UI, Playlist Link, Smart folders, Flip and Track Auto-DJ queue observers. |

Do **not** move 4.2.1 to Accepted until every feature intended to ship has either been remapped and device-tested or explicitly classified as unavailable for that version.

## 4.2.0 → 4.2.1 mapping ledger

Status meanings in this table:
- **Verified** = runtime evidence is sufficient to use the boundary.
- **Observed** = diagnostic evidence exists, but the replacement is not yet proven safe.
- **Unresolved** = old boundary is known broken; no safe replacement is selected yet.
- **Stable** = the 4.2.0 boundary still installed/worked in the observed 4.2.1 run.

| Feature / boundary | 4.2.0 baseline | 4.2.1 observation | Status / rule |
|---|---|---|---|
| Auto-DJ refill hook | existing verified refill boundary | installs successfully | **Stable** |
| Player Auto-DJ badge | existing verified player hook | installs successfully | **Stable** |
| Auto-DJ selection | `kr.F1(int): List` | `F1` absent; only concrete `kr.B1(int): List` candidate observed | **Observed**, do not hard-code `B1`; resolve only from a unique concrete int→List contract plus runtime postconditions |
| Auto-DJ TrackDao field | `qr.r` → TrackDao | still yields runtime class `kr` | **Verified** for the observed build |
| Auto-DJ GMDatabase field | `qr.t` → GMDatabase | `qr.t` now yields `ur`; unique database-like field `qr.s` yields `GMDatabase_Impl` | **Verified structurally**; prefer legacy field only if database-like, otherwise require exactly one Room/GMDatabase candidate |
| TrackDao library query | `kr.g2(rawQuery): List` | `g2` absent; hierarchy `kr > gr > ys3`; inherited `ys3.Q(qp4): List` is abstract; no concrete RawQuery method resolved yet; fields include `u → GMDatabase_Impl`, `v → kr$a`, `w → kr$c` | **Unresolved**; never invoke the abstract contract. r8 adds focused `p94/f94` and live TrackDao type diagnostics to derive a read-only semantic query path instead of guessing another method name |
| ArtistDao accessor | `GMDatabase.y()` | succeeds once the correct GMDatabase field is used and returns runtime ArtistDao class `fn` | **Verified** |
| Artist RawQuery method | `ArtistDao.R1(tp4)` | `R1` absent; `fn` exposes several `qp4` candidates including List-returning methods | **Unresolved**; derive/verify by semantic contract, not method name alone |
| Artist RawQuery wrapper | `tp4(String,Object[])` | old `tp4` constructor absent; 4.2.1 DAO candidates use `qp4` | **Observed**; derive wrapper from the verified DAO method parameter |
| Main Playlist RecyclerView adapter | `zn3` | `playlistListRecyclerView` runtime adapter is `ao3`; both are now centralized in `GmmpPlaylistAdapterPolicy` | **Verified for surface identity**; never duplicate adapter-name ledgers in later attach/event paths |
| Main Playlist source extraction | accepted `NativePlaylistSourceInspector` path on 4.2.0 | r7 resolves `wp3.z -> yn3`; native row text identifies `yn3.o` as display title, the remaining location-like String is `yn3.p`, and the unique concrete adapter getter resolves as `bw.O0(int):g02`. The resolver reproduces **256/256** rows. | **Verified read-only source mapping on 4.2.1**; `ao3` is now allowed through the model-source gate. Next acceptance step is end-to-end folder rendering/navigation/action regression testing. |
| Smart Playlist RecyclerView adapter / bound holder | accepted 4.2.0 Smart adapter/model path | `smartListRecyclerView` uses `is4`; r5 sees 84 rows and first holder `ss4 > jw > rx` with holder-owned `ss4.z -> ts4` and inherited `jw.v -> u23` | **Observed**; crucially, the old 4.2.0 symbol `ss4` now has a ViewHolder role, proving that name equality across versions is not semantic identity |
| Add-to-Playlist multi-selection lifecycle | `bo3.I3()` plus `k2()/D1()` view getters | `bo3.I3()` absent; the compatibility installer now attempts/diagnoses `I3`, `k2`, `D1` and the native handler independently instead of aborting at the first miss | **Unresolved**; select a replacement only from unique semantic lifecycle/view evidence; independent native playlist-creation hooks must still register |
| Playlist Link rule model | `ft4(int,int,String,int)` and related `ft4.z(...)` | constructor and evaluation method no longer match | **Unresolved** |
| Playlist Link editor presenter | `ds4(Context,Bundle)`, `ds4.g2(boolean)`, `ds4$g` | constructor/method/inner class no longer match | **Unresolved** |
| Playlist Link native label hook | `os2.U(gt4)` | method no longer matches | **Unresolved** |
| Smart-Playlist model | `ws4(String,int,int,int,ArrayList,int)` | runtime holder evidence identifies `ss4.z -> ts4`; `ts4` exposes `(String,int,int,int,ArrayList,int)`, a rule list, a `File` and a long ID | **Observed row-model replacement = `ts4`**; save/write semantics remain unresolved and must not be inferred from names alone |
| Smart presenter capture | `ss4.P1(fo2)` | method absent | **Unresolved** |
| Smart list submit | `os4.j2(List)` | method absent | **Unresolved** |
| Smart create callback | `ss4$b.invoke` | inner class absent | **Unresolved** |
| Smart context Move | `nt4.c(Context,zn0,MenuItem)` | method absent | **Unresolved** |
| Native Smart ActionMode | `n3` ActionMode callbacks | still install | **Stable** |
| Smart writer | `ws4.t(File)` | method absent | **Unresolved** |
| Flip queue capture | `ex3.D()` fallback capture | method absent | **Unresolved**; constructor capture may still be usable independently |
| Track Auto-DJ queue write observer | `ex3.w(List)` | no matching method observed by old resolver | **Unresolved** |
| Queue position observer | `ex3.b2(int)` | method absent | **Unresolved** |
| Native playlist Play / Flip | `MusicService.w1(int,Object,List)` | method absent | **Unresolved** |
| Flip / Track Auto-DJ menu inflaters | verified native menu inflater hooks | still install | **Stable** |

## Lessons from the 4.2.1 update

### 1. Treat obfuscated names as a fast path, never as identity

A symbol such as `kr.F1`, `qr.t`, `ws4.t` or `ex3.b2` is evidence for one exact tested build, not the semantic identity of a GMMP function.

Preferred resolver order:

1. **Verified legacy fast path** for the accepted GMMP version.
2. **Structural resolver** using parameter count/types, return type, modifiers, declaring-class relationships, constructor shape and field declared/runtime types.
3. **Semantic/postcondition validation** before GoneSmart changes state.
4. If the candidate is not unique or validation is impossible, **fail closed and log bounded diagnostics**.

Never pick a replacement solely because its name or one signature “looks likely”.

The r5 Smart-list trace adds a stronger rule: **the same obfuscated class name can survive an update while its semantic role changes completely**. In 4.2.0 `ss4` was part of the Smart presenter path; in the observed 4.2.1 surface, `ss4` is the concrete RecyclerView ViewHolder. Always validate Android/Java superclass, owning surface, live adapter/holder relationship and runtime field types before treating a familiar name as the same boundary.

### 2. Reject abstract/interface contracts as callable implementations

The first 4.2.1 TrackDao experiment found `ys3.Q(qp4): List`. Its signature looked correct, but it was an abstract inherited contract and invocation produced `AbstractMethodError`.

Every structural method resolver must check at least:
- method is not abstract when GoneSmart plans to invoke it;
- parameter types match the semantic boundary;
- return type is compatible;
- if a bridge/delegate method returns `Object`, validate the runtime result before use;
- owner/hierarchy makes sense for the expected native component.

### 3. Resolve fields by type relationships, not letters

The 4.2.0 database field `qr.t` became unrelated `ur` in 4.2.1, while `qr.s` became the real `GMDatabase_Impl`.

For fields:
- first try the accepted-version field;
- validate the value against the expected declared/runtime type;
- otherwise enumerate non-static instance fields;
- accept a fallback only when exactly one candidate satisfies the required type relationship;
- log field name + declared/runtime type only, never private user data.

### 4. Generated Room DAO layers can add abstract contracts and delegates

A Room DAO runtime object can inherit abstract query contracts while concrete work is delegated through generated classes or fields. A missing direct method does not prove the query disappeared.

When a DAO mapping breaks:
- record class hierarchy and interfaces;
- inspect concrete vs abstract methods separately;
- inspect non-static delegate fields and their runtime types;
- derive RawQuery wrapper types from the verified native DAO method instead of hard-coding obfuscated query-class names;
- validate returned collection/row shapes before caching the mapping.

### 5. Hook installation must be feature-isolated

One version-specific failure must not abort independent features. 4.2.1 initially made GoneSmart look completely dead because one Smart-DJ reflection failure stopped later hook registration.

Rules:
- install each independent hook family inside its own guarded boundary;
- split multi-hook families further when one missing sub-hook should not prevent other safe observers from installing;
- report degraded capability precisely;
- never convert “module injected” into “all native hooks compatible”.

### 6. Deterministic mapping failures must not retry in a hot loop

The original startup prewarm retried the same missing reflection mapping every 400 ms. Once a missing/ambiguous native boundary is deterministic for the process, cache that state and stop retrying it.

Retry only conditions that can genuinely change during the process lifecycle (for example native database readiness), not missing methods/classes/constructors in a fixed APK.

### 7. Diagnostics should be bounded, structural and privacy-safe

Useful diagnostics:
- class hierarchy and implemented interfaces;
- method name, declaring class, modifiers, parameter types and return type;
- constructor parameter types;
- field name, declared type and runtime type;
- candidate count and why a candidate was rejected.

Do not log:
- full library contents;
- playlist/track titles unless explicitly necessary for a targeted test;
- filesystem paths in compatibility inventories;
- unbounded method/field dumps.

### 8. Cache resolved native bindings once per process/version

After a resolver proves a boundary, retain the resolved `Method`, `Field` or `Constructor` in the feature's binding object. Do not rediscover mappings from layout passes, scroll events, list refreshes or repeated Auto-DJ calls.

A future cleanup should centralize these contracts into version-aware/native-semantic binding objects instead of scattering string literals throughout feature controllers.

### 9. Prefer stable Android/GMMP semantics around the obfuscated core

When possible, anchor discovery to:
- stable Android / AndroidX types;
- GMMP resource IDs/names and actual localized UI ownership;
- RoomDatabase inheritance;
- Java collection/primitive signatures;
- native model relationships already proven by GMMP itself;
- actual postconditions (queue order changed, list committed, file written, native adapter contains expected model).

This does **not** authorize synthetic substitutes where AGENTS requires native-first reuse.

## Probe revision history for the 4.2.1 investigation

| Revision / commit | Purpose | What it established / changed | Graduation state |
|---|---|---|---|
| diagnostic foundation — `2c456510` | bounded hierarchy/field/constructor descriptions | reusable privacy-safe structural inventory instead of ad-hoc reflection dumps | retained as shared diagnostics utility |
| `gmmp421-r3` — `bc0bb7dc` | one-pass 4.2.1 compatibility inventory | exact probe revision marker; bounded class structures; richer Playlist picker, Smart, Flip/queue/service diagnostics | superseded by narrower runtime-correlated probes |
| r3 all-build visibility — `46df396e` | ensure the actually installed local build emits the probe | removed DEBUG-only gating for the temporary compatibility inventory and logged `buildDebug` | retained only while 4.2.1 is unaccepted; must be narrowed/removed after acceptance |
| `gmmp421-r4` — `c664b0a0` | correlate actual surfaces and live objects | runtime field/collection element types; delayed Recycler snapshots; observed `ao3` Playlist, `is4` Smart and `fx3` Queue adapters | Playlist adapter graduated; Smart/Queue identities remain evidence for deeper resolvers |
| `gmmp421-r5` — `934e762e` + `07364f8f` | fix fail-open regressions and discover row-model ownership | centralized verified Playlist adapters; Smart list is never masked before complete compatible bindings; first bound RecyclerView holder structure is logged; candidate inventory narrowed to types seen in r4 | superseded by r6 after the live holder relationship was established |
| `gmmp421-r6` — `3b6eef2f` + `2c86ef9e` + `b8fbbb5f` + `41e9e7e0` | distinguish surface compatibility from model-source compatibility and follow the live holder ownership chain | unrelated Library RecyclerViews are no longer inventoried; `ao3` remains a verified Playlist surface but its unresolved model source now fails open without repeated 256-row scans; the first bound holder exposes only holder-owned native objects and inherited native collection carriers with field/collection **types only** | superseded by r7 after the live Playlist row model and complete source were semantically resolved |
| `gmmp421-r7` — `d0685702` + `e2461afc` + `3db5b180` + `5081e85f` | semantically resolve and then graduate the 4.2.1 main Playlist source | the bound `wp3` holder owns `yn3`; visible native title + one location-like String + native ID identify the model fields; unique concrete `bw.O0(int):g02` reproduces all 256 adapter rows; `ao3` model source is therefore promoted for the folder path | verified on device for read-only source extraction; end-to-end folder interaction still requires regression testing |
| `gmmp421-r8` — interaction/fallback pass | repair fail-open behavior and make remaining native actions discoverable | Smart-DJ now honors the user's native fallback choice when the 4.2.1 queue reader is unresolved; Playlist synthetic rows consume the same runtime-proven `yn3` binding for native click/context/style forwarding; folder creation resolves the host Kotlin Unit singleton structurally; Playlist move resolves original delete/wrapper/scanner signatures structurally; focused inventories cover `d85/p94/f94/py0/th1/t6/qs4/ns4/tx4/rx4/zu4/ct4/vw3/qw3` and live queue/TrackDao-owned native objects. Smart's folder action is hidden while complete Smart bindings are unavailable rather than exposing a dead control. | active until queue/library and Smart-folder bindings graduate; retire focused class inventories as each resolver is accepted |
| `gmmp421-r14` — consolidated runtime-contract pass | reduce device-test churn while fixing shared 4.2.1 failure boundaries | verifies the runtime ownership evidence `d85.u/kr.u -> f94 (GMDatabase_Impl)` and callable `f94.q(p94):android.database.Cursor`; rejects the false `menu_gm_sort_playlist_list` toolbar match; treats transparent/stale `colorAccent` emissions as unverified; adds selection lifecycle/latency guards and Smart projection-drift repair | Playlist/Smart deep Recycler inventories retired; Queue/Rule diagnostics remain until native read/mutation behavior is accepted |

The revision string exists so a submitted Logcat can prove which probe generation actually ran. Increment it whenever the meaning or coverage of the compatibility probe changes materially.

## Active probe registry and retirement rules

| Marker / mechanism | Trigger and scope | Evidence collected | Bounds / safety | Retire or narrow when |
|---|---|---|---|---|
| `GMMP COMPAT PROBE` | package-ready + hook-registration completion | exact probe revision, module/build variant | two small process-level lines | keep only a compact revision marker once the version is accepted |
| `GMMP COMPAT CLASS` | one process-level static inventory | hierarchy, interfaces, constructors, fields, declared methods of known boundaries | bounded counts; types only | corresponding domain has semantic bindings/resolvers and no unresolved class identity |
| `GMMP RECYCLER ADAPTER/CLASS` | native RecyclerView `setAdapter` / attach, restricted in r14 to still-open Rule/Queue surfaces | resource/surface, runtime adapter class, hierarchy and field types | unique surface/class snapshots; ordinary Playlist + Smart-list deep inventories are retired | surface adapter/model ownership is accepted |
| `GMMP RECYCLER SNAPSHOT` | short delayed samples only for still-open Rule/Queue surfaces | item count plus collection element **types** | fixed delays and relevant resource IDs only | model ownership is known and no timing question remains |
| `GMMP RECYCLER HOLDER/CLASS` | first bound child after non-zero item count on still-open diagnostic surfaces | actual ViewHolder hierarchy and runtime field **types** | first unique holder per surface/adapter; no view text/model values | row-model relationship is encoded in a structural resolver |
| `GMMP RECYCLER NESTED/CLASS` | follows that first Rule/Queue holder when still unresolved | holder-owned native objects plus inherited native collection carriers, including only class/field/collection element **types** | at most four nested native objects per unique holder/surface; no strings, titles or paths are logged | the row model and paged source are structurally identified and consumed by semantic resolvers |
| `GMMP AUTO DJ RUNTIME` | captured live Auto-DJ instance, short delayed samples | runtime field and collection types | fixed sample count; types only | queue/database/DAO fields have stable semantic resolvers |
| `GMMP QUEUE RUNTIME` / `GMMP TRACK DAO RUNTIME` | Auto-DJ refill on an unresolved 4.2.1 queue | runtime field/collection **types only** for the live `qr.q` queue object and TrackDao | fixed delayed samples, no queue titles/paths/IDs | semantic queue reader and full-library reader are verified |
| `GMMP QUEUE RUNTIME NESTED` / `GMMP TRACK DAO RUNTIME NESTED` | same focused r8 capture | structure/runtime **types only** for at most six unique native objects directly owned by the queue/TrackDao, so the QueueDao/query delegate can be found without scanning unrelated classes | no scalar values, IDs, titles or paths; GMDatabase_Impl is skipped | queue/library semantic bindings are verified |
| domain mapping markers (`AUTO DJ SELECTION MAPPING`, `GMMP LIBRARY MAPPING`, `PLAYLIST PICKER MAPPING`, `SMART * MAPPING`, `FLIP * MAPPING`, `BRIDGE MAPPING`) | only when that legacy boundary fails | candidate signatures, hierarchy and rejection reason | bounded, feature-local, no user data | resolver uniquely identifies and validates the replacement |

### Probe escalation rule

Do not immediately add a broader probe when a mapping fails. Reuse existing evidence in this order: current semantic binding result → runtime surface identity → holder/live-instance field types → narrowly selected candidate classes → only then add one new bounded observation. Every new probe must answer a named uncertainty that the previous one could not answer. This keeps future update passes short and prevents compatibility diagnostics themselves from causing main-thread lag.

## Automatic semantic remapping design

The long-term goal is not a larger table of `4.2.0 name → 4.2.1 name`. It is a small set of **semantic binding descriptors** that can rediscover safe native boundaries after R8 renames them.

A mature binding should carry:

1. a stable semantic ID such as `playlist.main.adapter`, `smart.row.model`, `autodj.trackDao` or `playback.playSelected`;
2. an accepted-version fast path, treated only as an optimization;
3. structural constraints: superclass/interfaces, parameter and return types, static/abstract modifiers, constructor shape and owner relationships;
4. runtime anchors: resource ID/surface, actual adapter/holder ownership, field runtime type, RoomDatabase relationship or captured live service instance;
5. uniqueness requirement: zero or multiple candidates means unresolved, never “pick the first”;
6. a non-destructive validation step where possible;
7. a semantic postcondition before any mutating/write boundary is considered usable;
8. a version/process-scoped cache of the successful binding plus a compact diagnostic reason when resolution fails.

This allows many **read-only/observer** remaps to become automatic while remaining fail-closed. Mutating boundaries such as queue writes, Smart-Playlist serialization or file moves require a stronger postcondition and still need device acceptance before the GMMP version becomes supported.

The existing `GmmpReflectionPolicy` is the seed of this architecture: it already rejects abstract methods and only returns a structural fallback when it is unique. Future compatibility work should move repeated field/constructor/method discovery into shared semantic resolvers rather than adding local string checks to controllers. The r5 holder evidence is particularly useful because it gives a runtime ownership chain (`resource → adapter → bound holder → model-like field type`) that is substantially more robust than matching an obfuscated class name in isolation.

## Update procedure for every new GMMP version

1. **Do not change the tested-version constant immediately.** Let the companion mark the new version untested.
2. Capture one clean startup log with GoneSmart enabled and the normal development options.
3. Compare the log against this ledger and classify every native boundary as stable, remapped or unresolved.
4. Fix the earliest shared/core boundaries first (database/DAO, queue, Smart models), because many features depend on them.
5. Prefer structural resolvers over adding a new obfuscated name table.
6. Before adding a new diagnostic, consult the **Active probe registry** and reuse/narrow an existing probe when it can answer the question.
7. Add bounded diagnostics for unresolved boundaries in the same test build so one device pass can answer several questions; increment the probe revision when coverage/meaning materially changes and document the probe in this file in the same commit.
8. Keep failures isolated by feature and stop deterministic retry loops.
9. Run unit tests + debug APK + unsigned release APK + artifact upload on the exact head.
10. Device-test only the affected native boundaries and their immediate regressions; do not repeat unrelated accepted flows.
11. Update this ledger with **observed evidence**, marking mappings Verified only after the relevant runtime behavior is confirmed and recording which probe/resolver produced that evidence.
12. Update `docs/NATIVE_GMMP_AUDIT.md` with detailed investigation notes when useful.
13. Retire or narrow probes whose uncertainty is resolved; do not leave broad inventories active by default.
14. Only after the intended feature matrix passes should the tested-version constant/README compatibility claim move to the new GMMP version.

## Compatibility diagnostic markers

For an unaccepted GMMP build, prefer one clean startup log containing the following bounded markers rather than many one-off probes:

- `GMMP COMPAT PROBE` — exact temporary probe revision loaded by the tested module build.
- `GMMP COMPAT CLASS` — bounded hierarchy/constructor/field/declared-method inventory for known 4.2.0 native boundaries.
- `GMMP RECYCLER ADAPTER` / `GMMP RECYCLER ADAPTER CLASS` — runtime RecyclerView resource/path, actual adapter class, item count and privacy-safe adapter structure/runtime field types.
- `GMMP RECYCLER SNAPSHOT` — delayed item-count plus collection element **types only** for relevant Playlist/Smart/Queue adapters, allowing model remaps without logging titles, paths or row values.
- `GMMP RECYCLER HOLDER` / `GMMP RECYCLER HOLDER CLASS` — the first actually bound native ViewHolder on each relevant surface, including runtime field **types only**. This is used to discover the 4.2.1 Smart model/holder remap without logging row text or invoking guessed write methods.
- `GMMP RECYCLER NESTED` / `GMMP RECYCLER NESTED CLASS` — r6 follows only native objects already owned by that holder and records their class structure plus field/collection element **types only**.
- `GMMP AUTO DJ RUNTIME` — delayed runtime field/collection **types only** for the live Auto-DJ object, used to identify the remapped queue-controller field without invoking guessed methods.
- `AUTO DJ SELECTION MAPPING` — concrete int→List selection candidates.
- `GMMP DATABASE MAPPING` — structurally resolved GMDatabase field.
- `GMMP ARTIST QUERY MAPPING` / `GMMP ARTIST QUERY CLASS` — ArtistDao query candidates and RawQuery constructor shape.
- `GMMP LIBRARY MAPPING` / `GMMP TRACK DAO STRUCTURE` — TrackDao contracts, hierarchy, fields and delegate methods.
- `PLAYLIST PICKER MAPPING` — no-arg picker lifecycle/view getters.
- `BRIDGE MAPPING` — Playlist Link rule/editor/label constructors and methods.
- `SMART MODEL MAPPING`, `SMART PRESENTER MAPPING`, `SMART FRAGMENT MAPPING`, `SMART CONTEXT MAPPING` — Smart-folder remap evidence.
- `FLIP QUEUE MAPPING`, `MIX QUEUE MAPPING`, `FLIP SERVICE MAPPING` — queue and native Play remap evidence.

These markers are development compatibility diagnostics. Remove or narrow probes once a boundary is verified and a stable resolver replaces them; do not let inventories become permanent per-action logging.

## Longer-term hardening backlog

- Continue introducing semantic binding/resolver objects per native domain (Auto-DJ/Room, Playlist picker, Smart-Playlist model/editor, queue/playback) so feature code does not own raw obfuscated names. Treat `GmmpReflectionPolicy` and the centralized Playlist adapter policy as the first pieces, not the finished architecture.
- Give each binding a legacy fast path, structural fallback, validation function and compact diagnostic description.
- Unit-test resolvers with small fake class hierarchies covering renamed methods, abstract contracts, bridge methods, ambiguous candidates and moved fields.
- Track per-feature compatibility capability rather than one binary “module works” flag; surface degraded feature groups in logs/status without turning the overall injection state red.
- Consider persisting only the **GMMP version + resolved capability summary**, never reflected members or user library data.
- Keep an explicit device acceptance matrix for every GMMP version that GoneSmart publicly claims to support.


## 4.2.1 lessons: Room query interfaces and picker surfaces

When Room's generated database implementation exposes a Cursor method whose argument is an R8-renamed interface, treat the interface contract as the stable boundary. A compatible read-only query interface has one no-arg String SQL getter, one no-arg int argument-count getter and one one-arg void binder callback. Build a proxy for that contract instead of requiring a concrete `(String, Object[])` constructor.

For playlist deletion, resolve the original static `Context + List<T>` GMMP method first and derive `T` from its generic signature. Only then resolve the native File wrapper constructor. This prevents stale wrapper names from blocking both Folder Delete and Playlist Move.

For the Add-to-Playlist screen, the native `playlistListRecyclerView` and sibling `playlistFab` form the semantic picker boundary. A remapped fragment/presenter name must not downgrade that surface to the normal Playlists tab. Correlate a visible holder/model/title with the adapter-position getter before using any unknown adapter.


## 4.2.1 device verification: prefer host semantic boundaries

The October 1 device log exposed false-positive compatibility assumptions. A unique GMDatabase method returning Cursor may accept an R8-renamed SupportSQLiteQuery whose bridge/default method count differs from the previous build; validate the verified Cursor boundary and let the query proxy fail closed on unsupported calls. When a native RecyclerView has already correlated its visible holder/model to a playlist file, destructive operations should reuse that native row/action rather than reconstruct an obsolete File wrapper. **Do not infer model-list ownership from a generic adapter List method.** The October 2 r12 log proved that `is4.U(List)` is the metadata/config path (`u23`), while `is4.x` is the AndroidX differ owning `ts4` Smart-Playlist rows.

For Add-to-Playlist, treat View.performClick / View.performLongClick plus the verified playlist RecyclerView/FAB surface as the stable input boundary. Obfuscated listener helper names are diagnostic evidence only, not ownership contracts. For 4.2.1 multi-add, rebinding an already verified native holder to each selected native model and dispatching the original row click is preferable to depending on a remapped helper handler.

Queue compatibility is split into read identity and native mutation. Read queue identity through the already-open GMMP database Cursor. For mutation, correlate generated DAO entity fields to the Cursor snapshot by queue_id first and only then resolve song_id and queue_position. Never guess queue/shuffle fields from value ranges alone, and roll back reversible queue-position changes if post-write verification fails.

## 4.2.1 r12: device-proven failure boundaries

The r12 compatibility pass must preserve the native row/list ownership demonstrated by the device log. Smart folders submit `ts4` projections through the verified AndroidX differ field only. `is4.U(List)` must never receive Smart models: doing so replaces the adapter's `u23` metadata collection and crashes during holder creation.

Playlist-folder actions use adapter-position correlation, bounded holder binding retries, and the original row click/long-click. For non-empty folder deletion, select the verified native rows through GMMP itself and invoke the original contextual Delete action; this survives R8 moving the old `py0` helper and keeps GMMP's own confirmation/worker authoritative.

Compatibility fast paths for obfuscated listener classes are optional. Install semantic view-dispatch hooks independently so a missing legacy listener class cannot silently disable picker multi-selection or the picker creation speed-dial. Folder creation's MaterialDialogs callback may return erased Kotlin `Unit`; nullable reference returns are valid and must not be rejected merely because no static Unit singleton is discoverable.

## 4.2.1 r13: interface boundaries, native visual preservation and test burden

The next device log proved that the remaining Queue Flip / Smart DJ / Track Auto-DJ read failure was not an unknown SQL API: the runtime already exposed `f94.q(p94): Cursor`, but the resolver walked only classes and superclasses. Include inherited interface methods when resolving read-only callable boundaries. This is appropriate for a verified database instance and a read-only Cursor method; do not generalize it to destructive methods without independent ownership proof.

Picker folder rows are synthetic presentation around verified native playlist models. Android may dispatch the semantic long-click from a descendant TextView, so ownership is established by walking back to the registered GoneSmart row/model rather than requiring the clicked View itself to be the RecyclerView child.

For Aesthetic styling, absence and ambiguity are different. If the old `oy0` `!mainColorAccent` helper has no compatible runtime method, use the live Aesthetic `colorAccent` observable already returned by the theme. Never hide the creation dialog or intentionally make its cursor/underline transparent while waiting for optional compatibility styling; untouched native Material/Aesthetic visuals are the fail-open baseline.

Compatibility diagnostics must not become the performance problem they diagnose. Keep deep reflection snapshots bounded and convert each device-proven mapping into a JVM contract test. A stock emulator cannot validate LSPosed injection into proprietary GMMP unless the exact APK and an LSPosed-capable image are available, so the practical goal is to make the remaining real-device pass small and consolidated rather than pretending emulator coverage proves injection behavior.


## 4.2.1 r14 device evidence and graduated mappings

The r13/r14 Logcat closes several ambiguities that should not be rediscovered on every device pass:

- **Room / read-only SQL ownership:** both the generated Queue DAO and Track DAO own the same live database implementation through `d85.u:f94 -> gonemad.gmmp.data.database.GMDatabase_Impl` and `kr.u:f94 -> GMDatabase_Impl`. The database contract exposes callable `f94.q(p94):android.database.Cursor`. Resolve the unique one-argument Cursor boundary from the verified Room owner, including inherited interface methods. Because injected/host reflection can make framework `Class` identity checks stricter than the runtime relationship observed in Logcat, exact return-type name `android.database.Cursor` is an allowed fallback **only after** database ownership is established. The query object still requires either the legacy safe constructor or an interface proxy and fails closed otherwise.
- **Smart folders:** the accepted 4.2.1 ownership chain remains `smartListRecyclerView -> is4 -> ss4 -> ts4`, with Smart models submitted only through the AndroidX differ field `is4.x`; the observed submit method is `b(List)`, Smart reader `ts4.r(File)`, writer `ts4.t(File)`. The controller may repair a later GMMP root overwrite only when the committed projection's observed item count drifts from its expected current-folder count. The check is throttled and must not become a per-frame reload.
- **Playlist toolbar:** `menu_gm_sort_playlist_list` is a real submenu and must never replace the retained top-level `menu_gm_playlist_list`. Exact verified resource identity is preferred. If a future GMMP build renames the top-level resource, rediscover it from the live `ActionMenuView/getMenu()` host rather than broad `playlist + list` substring matching.
- **Aesthetic palette:** the tested 4.2.1 runtime emitted `#00000000` and later the stale red `colorAccent` through the ordinary accent observable. That fallback is **not** evidence for `!mainColorAccent` and must never be cached/labeled as such. Dynamic selection/dialog compatibility styling uses the host's live primary palette, gives an actually visible native contextual/FAB color precedence when available, and rejects transparent emissions. Native untouched styling remains the fail-open baseline.
- **Normal Playlist selection:** synthetic rows mirror GMMP's original selection only. For off-screen native rows, a predicted highlight may be drawn immediately to hide the hidden-Recycler scroll latency, but the mirror commits only after the original GMMP row action succeeds. A 4.2.0 obfuscated ActionMode callback is merely a fast path; disappearance of the actually observed native contextual selection chrome is the 4.2.1 lifecycle postcondition for clearing synthetic highlights.
- **Probe retirement / performance:** the Playlist and Smart-list adapter/holder/model chains are now device-proven and encoded in resolvers/tests, so their deep Recycler inventories are removed from r14. Keep only diagnostics for still-open Queue/Rule boundaries. Successful structural bindings should be cached for the live process/object rather than rediscovered on each queue poll.

## Manual-test minimization contract

Compatibility development should converge from **logs → semantic resolver → automated contract test → one consolidated device smoke test**, not from repeated tiny device builds.

1. Mine the full latest Logcat before adding a probe. Resolve the earliest shared failure first; one shared Room/queue failure can explain several user-visible features.
2. Convert each confirmed runtime relationship into a reusable resolver and JVM/Android test where possible. Menu identity, reflection-shape uniqueness, selection state, projection policy and other GoneSmart-owned logic belong in CI.
3. Cache successful process-stable mappings and retire deep inventories after graduation. A diagnostic that causes visible main-thread stalls invalidates its own measurement.
4. Bundle the remaining unresolved native questions into one bounded build whenever safe. Increment the revision only when diagnostic meaning/coverage changes and record additions **and retirements** here.
5. Real-device testing is reserved for LSPosed/GMMP host behavior that CI cannot prove: actual hook execution, proprietary fragment/menu lifecycle, live theme values, gesture/animation appearance and end-to-end native mutations.
6. Never request an already accepted flow again unless its native boundary or a shared dependency changed. Keep each requested device pass short and focused on the current unresolved matrix.


## 4.2.1 r15 device evidence

The r14 device pass narrows the remaining failures further:

- **Room pooled query:** the unique verified database boundary remains `f94.q(p94): android.database.Cursor`, but `p94` is concrete and reports constructor `(int)`. This matches Room's pooled query shape rather than an interface. Runtime resolution now requires exactly one static acquire-style `(String,int) -> p94` factory when neither the old `(String,Object[])` constructor nor an interface is available. The resulting object is populated only through structurally resolved bindNull/bindLong/bindDouble/bindString/bindBlob operations. A failure reports bounded constructor/static-method inventory so one device pass is sufficient.
- **Shared Auto-DJ root cause:** startup library/artist loading and queue reading all stopped at the same `p94` factory failure, so Track Auto-DJ and normal Smart Auto-DJ must not be debugged as separate recommendation problems until this shared read-only SQL boundary succeeds.
- **Normal Playlist selection performance:** r14 still repainted every rendered synthetic playlist row multiple times around one accepted native click. r15 changes that hot path to update only the target row before/after native confirmation. Full selection sweeps remain only for bulk teardown/palette changes.
- **Normal Playlist Back:** r14 could observe native ActionMode disappearance and clear the mirror later, but the presentation could remain visible during the native lifecycle gap. r15 clears immediately and explicitly calls the already-observed native ActionMode `finish()` when available; only then is Back consumed.
- **Smart colors:** the legacy Smart New Folder path logged `colorPrimary=#ff000000`; that value is not a selection/input accent. The normal Playlist native New Playlist shell is the accepted visual reference, so Smart creation reuses the captured top-level Playlist `menuAdd` shell when possible. Legacy native prompts keep their original Material button styling and are not overwritten with black primary.
- **Picker color evidence:** the Add-to-Playlist surface can look correct even while Aesthetic reports primary black and accent red because the live Material FAB tint is a stronger native palette source. Standalone Smart selection now prefers semantic/unique live native FAB tint and native highlight resources; black primary is rejected.
