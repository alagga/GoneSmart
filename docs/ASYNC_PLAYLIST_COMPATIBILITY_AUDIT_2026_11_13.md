# GoneSmart recent fixes — compatibility and feature audit (2026-11-13)

**Status: conditional / not release-ready solely on this audit.** This is a source and CI audit of the tested GMMP **4.2.1** branch, **not** a certification of an arbitrary later GMMP version.

**Scope:** `feature/async-track-mix-playback` at pre-audit head `e2b607ec1452bf0b618288f66e9e058e17962d4a`, compared against `87af91a6a0` (42 intervening commits). Includes November normal Smart Auto-DJ/refill, async Track Auto-DJ/Track Mix native initial `qr.z` refill, next-AudioSource recovery, native fallback/partial-fill guards, playlist creation and folder picker, indexed native row navigation, add-to-playlist title repair, and back-navigation overlay. Reference contracts: `AGENTS.md`, `docs/GMMP_COMPATIBILITY_PLAYBOOK.md`, `docs/FEATURE_AUDIT_CHECKLIST.md`, `docs/GONESMART_GMMP_I18N.md`, and `docs/LOGGING.md`.

**Evidence limits:** Static inspection of the named current source/policies/tests, a GitHub commit comparison, and the existing exact-head Actions Build #`43269512755` (success: unit tests, Debug APK, unsigned Release smoke, upload). User's 4.2.1 reports confirm the specific initial Track Mix audio transition and the later playlist creation/open/picker/back-navigation scenarios. No instrumented GMMP 4.2.2+ environment, arbitrary future-APK decompilation, exhaustive device matrix, independent release run, or profiler capture was performed. Do not infer 4.2.2+ device compatibility from green unit tests.

## Feature and rule matrix

| Family | Native ownership and binding | Current 4.2.1 evidence | Future-version disposition |
| --- | --- | --- | --- |
| Async Track Mix initial refill | Native Play and verified `qr.z(int)`; selected CURRENT/queue entry, appended Track IDs/positions and next source postconditions; no materialized decoder retarget | User reported audible continuation after the `0aec337` candidate; tests and head Build green | **Revalidate** refill and MusicService next-source chain for new mappings; no automatic assertion of support |
| Normal Smart Auto-DJ | Native `qr.z`/selection, per-session cold-fill, native fallback and queue checks, event-driven GMMP-owned natural refill | Sources/tests present; prior real-device regression rounds; not all headset/EOF/manual-Next races accepted here | Structural discovery/semantic evidence required for renamed mutable callbacks; unverified mutations must fail closed |
| Playlist folder and picker | Real GMMP adapter/model, independently correlated `getItem(position)`, path/title/holder validation; native row action | User confirms folders restored in Add picker and normal playlist opening after add/create | Partially dynamic; a different adapter/model needs new read-only correlation and may fall back to native list |
| Physical playlist creation | GMMP native callback/writer/scanner, temporary scoped original preference-root substitution and native return singleton | User confirms playlist appears in selected folder and can be opened; separate edge cases not fully exercised | **Version-specific** 4.2.0/4.2.1 callback/delegate layout; unknown R8 maps are rejected, not dynamically ported |
| Playlist Back navigation | Retains already-built folder overlay; initial playlist rows temporarily hidden with bounded native restore | User confirms no flash with latest build | Works on tested layout; future changed Fragment/ViewPager lifecycle must be checked for stale overlays/hidden-list fallback |

## Findings requiring follow-up (prioritized)

### C-01 — P1 portability gap: playlist create binding is still an exact 4.2.0/4.2.1 map
`NativePlaylistCreateBinding.resolveLayout/resolveDelegate` checks names/fields `sp3`, `go3`, `sa4`, `tp3.w`, `vp3.E`, `r15` (and the 4.2.0 map). `GoneSmartModule.installNativePlaylistCreationHooks` installs hooks only after that layout resolves. These checks are shape-validated and the actual native create writer is retained — good for safety — but there is **no bounded structural-semantic fallback** when a new GMMP build renames this chain.

