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

| `gmmp421-r20` — selection/queue carrier pass | close the four remaining host-only regressions without reopening graduated Smart-DJ/Room discovery | main Playlist selection uses removable overlay drawables; Add-picker bar/rows use native selection witnesses; current Queue-row Track Auto-DJ accepts a bounded same-current native Play; `d85.W1():xp4` / `d85.X1():jm1` may use a read-only Object-returning blocking terminal before exact Cursor/entity correlation | only failure-scoped `QUEUE REACTIVE SHAPE` remains active until one native entity reader is verified |

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
| `QUEUE REACTIVE SHAPE` | only when verified `d85` reactive Queue DAO readers fail to yield the expected native entity List | method signatures of the already-owned `xp4/jm1` carrier instances only | failure-only, at most the bounded reader candidates; no row values/paths/titles | one carrier unwrap path is device-verified and encoded in `NativeReactiveListReader` |

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


## 4.2.1 r16 device evidence and probe graduation

The r15 device pass resolves the next layer without requiring another exploratory build:

- **Read-only Room carrier:** `f94.q(p94): Cursor` remains the verified database boundary, but `p94` reports only constructor `(int)` and `staticMethods=none`. The r15 static-acquire hypothesis is therefore retired. r16 treats this as the direct pooled Room carrier shape: construct with capacity, initialize through a unique instance `void(String,int)` method when present, or use the strict post-constructor Room field layout when R8 inlined that initializer. The field fallback requires a single mutable String SQL field and a uniquely zero-valued arg-count int alongside at least one capacity-valued int. The complete carrier initialization (including SQL + argument count) is now exercised by JVM fake-runtime tests.
- **Shared Auto-DJ/Flip boundary remains one problem:** Smart DJ, Track Auto-DJ and Queue Flip all failed before queue/current/entity logic because the same read-only query carrier could not be created. Do not split those into three device investigations until `GMMP READ-ONLY SQL MAPPING` succeeds. Existing queue/current/entity resolvers remain fail-closed and will expose any later independent ambiguity in the same consolidated log.
- **Playlist selection lifecycle:** the log showed selection accepted and then `native ActionMode gone; cleared` only milliseconds later. The native ActionMode callback can precede contextual-bar layout, so it no longer sets the “visible chrome seen” state. Automatic teardown requires the actual visible native context bar to have been observed first. Back/teardown restores captured original row foregrounds directly. The lifecycle rule is covered by a JVM state-policy test.
- **Add picker row ownership:** the picker session and `playlistListRecyclerView + playlistFab` pair were valid, but semantic long-clicks arrived from inner `TextView` descendants and were rejected by the old direct-`FrameLayout` predicate. r16 walks bounded ancestors to RecyclerView's direct child before obtaining its native holder/model. The ancestry rule is covered by JVM tests for valid descendant, unrelated tree and depth bound.
- **Smart folder creation colors:** the log proves the incorrect Smart dialog was the legacy `DialogFileChooserExtKt.showNewFolderCreator` path, while normal Playlist creation in the same process successfully used the original `menuAdd` / New Playlist shell. r16 keeps a bounded strong lease to that verified native menu/presenter across tab switches and prefers it for Smart folders, instead of trying to repaint the legacy dialog's unrelated Material palette.
- **Probe status:** no new broad r16 inventory was added. Playlist/Smart recycler ownership remains graduated. Room carrier shape, picker ancestry and selection lifecycle moved from device discovery into automated contracts. Queue/Rule diagnostics stay bounded until the first successful r16 SQL/queue run proves whether any deeper native mutation ambiguity remains.

### r16 automated-test gate before a device build

Before asking for a real-device pass, CI must pass all existing tests plus:
- direct Room query carrier with instance initializer;
- direct Room query carrier with R8-inlined initializer / strict field layout;
- rejection of a bare `(int)` constructor without SQL ownership;
- descendant-to-direct-picker-row ownership and fail-closed depth handling;
- ActionMode callback-before-layout does not clear selection; disappearance after an actually visible native bar does.

Only after those contracts, debug APK, unsigned release APK and artifact upload succeed should a device run be requested. The next real-device pass is intended to validate host-only integration: SQL invocation inside GMMP, one Playlist Back teardown, Smart native-shell creation across tabs, Add-picker multi-select dispatch, and the shared queue mutation/read path. Do not repeat unrelated accepted flows.


## 4.2.1 r17 device evidence and probe graduation

