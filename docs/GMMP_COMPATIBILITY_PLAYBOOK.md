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
| TrackDao library query | `kr.g2(rawQuery): List` | `g2` absent; hierarchy `kr > gr > ys3`; inherited `ys3.Q(qp4): List` is abstract; no concrete RawQuery method resolved yet; fields include `u → GMDatabase_Impl`, `v → kr$a`, `w → kr$c` | **Unresolved**; never invoke the abstract contract; inspect concrete delegate fields / implementation structure |
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

The revision string exists so a submitted Logcat can prove which probe generation actually ran. Increment it whenever the meaning or coverage of the compatibility probe changes materially.

## Active probe registry and retirement rules

| Marker / mechanism | Trigger and scope | Evidence collected | Bounds / safety | Retire or narrow when |
|---|---|---|---|---|
| `GMMP COMPAT PROBE` | package-ready + hook-registration completion | exact probe revision, module/build variant | two small process-level lines | keep only a compact revision marker once the version is accepted |
| `GMMP COMPAT CLASS` | one process-level static inventory | hierarchy, interfaces, constructors, fields, declared methods of known boundaries | bounded counts; types only | corresponding domain has semantic bindings/resolvers and no unresolved class identity |
| `GMMP RECYCLER ADAPTER/CLASS` | native RecyclerView `setAdapter` / attach, now restricted to semantic Playlist/Smart/Queue resource IDs | resource/surface, runtime adapter class, hierarchy and field types | unique surface/class snapshots; unrelated Library adapters are no longer inventoried | surface adapter can be found structurally or explicit version mapping is accepted |
| `GMMP RECYCLER SNAPSHOT` | short delayed samples after relevant adapter attach | item count plus collection element **types** | fixed delays and relevant resource IDs only | model ownership is known and no timing question remains |
| `GMMP RECYCLER HOLDER/CLASS` | first bound child after non-zero item count | actual ViewHolder hierarchy and runtime field **types** | first unique holder per surface/adapter; no view text/model values | row-model relationship is encoded in a structural resolver |
| `GMMP RECYCLER NESTED/CLASS` | immediately after that first bound holder is known | holder-owned native objects plus inherited native collection carriers, including only class/field/collection element **types** | at most four nested native objects per unique holder/surface; no strings, titles or paths are logged | the row model and paged source are structurally identified and consumed by semantic resolvers |
| `GMMP AUTO DJ RUNTIME` | captured live Auto-DJ instance, short delayed samples | runtime field and collection types | fixed sample count; types only | queue/database/DAO fields have stable semantic resolvers |
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