**Impact:** Physical folder creation may be unavailable after an R8 update; existing ordinary GMMP creation should remain untouched. This is **not** proof of a broken 4.2.1 implementation. **Action:** For the next GMMP APK, passively identify both real create callbacks, delegate/selected-root source and native writer, prove uniqueness plus exact-root postcondition, and cache the new mapping. Do not invoke unknown create callbacks just for discovery.

### C-02 — P1 portability limit: next-AudioSource recovery is an accepted-version-only hook
`GoneSmartModule.installNativeNextSourceRefreshHook` requires `MusicService.E2(MusicService)`, `hn3.h0(sq,boolean)`, `sq` and the observed exact method shapes. `AutoDjNextSourceRefreshPolicy` also requires `acceptedGmmpVersion`; the tested version source is `GmmpCompatibilityPolicy.TESTED_VERSION = "4.2.1"`. This appropriately **fails closed** instead of guessing an AudioSource/decoder setter. The exact chain is not automatically rediscovered on a later version.

**Impact:** A renamed boundary can remove the optional post-append recovery that prevented the cached-null next source in the tested async initial path. **Action:** Require a read-only next-version self-test with passive native call/source-setter correlation and an audible second-track device check before enabling the new path. Never replay a guessed playback method or mutate a materialized AudioSource.

### C-03 — P2 liveness: unbounded background join on native refill
`AutoDjRefillSingleFlight.joinActive()` uses `CountDownLatch.await()` without a deadline. `GoneSmartModule` excludes the UI thread and releases the owner in `finally`, which reduces the normal risk. If the native writer or its owning thread stalls, however, another GMMP playback worker can wait indefinitely. This is a **code-level risk, not a reproduced deadlock**.

**Action:** Review a bounded fail-safe/ownership cancellation design against a realistic GMMP worker contention trace before changing this accepted playback path; add regression tests for a stalled owner, interrupted join and successful single-flight completion. Do not arbitrarily resume a duplicate native writer after timeout.

### C-04 — P2 evidence/docs drift: chronology still calls tested scenarios "unaccepted"
`AGENTS.md` section 5 still describes the November `qr.z` + null-next refresh as an "unaccepted candidate"; `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` creation/row navigation sections still have pre-retest "pending" language. Device reports subsequently confirmed several specific fixes. They do **not** prove all contexts or future versions.

**Action:** Reconcile the standing rule and playbook in a separate documentation pass: mark the exact **4.2.1 tested scenarios**, retain untested variants as pending, and keep historical failure narratives clearly historical. Do not convert limited user acceptance into a blanket feature/future-version certification. The old `docs/NORMAL_AUTO_DJ_ASYNC_REFILL_EXPERIMENT.md` is historical work-in-progress and also needs an accepted-vs-experimental table before release.

### C-05 — P2 validation gap: future GMMP versions and combined device edges
Tests cover `NativePlaylistCreateBindingTest` with 4.2.1 Java fixtures, `NativePlaylistRuntimeBindingHolderTest`, `NativePlaylistSourceInspectorTest`, `PlaylistFolderSurfaceLifecyclePolicyTest` and many Auto-DJ policy tests. These prove code invariants, **not** that GMMP 4.2.2+ preserves callback/adapter semantics. The new native picker + creation-only path, offscreen item clicks, multiple folder depths, empty/root M3U, large playlist lists, background playback/headset Next and interrupted provider search are not collectively re-accepted by one final post-fix device matrix.

**Action:** For a new GMMP version, run one **bounded, read-only** mapping self-test, then a single bundled device acceptance of create/add/open/back (main + picker), zero/smart/partial Track Mix and manual/hardware Next. Reject ambiguous mutations independently; do not deactivate unrelated features.

### C-06 — P3 UI and native list lifecycle hazard on a future layout
`PlaylistFolderPreviewController.onNativePlaylistRecyclerPreparing` hides the raw `playlistListRecyclerView` prior to adapter/attach completion; it has a bounded 2.5-second native visibility restore if overlay construction fails. `restoreRetainedMainBrowser` retains and reveals a finished overlay on verified foreground return. This removes the measured flicker but assumes the tested host surface identity and geometry.