The latest consolidated log closes four more ambiguities without adding another exploratory inventory:

- **Zero-argument Room carrier:** the unique database boundary is still `f94.q(p94): Cursor`. r16 successfully reached the direct `p94(int)` carrier but failed while trying to distinguish its integer fields. The failing GoneSmart queries are all parameter-free: Queue snapshot, Library snapshot and ampersand Artist catalog pass no bind arguments. For this proven case set only the uniquely owned mutable SQL String after constructing the carrier and preserve Room's default zero argument count. This avoids writing any ambiguous int field. Parameterized direct-carrier queries remain unsupported/fail-closed until a stronger argument-count owner is proven. A JVM regression fixture includes multiple zero-valued int fields to prevent reintroducing the false uniqueness requirement.
- **Normal Playlist Back presentation:** Logcat reports the native ActionMode ending and the GoneSmart selection mirror clearing, while the maintainer still sees the old row tint until the next scroll. The failure is therefore drawable invalidation/order, not selection state. Restore each captured native foreground, pressed/selected/activated state and drawable state immediately, request one layout, then reapply once on the next animation frame after GMMP's ActionMode teardown transaction.
- **Add-to-Playlist overlay ancestry:** the picker session is valid (`playlistListRecyclerView + playlistFab`), but the long-click arrives from a deeply nested synthetic TextView. The native direct-row resolver correctly rejects it because the synthetic row is not a child of the hidden native RecyclerView. The overlay path must first walk to the registered GoneSmart row/model; its bounded depth is widened to 32 and covered by a deep-hierarchy JVM test.
- **Smart creation shell lease:** the normal Playlist top-level menu was discovered through the live `ActionMenuView` path, not the earlier menu-inflation path, so r16 never handed that menu to `NativeGmmpFolderCreator`. The Smart tab consequently fell back to `DialogFileChooserExtKt.showNewFolderCreator`, which explains the red Cancel/cursor/underline. The live ActionMenu capture now also leases the exact native `menuAdd`; Smart creation therefore reuses the same accepted New Playlist shell instead of repainting the legacy prompt.
- **Smart selection palette:** Smart selection is extension-owned and had fallen to `#ff36a8be`. Normal Playlist selection is GMMP-owned and supplies the authoritative visible ActionMode color. Cache only that explicitly observed native selection color and reuse it for Smart ActionMode/row overlays. If it has not been observed, wait for the real Smart ActionMode bar for several frames before using the final fallback; do not recertify stale `colorAccent` or black `colorPrimary`.

**Probe status:** r17 increments the compatibility revision for traceability but introduces no broad class/Recycler inventory. The supplied log already proves the current failure layer. Playlist/Smart Recycler mappings stay retired. Queue/Rule diagnostics remain bounded until the first successful read-only SQL invocation exposes or clears any deeper queue-current/mutation ambiguity.

### r17 automated gate

Before another device pass, CI must additionally prove:
- zero-argument direct Room carrier succeeds even with multiple zero-valued int fields;
- non-zero ambiguous direct carrier still fails closed;
- synthetic picker overlay target resolves through a deep but bounded hierarchy;
- existing descendant bound remains fail-closed beyond the configured limit.

The next device check should remain one consolidated run: Playlist Back repaint, Smart selection/native-shell colors, Add-picker multi-select, normal Auto-DJ and Track Auto-DJ. Any queue-current/mutation failure that appears only after SQL succeeds should be handled from that same log rather than by splitting these into separate probe builds.


## GMMP 4.2.1 r18 — graduated read boundaries, narrowed mutation probes

The r17 device pass materially narrows the remaining compatibility surface:

- **Graduated / accepted in this pass:** the read-only Room bridge (`GMDatabase_Impl -> f94.q(p94): Cursor`), library loading through that Cursor, queue snapshot/current-entry reading, Smart DJ refill/selection, and the Smart-Playlist New Folder dialog. These no longer justify broad static carrier or deep Recycler inventories. Re-open them only on a direct regression.
- **Playlist selection teardown:** Logcat records the native contextual mode ending and GoneSmart clearing its mirror before the maintainer still sees the old row tint. This is a render/presentation residual, not a selection-state failure. r18 performs one local synthetic-row rebuild after teardown; no additional native reflection probe is added.
- **Add picker:** the picker session itself is valid. The failure is `performLongClick` landing on an inner synthetic `TextView` and missing the row-owned target. r18 maps the already-verified target onto every descendant while the synthetic row is created. No new native class mapping is required.
- **Track Auto-DJ:** the selected song is observed playing immediately after native Play, while GMMP continues changing the surrounding queue. Playback stabilization now keys on current queue-entry ID + track ID (track ID fallback on legacy 4.2.0), not whole-queue snapshot equality. A JVM regression test covers the fact that current-index/queue-shape changes do not invalidate the current-entry identity.
- **Queue Flip mutation:** queue read succeeds first; failure moves to the native generated DAO entity reader. r18 changes entity-reader/update/delete discovery from class/superclass-only methods to `GmmpReflectionPolicy.callableMethods`, allowing inherited interface contracts exactly as required by the accepted Room read boundary. Returned native entities still must correlate one-to-one to live Cursor `queue_id`, `song_id` and `queue_position` before any writer becomes eligible.
- **Queue mutation diagnostic/state binding:** if entity discovery remains unresolved, emit one bounded `QUEUE MUTATION SHAPE` record containing only DAO class, expected row count, no-arg method signatures and List/array writer signatures. Do not restore broad queue inventories. The observed current resolver can move between `dx3` state and another accessor host after native Play. r18 therefore accepts either the uniquely matching int field or a method-backed pointer only when one host has exactly one current-valued int getter and exactly one int→void writer; each write is immediately read back and rollback uses the same binding. Ambiguity emits one bounded `QUEUE MUTATION STATE SHAPE` and fails closed.
- **Playlist Play Flipped:** the old exact `MusicService.w1(int,Object,List)` mapping is demoted to a fast path. r18 accepts only a unique host callable boundary with exactly one action `int`, one `List`, and three parameters, then re-enters that same method with only the resolved track List reversed for the one pending Flip token. Resolved track rows are validated structurally by numeric song ID rather than the 4.2.0 obfuscated class name.
- **Play-Flipped verification:** GMMP 4.2.1 verification prefers the already-accepted read-only queue Cursor and preserves the 4.2.0 `ex3/H1/D` verifier as fallback.

### r18 probe lifecycle

| Boundary | r18 status | Diagnostic policy |
| --- | --- | --- |
| Room query / library read | graduated | retire broad discovery; failure-only logs only |
| Smart DJ queue read/current entry | graduated | normal concise mapping log only |
| Smart folder creation dialog | graduated | no compatibility probe |
| Playlist Back visual teardown | presentation fix | no native probe; one local rebuild |
| Add-picker row ownership | synthetic mapping fix | no native inventory |
| Track Auto-DJ playback detection | automated identity contract + device integration | no whole-queue stability probe |
| Queue native entity/write mapping | active | one bounded `QUEUE MUTATION SHAPE` only on failure |
| Queue playback-pointer writer | active structural binding | exact current-valued field, or unique getter + unique int→void setter on one host with read-back; one bounded state-shape log on ambiguity |
| Playlist native playback List boundary | active structural self-test | exact/unique callable signature, failure-specific method inventory only |

The next real-device request is intentionally one five-action smoke pass: Playlist Back visual cleanup, Add picker multi-select, Track Auto-DJ, Queue Flip, and ordinary Playlist Play Flipped. Smart DJ and Smart-folder creation are excluded because this device pass already accepted them.


## GMMP 4.2.1 r19 — post-r18 device evidence

The r18 consolidated run separates the already-graduated read path from the remaining host-only write/input boundaries:

- **Smart DJ is successful in this run.** `GMMP READ-ONLY SQL MAPPING` resolves `GMDatabase_Impl -> f94.q(p94):Cursor`, the library loads 18,743 tracks, queue read resolves 52/53 rows, Smart DJ selects one candidate, applies the replacement and GMMP adds it to the queue. No additional Smart-DJ probe is justified by this log.
- **Track Auto-DJ false timeout:** native Play changes GMMP's current artwork/metadata from the pre-click song to the chosen song almost immediately, but `waitForSelectedSong` continues polling until its nine-second timeout. r19 therefore separates *playback changed* from *queue shape stable*: changed current track ID or changed current queue-entry ID is sufficient verified playback evidence; current-index-only motion is ignored. The following native seed isolation remains fail-closed and is still verified through the queue reader.
- **Queue Flip deeper boundary:** queue Cursor/current resolution succeeds first. Mutation then fails because `d85` has no direct no-arg `List` reader. The bounded r18 shape reports two no-arg reference-return readers (`W1():xp4`, `X1():jm1`) and existing native List/array writer families. r19 adds a read-only structural reactive snapshot bridge: terminal callback boundaries may emit a candidate List, but the candidate is accepted only at the expected live row count and still must pass exact queue_id/song_id/queue_position correlation before mutation. Multiple equivalent emissions are deduplicated by native numeric row fingerprint; ambiguity still fails closed.
- **Add-to-Playlist:** the picker surface/session is valid and the folder overlay renders, but semantic long-clicks arrive on an inner synthetic `TextView` and fall through to the native Recycler row resolver. r19 keeps strong identity ownership only for the current synthetic render (explicitly cleared on rerender/teardown) and removes the unnecessary native-holder prerequisite from selection. Confirm remains the point where a native holder is resolved and GMMP's original add path is dispatched.
- **Playlist Back/up:** the r18 log again records eventual ActionMode disappearance and mirror cleanup only after the user has already seen stale highlights. r19 observes completion of the original click inside GMMP's visible `action_mode_bar`; if the bar disappears within a bounded post-click window, the synthetic presentation is cleared/rebuilt immediately. Original GMMP click behavior is never intercepted.

### r19 automated gate and probe lifecycle

CI must cover the new playback-change policy and a fake R8-style reactive List source before another device pass. The reactive bridge test proves that transformation-style same-source methods are rejected, the callback terminal emits exactly the expected row count, and a uniquely identifiable disposable is cleaned up. Existing selection/menu/Room tests remain required.

No broad Recycler/Room inventory is reintroduced. Smart DJ remains graduated. Queue entity mutation remains the only active structural compatibility mapping; if r19 cannot unwrap `xp4/jm1`, the single failure log should report only the bounded native queue mutation shape required to refine that one boundary.

The next real-device pass remains one consolidated four-action check: normal Playlist ActionMode close/back visual cleanup, Add-picker multi-select, Track Auto-DJ, and Queue Flip.


## GMMP 4.2.1 r20 — post-r19 device evidence

The r19 device log confirms that the shared read-only Room/queue foundation is no longer the blocker. The remaining failures are narrower and can be handled without another broad remap:

- **Queue/Room read is graduated for these flows.** `GMMP READ-ONLY SQL MAPPING` resolves `f94.q(p94):Cursor`, and repeated Queue snapshots resolve 53 rows with a live current pointer. Queue Flip therefore fails *after* Cursor resolution, at native Queue DAO entity acquisition.
- **Normal Playlist Back is state-correct but presentation-wrong.** The log records `visible native ActionMode ended; cleared` followed by `unselected rows rebuilt after teardown`, yet the maintainer still sees highlighted synthetic rows. This disproves further state/back-hook work as the right fix. r20 stops replacing the row foreground and owns a removable overlay drawable instead.
- **Picker selection palette:** the same run exposes generic Aesthetic values `colorPrimary=#ff000000` and `colorAccent=#ff8e0e00`, while a real native selection witness resolves to `#fff08860`. Picker ActionMode and selected-row overlay must therefore share the selection witness pipeline; FAB/primary/accent are not selection authority.
- **Track Auto-DJ current-row case:** native `Spielen` is dispatched successfully, GMMP restarts decoding/scrobbling, but the Cursor current identity remains the same because the selected Queue row is already current. Identity *change* is not a valid universal postcondition. r20 permits a same-current seed only for `menu_gm_context_queue`, only after original Play returned handled and after a 1.5 s guard in which a non-current selection would normally publish its changed identity.
- **Queue native entities:** r19 proves the generated DAO read candidates are `d85.W1():xp4` and `d85.X1():jm1`, but callback-only unwrapping produces no candidate List. r20 additionally tests no-arg Object-returning blocking terminals on these already-verified read carriers. The returned object is accepted only if it is a List with exactly the Cursor row count; then every row must still correlate one-to-one by `queue_id`, `song_id` and `queue_position` before any native update/delete method can run.
- **Probe lifecycle:** no broad Recycler, Room, Smart or Auto-DJ probe is reintroduced. If both carrier unwrap paths still fail, `QUEUE REACTIVE SHAPE` records only bounded method signatures on the already-owned carrier instances. Retire it immediately once one native entity reader is device-verified.

### r20 automated gate