**Action:** At next-version port, verify detach, fragment replacement, pager back-stack, unknown adapter and failed overlay host do not leave an invisible native list or a stray clickable overlay. Preserve the bounded restoration; no periodic full-tree scanning.

## Mandatory acceptance-checklist review

1. **Behavior/scope:** Tested 4.2.1 normal playlist creation and folder navigation, native refill and initial Track Mix behavior preserved. The separate non-GoneSmart MP3/TagManager delay does not establish a GoneSmart bug. No new recommendation algorithm is introduced by the playlist fixes.
2. **Compatibility/native-first:** No new direct SQL/Room playlist writer; native create callback, native row click, native `qr.z` refill and GMMP queue mutation owner remain in place. `NativePlaylistRuntimeBinding` uses visible-title/path/holder/getItem identity, unique getter discovery and strict path checks. Gaps C-01/C-02 explicitly limit future versions.
3. **Mutation safety/fail-closed:** Unknown playlist-create layout aborts the redirection; row actions reject unbound/wrong-path holders; `TrackMixNativeFileRefillPolicy` verifies preservation of original queue entries and only accepted prepared Track IDs after refill. Later-version mutating hooks require independent proof, not just signature similarity.
4. **Performance/Logcat:** Deep reflection/recycler inventories are gated in `GoneSmartModule`; accepted runtime caches native holder/adapter mapping. Full native playlist enumeration happens at attach/update rather than per frame; per-action row matching scans only visible rows. Background pool fill remains asynchronous. C-03 remains a liveness concern; no profiler proof of zero overhead. New messages use existing `GoneSmart`/`GoneSmartPlaylist` Logcat tags, not a new tag family.
5. **Companion Logs:** Existing bounded `[Smart DJ]`, `[Track Mix]` and `[Playlists]` event taxonomy and copy/clear/logcat-help contract from `docs/LOGGING.md` reviewed. Recent playlist fixes are mostly native-ui/Logcat boundary corrections; no evidence of a new high-level event category or tag-family change. A final on-device check should verify creation/opening failures and native fallback are visible to users without flooding the companion timeline.
6. **Code hygiene:** The comparison shows historical per-file Track Mix native-enqueue helper and the former continuation policy removed; existing complex Xposed bridge and version-specific probes remain. No temporary branch, workflow or generated forum file was introduced by this audit. Future release-closeout should retire stale source comments/probe references and reconcile docs (C-04). No automatic dead-code removal without proof.
7. **Localization/I18N:** The audited latest adapter/overlay/AudioSource patches introduce **no new GMMP-facing translated phrase: no new translations required** for this audit. Existing playlist failure notices still require a native-resource/locale smoke check if their wording changes. Continue using `NativeGmmpUiText` and central `GoneSmartGmmpStrings.kt` for genuinely missing host phrases; companion remains English.
8. **Documentation/release:** No README/forum/release note publication was requested. The current 0.5.0 release-candidate docs should not claim support for untested GMMP builds or all pending device scenarios. C-04 is an explicit documentation closeout item.
9. **Validation:** Existing pre-audit head `e2b607ec` has a successful **normal Build workflow** [#43269512755](https://github.com/alagga/GoneSmart/actions/runs/43269512755) (unit tests, Debug APK, release smoke, artifact). This **documentation-only audit commit** requires its own exact-head CI before the *new* head may be declared green. No release, merge or tag is authorized by this report.

## Recommended sequence

1. Keep the working GMMP 4.2.1 paths unchanged while the above source-level concerns are tracked.
2. Reconcile accepted/experimental documentation and prepare one bundled device regression matrix for the remaining playlist and Auto-DJ edge cases.
3. Investigate C-03 with a safe blocked-writer regression test before touching playback concurrency.
4. When a newer GMMP build actually becomes available, run the playbook's read-only self-test and re-prove C-01/C-02, then update `TESTED_VERSION` **only after** device acceptance and exact-head CI.

**Audit decision:** Safe native-first and fail-closed **intent is substantially met** on the reviewed 4.2.1 paths; **not uniformly version-independent**, and **not yet an unconditional future-GMMP or release-readiness pass**.