CI must cover the erased Object-returning blocking carrier path and the Track Auto-DJ same-current Queue-row guard, in addition to the existing reactive callback, playback-identity, selection lifecycle and Room contracts. The next device check stays consolidated to only the still host-dependent behaviors: Playlist Back visual cleanup, Add-picker selection/bar colors, Track Auto-DJ and Queue Flip.


## GMMP 4.2.1 r21 — restore accepted behavior, remap only moved boundaries

The r20 device log disproves two assumptions and narrows the remaining Queue boundary again.

- **4.2.0 is the behavioral oracle for accepted flows.** When a 4.2.1 regression affects a flow that worked on the accepted 4.2.0 branch, first diff the last accepted implementation and preserve its lifecycle/state-machine semantics. Compatibility work should replace the moved native boundary, not redesign the behavior around polling or theme guesses.
- **Playlist ActionMode close:** r20 selection begins normally and native row actions are handled, but the mirror is not cleared until the later `visible native ActionMode ended` poll. The toolbar close/up click can leave `action_mode_bar` visible during its exit animation, so 0/32/96 ms visibility checks do not reproduce the old `onDestroyActionMode` timing. r21 uses AppCompat's stable `action_mode_close_button` at the already-installed `View.performClick` boundary. GMMP's original click runs first; GoneSmart then clears only its synthetic overlay/mirror immediately. The old exact 4.2.0 callback remains a fast path.
- **Picker palette:** the same process again reports `colorPrimary=#ff000000` and `colorAccent=#ff8e0e00` before the native `playlistFab` is attached. The extension-owned picker ActionMode must not certify its initial background as native selection chrome. r21 captures the actually rendered `playlistFab` tint first and reads it through framework `View.getBackgroundTintList()/ColorStateList` (reflection fallback), avoiding host/module Material class casts. This live native control color wins over stale Aesthetic fallbacks for picker row/bar tint and teardown.
- **Queue read/current is already graduated:** the latest log repeatedly resolves Queue snapshots through the read-only Cursor and reports `currentResolver=field:t->ur.method:b`. A later generic scan can also find an unrelated TrackDao integer `kr.G1` with the same value; that coincidence is not playback state. r21 resolves `qr.t` first and excludes objects that own the live Room database from generic state-signal discovery.
- **Queue native entity acquisition is the shared Auto-DJ/Flip blocker:** `d85.W1():xp4` and `d85.X1():jm1` still yield no whole List through callback/blocking terminals. Their carrier signatures are now known and no broader probe is needed. r21 extends the read-only carrier bridge to accept an item stream only when every emitted candidate is the same runtime class with at least the numeric identity shape required for `queue_id/song_id/queue_position`, and exactly the known Cursor row count is collected. The mutation bridge then performs its existing exact Cursor correlation before any DAO writer can run.
- **Generated DAO writers:** when an array delete candidate is required, prefer the unique concrete method declared by the verified `d85` instance over an inherited 4.2.0 obfuscated name. Update/delete still run only on correlated native entities, and Queue Flip retains post-write Cursor verification + rollback. Direct SQL mutation remains forbidden.

### r21 probe/test lifecycle

| Boundary | r21 status | Evidence / automated gate | Next diagnostic |
| --- | --- | --- | --- |
| Room SQL / library / Queue Cursor read | graduated | `f94.q(p94)` works; 18,743-track library and repeated Queue snapshots succeed | none unless direct regression |
| Queue Current read | graduated mapping | repeated `qr.t -> ur.method:b`; DAO false-candidate explicitly excluded | failure-only state-shape |
| Playlist toolbar ActionMode close | semantic compatibility fix | JVM resource-policy test + old 4.2.0 lifecycle contract | no broad hook inventory |
| Add-picker color source | host-only integration | actual `playlistFab` ColorStateList captured before theme fallback | one concise native-FAB color log |
| Queue native entity reader | active narrow boundary | JVM item-stream + whole-List/blocking carrier tests; final Cursor correlation remains mandatory | existing bounded `QUEUE REACTIVE SHAPE` only if unresolved |
| Track Auto-DJ / Queue Flip write path | downstream of entity reader | same native entity/update/delete bridge, verified/rolled back by Cursor | only the first downstream unique failure |

Do not request separate probe builds for these items. CI must pass the full unit suite, debug APK and unsigned release smoke build first. The next real-device pass is one consolidated four-boundary check: Playlist contextual close, Add-picker selection/Back colors, Track Auto-DJ, Queue Flip.
