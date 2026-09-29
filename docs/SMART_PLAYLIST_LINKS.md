# Playlist Bridge — feasibility study (28 September 2026)

**Status: WRITABLE DEBUG-ONLY END-TO-END POC PREPARED AFTER THE FIRST DEVICE TRACE. Use disposable Smart Playlists only. No ordinary playlist or GMMP database row is mutated by Playlist Bridge.** Target: the maintainer's original GMMP 4.2.0 APK and the existing `feature/multi-playlist-add` development branch. Do not commit or redistribute the privately supplied proprietary APK.

## User goal and proposed names

The GMMP Smart Playlist editor already has **Link Smart Playlist**: it imports another `.spl` file's RULES, not the membership of a normal playlist. Add a SECOND native-looking toolbar action **Link Playlist** that selects an existing ordinary playlist (including physical nested folders when GoneSmart Playlist folders is enabled), dynamically includes its actual songs in the smart rule/group and saves the link in the edited Smart Playlist. Approved GoneSmart feature name (maintainer decision 2026-09-28): **Playlist Bridge**. The user must NOT have to convert ordinary M3U playlists into duplicate visible Smart Playlists or refresh copied snapshots manually. Existing Link Smart Playlist is unchanged.

GMMP's own editor documentation: https://gonemadmusicplayer.blogspot.com/p/help-smart-playlist-editor.html . The developer explicitly said regular playlist CONTENTS were not indexed into the smart-rule database and the original application does not offer this native operation: https://www.reddit.com/r/gonemadmusicplayer/comments/1ghiv3l/ . This source-level investigation must not be described as a successful working device prototype.

## Native-first findings in the previously supplied original 4.2.0 APK

The materialized original APK's binary AndroidManifest contains the GMMP package and version `4.2.0`; DEX class/source metadata and selected direct method bodies were inspected read-only. Obfuscated identifiers are ONLY observations for this exact APK:

| Native class/method | Observed bytecode meaning and reuse candidate |
| --- | --- |
| `Lds4; / SmartEditorPresenter.kt: g2(Z)` | Original link-Smart-Playlist chooser wiring; builds the native add/edit callback and populates existing native `Lws4` Smart Playlist selections. Investigate its real Activity/Fragment lifecycle, native toolbar action and dialog before injecting a second button. |
| `Lds4;->P1(Lgt4;)` | Original editor path that assigns a rule ID and appends a new `SmartRuleBase` to the current editor's rule list, marks the editor dirty and refreshes the original view. Prefer calling this instead of a second custom editor. |
| `Lds4$i;->invoke` and `Lds4$h;->invoke` | Original add/edit linked Smart Playlist callbacks construct a `Lft4` rule with native `File.absolutePath + "|" + displayName`, then use the native editor add/replace and refresh paths. Verify constructor field constants before reusing for ordinary paths. |
| `Lft4; / SmartRule.kt: c(Node), t(XmlSerializer)` | Native rule XML parser and serializer write `Field`, `Operator`, `Value`, `TimeUnit`; a native link's path/display-name encoding is therefore a candidate for persistence. Whether native edit/validation accepts an M3U target has NOT been proven. |
| `Lft4;->z(LinkedHashSet, Integer): Lww3;` | **Critical evaluation boundary.** The existing linked-Smart rule splits the stored value on `|`, loads the referenced `.spl` using `Lws4.r(File)`, compiles its nested native rules into an original `Lww3` WHERE clause, and guards against recursive links. A narrowly scoped hook for a specially recognized ordinary playlist reference could instead produce membership using the same native query AST. |
| `Ljt4;->z(...): Lww3;`, `Lws4;->c(...): Lvw3;` | Native rule groups/top-level Smart Playlist compilation preserve `AND` / `OR`, native ordering, grouping and limits. Prefer returning a normal original `Lww3` from the new leaf condition rather than filtering ONLY the final displayed track list (which breaks nested groups, OR, limits and Auto-DJ). |
| `Lot0; / DatabaseSearchHelper.kt: t(Lqw3;, List): Lxw3;` | **Confirmed native `IN` helper.** Builds an original `Lxw3` WHERE predicate with operator `IN`, using an original query field and a value List. Native `Lz75.ID` and `Lz75.URI` song fields are referenced by the original rules. Verify actual parameter/value limits and suitable matching key before calling. |
| `Lhp3; / PlaylistFile.kt: c(...)` | Original file-read path selects PLS, WPL or the regular M3U-family native reader, populates parsed `Lhp3.r`; `Lip3; / PlaylistFileDataSource.kt: F(...)` resolves file entries to original GMMP library models. Determine the safe **read-only** entrypoint/lifecycle for a disposable native reader before considering a custom parser. |
| `playlist_file_table` original SQLite schema | Stores URI, display name and playlist ID, not a per-playlist membership index. A separate legacy `playlist_table` schema exists in DEX, but its three columns (playlist ID, position, track ID) are NOT evidence that arbitrary ordinary M3U membership is indexed for Smart Playlist rules. No GMMP DB schema edit or direct undocumented DB write is planned. |

**Feasibility judgment:** Native source contains the editor insertion path, link-rule XML format, query compilation boundary, group AST and built-in SQL `IN` helper required by a credible native-first solution. It does **not** yet prove that a linked ordinary file passes the original native editor's validation, survives a save/reopen cycle, or that an injected condition is consumed by EVERY Smart Playlist details, Play, Shuffle, pinned-tab and Smart Auto-DJ path. Confirm these before enabling a writable feature.

## Proposed implementation, conditional on a focused runtime prototype

1. Observe the ORIGINAL toolbar's Link Smart Playlist control and `SmartEditorPresenter` add/edit/save pipeline on GMMP 4.2.0 with bounded read-only logs. Identify original menu IDs, `Lft4` link constants, native row formatting, rule validation, caller context and hook classloader. The second `Link Playlist` button belongs NEXT to the original, using its actual native toolbar style, menu mechanics and player-locale wording.
2. Use original GMMP ordinary playlist models/paths and reuse the verified Playlist folders browser in **read-only selection mode**. Do not turn an ordinary playlist into a second persisted `.spl` or produce a duplicate Smart tab row. Use path/subtitle to disambiguate same-name playlists from different folders and include external/SD-card paths where native GMMP already permits reading them.
3. In the candidate native `Lft4` linked-rule representation, store a uniquely distinguishable, validated normal-playlist path plus display text via the original editor's `P1` and native XML writer IF original validation/binding is demonstrated. If native save/edit rejects the representation, investigate a narrowly scoped extension sidecar bound to the stable native Smart Playlist identity, without blindly modifying GMMP DB or serialized rule types.
4. Intercept `Lft4.z` ONLY for a verified GoneSmart ordinary-playlist link; leave all original native Smart links and rules unchanged. Read ordinary playlist entries with a verified original GMMP read-only file reader, match them to actual indexed song IDs/URIs in the existing GMMP library, return the native WHERE clause from original `Lot0.t(field,List)`. An empty playlist must compile to a verified always-false native predicate (not accidentally match everything). Reuse native `Ljt4.z` for every nested AND/OR group and `Lws4.c` for ordering/limit. Do not hand-copy entire Smart Playlist evaluation.
5. Refresh membership on original playlist changes, native scan events and relevant Smart screen entry; use safe file metadata/revision invalidation or a verified native update signal. Read on a bounded worker, avoid blocking the UI, and never replay a stale cached source after a file becomes missing/inaccessible. Verify native query bind limits for large playlists (use native supported chunked predicates only if required). Duplicate tracks in a normal playlist represent set membership, not duplicate Smart result rows. Ignore missing library tracks without inventing database rows.
6. Decide and document the disable/uninstall contract: a `.spl` containing an extension-only ordinary link may not work correctly in stock GMMP. Avoid silently corrupting a previously working smart playlist on toggle-off, module failure, export/import or uninstall. Verify whether original save validation allows the link; safeguard an existing `.spl` before the first write, fail closed, and provide a clear compatibility notice in the English-only companion Help.
7. Test update, deleted/renamed/moved playlist, path changes from existing GoneSmart Move, nested real folder, external URI, empty source, thousands of entries, native editor reopen/save and all Smart Playlist consumers. Native links must still behave normally. Never launch playback merely to discover membership; don't use the Play Flipped interceptor as a fake playlist reader.

## Focused proof-of-concept gate before implementing the full UI

- Verify live GMMP 4.2.0 `Lft4.z` receives original linked `.spl` rules and accepts a **read-only test** returning a real native `Lww3` derived through original `Lot0.t`; use only disposable Smart Playlists and ordinary Playlists.
- Confirm a validated synthetic ordinary-link rule survives original `Lds4.P1` -> `.spl` save -> original editor reopen and can display/edit/remove its source with a reused native row. If this fails, STOP and map a safe separate persistence mechanism before making writes.
- Verify updates without manual copy, mixed nested AND/OR semantics, native query limit and whether Smart Playlist details/playback/pinned/Auto-DJ all consume the same compiled rule. Do not promise them based on source signatures alone.
- If an original native read-only ordinary playlist resolver or query-compiler reuse cannot be verified, report the precise blocker and seek maintainer approval before writing a parallel parser/evaluator.

## I18n, UI and delivery discipline

The companion app remains English-only. Inside GMMP, compose native localized `playlist` and the installed original link/action title where grammatically possible; any truly new phrase uses the already centralized `GoneSmartGmmpStrings.kt` with the known GMMP 4.2.0 language codes. Follow `docs/DESIGN_SYSTEM.md`, original skin/theme/layout, original editor controls and current Playlist folders navigation. Add opt-in setting, English in-app Help, README/FAQ, tests and documentation **only when a runtime-verified implementation is ready**. No feature code has been changed by this feasibility document and there is no authorized merge, signed release or device-success claim.

Source: original privately supplied GMMP 4.2.0 APK (read-only DEX mapping in this session); current GoneSmart branch `AGENTS.md`, `docs/NATIVE_GMMP_AUDIT.md`, `docs/PLAYLIST_FOLDERS.md`, `QueueFlipController.kt`, and published official GMMP documentation above.


## First runtime diagnostic build — 28 September 2026

Branch: `feature/playlist-bridge`.

This build directly tests the two architectural questions raised by the maintainer without modifying Smart Playlist behavior:

1. **Does a persisted native Smart Playlist link get re-evaluated dynamically?** Debug-only hooks observe the original `ds4.g2` chooser, `ds4.P1` add-rule path, `ft4.c/t/z` parse/serialize/compile methods and `ws4.r(File)` source load. Values are classified and path/name data is hashed/redacted. If `ft4.z` and `ws4.r` recur when the Smart Playlist is reopened/refreshed/played, the correct Playlist Bridge architecture is a persistent reference plus on-demand membership resolution, not a copied snapshot.
2. **Can GoneSmart reuse GMMP's actual ordinary-playlist reader instead of parsing M3U itself?** Debug-only hooks observe original `ip3(Context,kp3,int,boolean)` construction and `ip3.F(start,count)` page reads, walking only the already verified native `kp3 -> hp3 -> th1 -> File` model to identify the source in redacted form. Returned item counts and native parser/cache counts are logged; no item titles or paths are exposed.
3. The exact original `ot0.t(qw3,List) -> xw3` native SQL `IN` helper and `z75.ID/URI` query fields are verified at hook-install time. Calls occurring inside a native linked-Smart evaluation are observed passively. The diagnostic never invokes this helper on its own and never substitutes a returned WHERE clause.

### Device test for this build

Use disposable data only.

- Create **Smart A** with a simple ordinary native rule (for example a rating/year rule that returns a small known set).
- Create **Smart B**, use GMMP's existing **Link Smart Playlist** action to link Smart A, save Smart B, close the editor, reopen/edit Smart B, then open its results and trigger one normal Play/refresh. This should produce `SMART LINK CHOOSER`, `SMART LINK ADD`, `SMART LINK SERIALIZE/PARSE`, `SMART LINK EVAL` and `SMART LINK SOURCE READ` markers as the corresponding original operations occur.
- Open one small disposable **ordinary M3U playlist** in the normal Playlists tab and let its tracks display. This should produce `PLAYLIST READER INIT/PAGE` markers.
- Capture Logcat for package `gonemad.gmmp` filtered by tag **GoneSmartPlaylistBridge**. The diagnostic intentionally logs hashes/counts instead of actual playlist paths or track metadata.

Do **not** manually edit a `.spl` for this test. The next build should only write a synthetic ordinary-playlist link after these logs establish the native lifecycle and after the stock editor's validation/persistence boundary is mapped.


## Diagnostic v2 after first device log — 28 September 2026

The maintainer's first supplied device log exercised the Smart Playlist list/editor and an ordinary playlist, but contained **no** `GoneSmartPlaylistBridge` markers. At the same time the older debug-only native save observer was active, proving that GoneSmart debug hooks in general were running. The Smart rule RecyclerView and native `ds4` presenter were both reached during the test. This does not prove whether the wrong local branch/build was installed or whether one eager reflection lookup aborted the original all-or-nothing bridge installer.

The v2 diagnostic therefore changes the instrumentation architecture, not GMMP behavior:

- all candidate hooks install independently; one missing obfuscated class/method no longer disables the rest;
- every Bridge event is mirrored under the already-visible `GoneSmartPlaylist` tag with prefix `BRIDGE |`, while retaining the dedicated `GoneSmartPlaylistBridge` tag;
- startup emits `DIAG V2 START`, one `HOOK READY/MISSING` line per candidate and `DIAG V2 READY`, making a branch/build mismatch immediately visible;
- `ft4.z` compile logging now covers every native `ft4` rule and separately marks whether its value is a native linked `.spl`;
- native `ip3.F` logging reports only the returned item's CLASS/FIELD/METHOD SCHEMA (no field values, paths or track metadata) so the exact native song-ID/URI accessor can be mapped before any query injection is attempted;
- still no button, custom rule persistence, query replacement or playlist mutation.

The next writable proof-of-concept remains gated on these v2 runtime observations.


## End-to-end debug PoC — 28 September 2026

The correct device log from the first diagnostic build established the two blockers that had to be proven before writing anything: original linked Smart rules were parsed and re-evaluated during actual Smart Playlist display/play, and the original GMMP reader loaded the disposable ordinary M3U as 22/22 parsed entries. The next build intentionally batches the remaining core path into one device test.

### Implementation in this PoC

- A second Smart-editor toolbar action is inserted only into original menu_gm_smart_editor, anchored to original menuLink. It reuses GMMP's original ic_gm_link drawable and link_playlist localized string; GoneSmart's lilac sparkle distinguishes the extension action.
- Ordinary choices come from original GMDatabase.E().G1() / PlaylistDao. The selection UI is original GMMP zn4 ShowDialogEvent -> MaterialDialog list rendering.
- Selection is validated/prewarmed through original hp3.c before the native Smart editor is mutated.
- Persistence is a normal original ft4 linked rule (field -1) added through original ds4.P1. Its Value is gonesmart-playlist:<hex-path>|<display name>. Native rule-row formatting still uses segment 2 as the visible name, while stock GMMP without GoneSmart sees a non-file sentinel instead of the real M3U path.
- On ft4.z, only a validated Playlist Bridge sentinel is intercepted. Current playlist membership is obtained with original hp3.c, then compiled with original ot0.t(z75.URI,List). Lists above 800 values are split into native IN clauses and combined with original zw3(..., OR).
- Missing, unsupported, parse-failed or empty sources fail closed using original ot0.p(z75.ID, Long.MIN_VALUE); they never fall through to GMMP's SPL loader.
- Membership is only cached in memory, keyed by canonical path + file lastModified + length. The first use/process restart or a changed file invokes the native parser again; no membership snapshot is persisted.
- Clicking an already persisted Bridge rule follows GMMP's original linked-rule edit dispatch, but GoneSmart intercepts only that Bridge value and reopens the same native ordinary-playlist chooser. Original linked Smart Playlists continue through ds4.g2(true) untouched.

### One consolidated device test requested

Use one disposable ordinary playlist and one disposable Smart Playlist. Confirm the second sparkle link icon; select the ordinary playlist; save and verify Smart results; reopen and edit the linked Bridge rule; modify the ordinary source without editing the Smart Playlist and verify membership updates; then use normal Smart Playlist Play. Capture Logcat filtered by GoneSmartPlaylistBridge. Relevant markers are POC MENU, POC CHOOSER, POC ADD/EDIT, POC SOURCE, POC COMPILE and POC RULE COMPILE END.

If any step fails, preserve the disposable Smart Playlist file and log but do not hand-edit it. The next change should target the single failing native boundary rather than split the feature into many speculative builds.


## UI consolidation after successful end-to-end device test — 28 September 2026

The maintainer confirmed the first writable Playlist Bridge PoC worked end to end on the first device run, including saving/reopening the bridge rule, normal Smart Playlist display/playback, and live source-playlist membership changes. The supplied log shows the same persisted M3U bridge compiling through GMMP's native `track_uri IN (...)` path with 16 members and, after the ordinary source changed, 6 members without editing the Smart Playlist.

The next build intentionally leaves that proven persistence/evaluation path unchanged and only consolidates the Smart Editor UI:

- GMMP's **single original `menuLink` toolbar action/icon remains**; the synthetic second toolbar item is removed.
- Clicking that original action opens GMMP's host AppCompat `PopupMenu` anchored below the same button, with **Smart Playlist** and native **Playlist** choices.
- The Playlist choice carries the existing lilac GoneSmart sparkle as an inline baseline-aligned badge.
- Smart Playlist dispatch calls the original `ds4.g2(false)`; Playlist dispatch enters the already-proven Playlist Bridge chooser. Existing linked Smart Playlist editing still uses original `ds4.g2(true)`; existing Bridge-rule editing remains scoped to the Bridge chooser.
- The original Smart Playlist chooser title is changed only inside the verified `ds4$g.accept -> bx.K0(link_playlist)` call to **Link Smart Playlist / Smart Playlist verlinken** on the tested English/German locales. Other host locales first reuse GMMP's own localized Smart-Playlists/link/playlist vocabulary.
- The original Smart Editor rule-summary formatter `os2.U(gt4)` is reused. Only native linked `.spl` rules change from the ambiguous **Playlist: ...** prefix to **Smart Playlist: ...**. Playlist Bridge rules continue to use GMMP's original localized **Playlist: ...** prefix.
- The former synthetic toolbar action ID is gone, which also removes the repeated Android resource lookup noise for `0x47534201`.

This remains debug-only until the compatibility/disable contract and broader locale/UI coverage are finalized.


## Portable disabled-module compatibility — 28 September 2026

The maintainer explicitly requires Smart Playlists containing Playlist Bridge rules to remain usable when GoneSmart is disabled. The desired semantics are: native GMMP rules continue to work; the unavailable Playlist Bridge rule contributes no filtering; no missing-file crash or user-facing error should be required.

A source audit of GMMP 4.2.0 found that the first PoC encoding was only safely ignorable at the top level. A missing linked-Smart source becomes GMMP's empty `yw3` clause and `ws4.c` filters that marker at the top level, but nested `jt4` groups do not perform that filter before building their AND/OR expression. Therefore a bogus Bridge path is not a sufficient disabled-module contract for arbitrary nested rules.

The new persisted V2 representation remains a **valid native linked Smart Playlist rule**:

`<neutral .spl>|<visible playlist name>|gonesmart-playlist-v2:<hex real M3U path>`

GMMP 4.2.0 reads component 0 as the linked `.spl` and component 1 as the displayed linked-playlist name; the verified native evaluator/summary ignore later components. GoneSmart reads the final component and compiles the live normal-playlist membership exactly as before.

Tiny compatibility Smart Playlists are written with GMMP's original `ws4.t(File)` serializer into GMMP's own private files directory. Their predicate is either:

- **true**: native `track_id != Long.MIN_VALUE`
- **false**: native `track_id = Long.MIN_VALUE`

Each Bridge occurrence gets its **own deterministic compatibility filename**, keyed from the owning Smart Playlist destination plus its rule-tree position and true/false role. This is required because GMMP 4.2.0's linked-Smart recursion detector keeps every visited source path for the complete compilation; reusing one shared true/false file would make a later Bridge rule look recursive.

The field mapping is the verified GMMP 4.2.0 `cg.A(100) -> z75.ID`; native operator 1 is `!=` and operator 0 is `=`. The files live under GMMP app data, so disabling or uninstalling GoneSmart does not remove them. Stale compatibility files are harmless private app-data artifacts; a moved/re-saved Bridge rule simply receives the deterministic path for its new tree position.

During the original `ws4.t(File)` save only, Bridge rules are temporarily pointed at true/false compatibility files. The choice is derived recursively from the actual native rule tree so a Bridge-only subtree is neutral to its nearest surviving AND/OR expression; if an entire Smart Playlist contains only Bridge rules, it degrades to the same unfiltered/all-tracks semantics as an empty native Smart Playlist. The live editor objects are restored in `finally` immediately after the native writer returns. V1 PoC values remain readable and migrate to V2 on the next save.

Device verification still required: save/reopen one Bridge Smart Playlist, disable GoneSmart in LSPosed and restart GMMP, then verify both a top-level and a nested Bridge rule are ignored while native rules still filter normally and no visible error appears. Re-enable GoneSmart and verify the Bridge rule becomes active again. If a user restructures an AND/OR group while GoneSmart is disabled, re-enable and save once before relying on the neutral-placeholder placement again.

## Smart Playlist folders — native-first investigation proposal

The maintainer requested a **separate opt-in GoneSmart setting** for folder navigation in GMMP's Smart Playlists tab. It must not be tied to the existing normal Playlist folders setting.

GMMP 4.2.0 uses `os4` / `ss4` / `ls4` / `vs4` for the Smart Playlists surface and native `ws4` models. The verified `ss4.P1` loading path constructs the configured Smart Playlist root, filters `.spl` files with GMMP's own `pt1("spl")`, and currently calls `File.listFiles(FileFilter)` on that root only; it is not recursive. This makes nested physical Smart Playlist folders technically feasible as a GoneSmart navigation extension, but the following native contracts must be mapped before implementation: subfolder refresh/observer behavior (`qs4`), native Smart Playlist creation/save destination, and preservation of every original context action on actual `.spl` rows.

Planned product behavior: a companion option **Smart Playlist folders** (English companion, initially off), independent navigation state from normal Playlist folders, physical nested folders, normal Android Back, and the same accepted native-first breadcrumb/row/theme rules where the Smart Playlist surface exposes equivalent live styles. The option should not be exposed until the native loader/creation lifecycle is implemented rather than presenting a no-op toggle.


## Bundled compatibility + Smart-Playlist-folders diagnostic — 28 September 2026

The maintainer accepted the consolidated single-link-button UI and requested two follow-ups to be tested with as few device passes as possible:

1. **Disabled GoneSmart compatibility.** The existing V2 portable representation remains unchanged. Its neutral native linked-`.spl` placeholder is selected recursively according to the surrounding AND/OR operator, so removing Playlist Bridge from evaluation should leave the surviving native rule tree logically unchanged. This still requires one stock-GMMP/LSPosed-off device verification after saving with the V2 build.
2. **Terminology.** Whenever the literal product term is used, GoneSmart now spells **Smart-Playlist** with a hyphen, including English. The popup still derives the normal **Playlist** noun from GMMP's live `playlist` resource. The Smart-Playlist noun is derived from GMMP's live `smart_playlist_editor` / `smart_playlists` resources; GoneSmart only normalizes the requested punctuation where the native wording literally contains `Smart Playlist`. The chooser verb/order still comes from GMMP's live `link_playlist` resource.
3. **Smart-Playlist folders.** No no-op setting is exposed yet. One read-only debug diagnostic is bundled into the compatibility/terminology test build. It observes the exact native `ss4.P1` load request, the verified `jz.apply` mode-5 root loader, `os4.B2` fragment binding, `ls4.U` adapter updates and `qs4.onEvent` FileObserver notifications. It also reports only redacted root identity plus direct child-directory/direct-`.spl` counts and the number of native `ws4` models loaded. This is sufficient to implement the separate **Smart-Playlist folders** option in the following build without a dedicated diagnostics-only device pass.

The Smart-Playlist-folder diagnostics do not insert folders, recurse, alter adapter data, redirect creation, or change the native FileObserver.


## Disabled-module compatibility device result + first Smart-Playlist-folders implementation — 28 September 2026

The maintainer disabled GoneSmart for GMMP, restarted the player and verified the V2 Bridge rule remained visible in the native editor as `Playlist: <name>` but no longer affected results, including when nested inside a rule group. The supplied full Logcat independently shows stock GMMP compiling the V2 compatibility placeholders as native `song_id != Long.MIN_VALUE` and `song_id = Long.MIN_VALUE` leaves inside the surrounding query. This confirms the intended boolean-neutral disabled-module contract on the tested GMMP 4.2.0 device.

The bundled Smart-folder trace also confirmed the real `smartListRecyclerView` uses native `ls4`, `ss4.P1` drives reloads and `qs4` observes file changes. The next implementation therefore exposes the requested separate **Smart-Playlist folders** companion option (default off) and keeps native ownership of Smart-Playlist files/actions:

- configured Smart root is resolved through GMMP's original `tx4.b(rx4.s)`;
- each current-folder `.spl` is parsed by original `ws4.r(File)`;
- current-folder Smart models are sorted with original `ou4.e` using the live `ts4.q/zu4` sort preferences;
- original `ls4` remains installed and receives the current-folder native `ws4` list;
- GoneSmart overlays only physical folder rows plus a breadcrumb built from GMMP's own horizontal metadata/separator XML;
- Smart-Playlist click/long-click/context actions are dispatched only through a currently bound native `vs4` holder whose `A.v` file path matches the requested source;
- folder creation reuses GMMP's existing MaterialDialogs new-folder creator;
- original `ss4$b` add flow remains unchanged, but a brand-new root `ws4.t(File)` save is retargeted to the currently selected physical folder; existing files are never implicitly moved;
- a FileObserver is scoped to the currently open nested folder because GMMP's own `qs4` observer is rooted at the native Smart root.

One writer hook now coordinates the new-folder destination redirect and Playlist Bridge's already-tested temporary V2 persistence rewrite so the two features cannot race or double-hook `ws4.t(File)`.


## Smart-Playlist folders first-device-build failure and corrected native adapter contract — 28 September 2026

The first functional Smart-folder build (`4491cea`) was **not accepted** after device testing. The screen could go black and Logcat showed `java.lang.ClassCastException: ws4 cannot be cast to t23` from `r1.c` while `ls4.onCreateViewHolder` was creating Smart-list rows. The same capture also contained a high-rate `Invalid ID 0x00000000…04` stream.

A second DEX pass established the exact contract crossed by that build:

- `ls4.x` is metadata-row configuration; `ls4.U(List)` only assigns it.
- `r1.c(w23,int)` reads `w23.i0()` / `ls4.x` and casts entries to `t23`.
- Smart-Playlist items themselves live in `ls4.y`, the native AsyncListDiffer.
- `ns4`, the native differ callback, compares `ws4` objects.
- `os4.j2(List)` is GMMP's native Smart-item submission path and calls `ls4.y.b(List)`.
- GoneSmart therefore submits current-folder `List<ws4>` through the same differ and never through `ls4.U(List)`.
- Action scrolling uses the submitted `ws4` order rather than `ls4.x`.
- The Invalid-ID flood was independent: page-front detection repeatedly called `getResourceEntryName()` for generated View IDs with a zero package byte. Those are now rejected before the Resources API is called.
- If no native Smart row exists to sample (for example an empty root containing folders), synthetic folder rows use a visible theme-derived TextView fallback instead of an unbound metadata XML row.


## Smart-Playlist folders second-device-build result: native rows must stay native — 29 September 2026

The corrective differ build (`080acd1`) removed the earlier `ws4 -> t23` crash: the supplied device Logcat contains no exception and shows current-folder snapshots rendering successfully, including one folder with zero Smart Playlists and the root with 85 Smart Playlists. However the visible surface could still alternate between a correct native screen and a black screen containing only three-dot handles.

That behavior exposed the remaining architectural mistake rather than another model-type error. The controller still set the real `smartListRecyclerView.alpha = 0` and covered the full list with GoneSmart-created copies of every Smart-Playlist row. Consequently GMMP's correctly bound `vs4` rows existed underneath while GoneSmart's copied row XML/theme sampling determined whether title text happened to be visible. Lifecycle/layout timing could therefore make one opening look correct and the next one black.

The implementation now follows a stricter native-first contract:

- the real `smartListRecyclerView` is never hidden;
- GMMP's original `ls4/vs4` draws and handles every actual Smart-Playlist row, including text, theme, context menu, click and long-click behavior;
- GoneSmart renders only a compact breadcrumb + physical-folder header;
- the native RecyclerView receives temporary top padding equal to the measured header height, with clipping enabled so native rows do not paint behind the header;
- original padding/clip state is restored when the controller detaches or the feature is disabled;
- current-folder filtering still uses the verified original `ls4.y` differ path.

The same device log still showed the repeated Android `Invalid ID 0x00000000…04` stream. The prior guard covered only the Smart-folder controller's own resource-name probe. GoneSmart also has shared hierarchy/menu scanners (normal Playlist folders, multi-select and player badge detection) that can encounter generated zero-package-byte View/Menu IDs introduced by extension UI. Those hot resource-name paths are now all gated by `NativeResourceIdPolicy` before calling Android Resources APIs.


## Smart-Playlist folder visual parity, root grouping and Move — 29 September 2026

The next device log confirms the native-row architecture is stable: the real `ls4` list attaches, the root renders 85 native Smart-Playlist rows plus one physical folder, and entering that folder switches cleanly to its physical-folder snapshot without a crash. The remaining black row is therefore isolated to the one synthetic GoneSmart folder row rather than GMMP's Smart items.

This build makes Smart-folder chrome follow the already accepted normal Playlist-folders implementation:
- synthetic folder rows clone the live GMMP row XML and copy effective glyph paint, size, color, typeface, gravity, letter spacing, text scale, font padding, line spacing, max-lines and ellipsize;
- the outlined folder glyph is a separate 24dp ImageView with the same inset used by normal Playlist folders, rather than a compound drawable on an incompletely bound row;
- breadcrumb labels use GMMP's `rv_horiz_metadata` / separator XML and the verified 4.2.0 quick-nav title ratio (1.225 fallback, shared persisted ratio when available), without overwriting native XML padding/ripple.

A separate companion toggle **Group root Smart-Playlists** (default off) mirrors the normal Playlist-folders root grouping. When enabled, root .spl files are shown in a virtual, GMMP-localized **Other Locations** node while physical folders remain at Smart root. The virtual node is never offered as a physical move destination.

A fresh source-level audit of the supplied GMMP 4.2.0 APK established the exact Smart Move boundaries:
- `nt4.c(Context, zn0, MenuItem)` receives the exact `vs4` row holder and its `ws4` model for the Smart three-dot menu;
- generic native selection `n3` stores selected models in `s3.c -> s3$a.b`; for the Smart surface those models are `ws4`;
- GMMP exposes no native physical .spl Move writer/action.

GoneSmart therefore injects its localized **Move** command into both the Smart context menu and original Smart multi-selection ActionMode, then reuses the accepted destination UX: physical-folder navigation, ActionMode back/cancel and a clean white-check native AestheticFab. The file move itself is extension-owned because GMMP has no equivalent writer. It is restricted to .spl files inside the configured Smart root, never overwrites, uses atomic move when available with a normal Files.move fallback, and rolls back earlier items if a later multi-move fails.

Native Smart links persist absolute .spl paths. Before moving, GoneSmart parses every Smart Playlist under the configured root through original `ws4.r(File)` and recursively scans `ws4.u` / `jt4.o` / `ft4.q`. If another native Smart rule references any selected source, the move is blocked rather than silently breaking that link. Playlist Bridge sentinel rules are excluded from this inbound-native-link test.

## 29 September 2026 — first parity device follow-up
Device testing of `2a487d07` confirms that the corrected architecture keeps GMMP's original `ls4` adapter for real Smart-Playlist rows, nested folder navigation works, and a physical one-file Smart move completes successfully. The same test exposed a remaining visual difference from accepted normal Playlist folders: the synthetic folder band was implemented as a fixed header, and raw `TextView.paint.textSize` missed GMMP's effective title-size span.

The follow-up keeps `ls4` unchanged. Native top padding reserves the breadcrumb + synthetic folder band, `clipToPadding` is disabled for this decorated surface, and a read-only pre-draw geometry bridge moves only the folder band with the first native RecyclerView child. The breadcrumb remains fixed. Folder-row paint is now derived with the same first-glyph `MetricAffectingSpan` handling used by normal Playlist folders, so both folder text and the 1.225 quick-nav fallback start from GMMP's effective rendered title size rather than its smaller base paint.

After a verified Smart move, one short confirmation is shown. Because GMMP 4.2.0 provides no native localized Move-success sentence, GoneSmart reuses GMMP's complete localized `playlist_saved` phrase; a neutral `✓` is the only fallback if that resource is unavailable or requires formatting arguments. The same feedback rule now applies to normal Playlist-folder moves. This supersedes the earlier no-success-toast choice; failure handling is unchanged.
## 29 September 2026 — second Smart-folder parity follow-up
Testing the first visual-parity build showed that the remaining horizontal mismatch came from the native Smart-row geometry, not the synthetic folder text. The accepted normal Playlist row measured a 36 px title inset on the tested 3x-density skin. Normal Playlist folders now persist that real inset per density; visible native Smart titles are translated only by the delta needed to reach the same title start, and their original translation is restored when the browser detaches.

Move destination browsing no longer replaces the native Smart model list with an empty list. The current folder's original `ws4` rows remain visible while only physical folder rows act as destinations. Move ActionMode/FAB tint now follows the same live GMMP Aesthetic `!mainColorAccent` observer and contextual-bar tinting used by normal Playlist Move.

Physical Smart folder rows now retain a native-looking three-dot control. Its popup inflates GMMP's `menu_gm_context_smart`, keeps only the original localized Delete item, and sends a fail-closed verified `.spl` subtree to the already established original GMMP `py0.b(Context,List<th1>)` delete dialog/worker. Folder moving remains unsupported.

The companion UI separates normal Playlist and Smart-Playlist settings and adds **Multi-Smart-Playlist selection**. The setting defaults on to preserve the existing native `n3 -> s3 -> ws4` multi-selection Move implementation; turning it off simply stops GoneSmart from inserting Move into GMMP's Smart selection ActionMode. It is disabled in the companion UI while Smart-Playlist folders are off because there is then no GoneSmart physical-folder destination browser.

Finally, identical ordered Smart snapshots are no longer submitted repeatedly to `ls4.y`; the signature includes canonical path, file modification time and length. Synthetic folder scroll translation is also retained across same-location refreshes and reset only for a genuine navigation/move-location transition. These changes target the intermittent visual shifting reported during scrolling without replacing GMMP's native adapter or item animator.

## 29 September 2026 — third device follow-up: runtime selection and stable header
The next device log disproved one source-level assumption: although GMMP 4.2.0's generic `n3/s3` structures can hold `ws4`, long-pressing a Smart row on the tested runtime does not enter that ActionMode. The trace reaches GoneSmart's generic long-press observer but no Smart selection callback. Smart multi-selection now therefore attaches only to already verified native `vs4` holders and reads their exact `ws4` paths. A long press starts GoneSmart's selection set and a contextual ActionMode using GMMP's live localized `num_selected`; while active, taps toggle those verified native rows. Normal Smart-row behavior is untouched while no selection is active. The existing single-row `nt4.c` Move path and file/link safety transaction are unchanged.

The physical folder Delete control no longer depends on a visible native Smart row. A bound native `rvContextMenu` remains the first source; if grouped root currently submits zero native rows, the folder row uses the same accepted fallback shape as normal Playlist folders: an end-aligned button with GMMP's own `rvContextMenu` resource ID, `ic_gm_more_vert`, current native tint and `menu` content description. The popup itself still reuses `menu_gm_context_smart` Delete wording/icon and the original `py0.b` delete workflow.

For scrolling, an unchanged folder header is no longer destroyed/re-inflated on every refresh. A signature of location, physical folders, virtual-node state and sampled row style controls rebuilds. Folder-band translation now follows the native RecyclerView's cumulative `computeVerticalScrollOffset()` rather than its currently attached first child, removing recycling-boundary discontinuities. Move palette syncing is also removed from per-frame PreDraw and remains driven by GMMP's live Aesthetic observable.
## 29 September 2026 — shared Playlist-folder chrome refactor
After repeated Smart-folder parity fixes, the accepted normal Playlist-folder implementation is now the explicit UI reference instead of maintaining parallel copies. Both controllers use `PlaylistFolderUiKit` for native folder rows, overflow fallback, Delete-only popup plumbing, selection presentation primitives and the segmented breadcrumb adapter using `NativeQuickNavDiff`. Both also use `PlaylistFolderMoveChrome` for the same native ActionMode, AestheticFab, live `!mainColorAccent` palette observer and cleanup.

The Smart surface still cannot be structurally identical to the normal Playlist browser because real Smart rows must remain GMMP-owned `ls4/vs4/ws4` rows. Therefore the only Smart-specific presentation bridge that remains is the physical-folder header over the native RecyclerView and its scroll synchronization. New parity work should change the shared chrome first unless the difference is proven to originate from that native Smart data/rendering boundary.
## 29 September 2026 — shared parity polish
Smart breadcrumb root is now the same GMMP-localized **Storage** root used by the normal Playlist folder browser. First-text alignment uses the shared post-layout calibration path with the persisted verified Files/qg1 text start or accepted Playlist-title inset fallback.

Smart multi-selection keeps real `vs4/ws4` rows native and adds only a cached 50% native-primary overlay. The overlay color is resolved once per selection session after ActionMode creation, so the first long-pressed row and every later row are identical.

The remaining folder-header scroll bridge no longer consumes RecyclerView's estimated vertical scroll offset. It uses the real top of the lowest adapter-position child while position 0 remains attached, and considers the finite folder band fully scrolled once position 0 is recycled.

Smart's main overflow now uses the same shared folder-add menu builder as the normal Playlist tab: native folder icon, host-localized Add wording, and placement directly after the native New Smart-Playlist command. Menu references are identity-deduplicated and visibility changes are applied only when state actually changes.

## 29 September 2026 — runtime popup/scroll/selection follow-up
The Smart overflow **Hinzufügen** action and the corrected **Speicher** breadcrumb spacing passed device feedback and are intentionally untouched.

The Smart browser no longer listens to global ViewTree layout events for overlay placement. Native overflow PopupWindows produce their own repeated global-layout/traversal cycles; reacting to those cycles caused unnecessary overlay positioning work and a large stream of PopupWindow relayout logs. Only actual Smart RecyclerView bounds changes now reposition the overlay.

The remaining folder-header motion is synchronized continuously from the first attached native row: adapter position times sampled native row height plus the exact padding/top pixel delta. Missing-child transition frames keep the previous offset. This makes RecyclerView holder recycling a continuous coordinate change instead of a folder-band jump.

GoneSmart Smart multi-selection row tint now uses the same `colorAccent + 0x80 alpha` presentation as the accepted normal Playlist folder browser. The contextual ActionMode bar remains native but does not define the row overlay color.

Normal Playlist main-selection mirroring updates existing rendered row foregrounds in place. It does not rerun the full synthetic browser render or insertion animator for a pure select/deselect operation.

The existing native Smart drawer entry is decorated using either the host-localized Smart title or its native resource ID containing `smart`, while still prohibiting a duplicate drawer item.


## 2026-09-29 Smart header lifecycle correction

The first-frame folder delay was previously attacked with a second, fast folder-only loader running in parallel with the full Smart snapshot. Device testing showed that this creates two authorities for the same synthetic header while the native `ls4.y` differ is also changing rows. That fast loader is removed: one background snapshot now supplies both physical folders and the current directory's `ws4` models, and initial native rows/header remain hidden only until that complete snapshot has been rendered.

Because `ls4.y` is AsyncListDiffer-backed, a folder navigation temporarily suspends folder-band scroll coupling and resets it to the top. Coupling resumes after layout settling, and only a visible native `vs4` whose bound `ws4.v` path equals the expected path at the same current `nativeOrder` position may drive the synthetic header. Holders left over from the previous folder are ignored instead of moving the new header. An original `os4.j2` submission marks GoneSmart's prior submission stale before refresh so a nested folder cannot be replaced by GMMP's root list merely because its last GoneSmart signature was unchanged.


## 2026-09-29 exact Smart scroll-delta correction

The snapshot-bound `vs4/ws4` holder verification introduced to reject stale rows after navigation proved too strict as the sole source of folder-header motion: during normal RecyclerView recycling it can temporarily have no verified holder, which leaves the synthetic physical-folder band at translation 0 while native Smart rows continue to scroll underneath it. Smart folder scrolling now uses the native RecyclerView's exact consumed `dy` as the primary continuous signal and clamps that accumulated distance to the physical-folder-band height. The existing holder/path calculation remains only a reconciliation fallback after layout. Breadcrumb behavior is unchanged and stays fixed. Navigation still disables coupling until the new location has been reset to adapter position 0.


## 2026-09-29 Smart scroll authority + staged first frame

Device testing after the consumed-delta patch proved that the remaining PreDraw holder/top reconciliation was still able to overwrite the correct `dy`-accumulated folder offset. Smart physical-folder vertical motion therefore now has one authority only: the native RecyclerView's consumed `onScrolled(..., dy)`, clamped to the folder-band height. PreDraw continues to synchronize native title alignment and selection interaction only; it no longer changes folder translation. Explicit directory navigation remains the only reset to offset zero.

The first Smart frame is also staged without reintroducing the old racing header worker. GoneSmart hides the verified native Smart RecyclerView before its root rows can draw, preserves the original alpha, performs one directory scan on the existing Smart worker, publishes physical folders from that scan immediately, then parses the same scan's Smart files and finally reveals the native rows. The folder can therefore appear before the expensive root model parse completes, while raw root playlists can no longer flash first.


## 2026-09-29 Coordinator-owned Smart scroll and Material input chrome

The next device log exposed why consumed RecyclerView `dy` alone still looked pinned: GMMP's Smart RecyclerView is a nested-scrolling child of the page `AestheticCoordinatorLayout`, and the native Smart toolbar was visibly collapsing during the same gesture. The Coordinator/AppBar therefore consumed part of the gesture before the RecyclerView's own scroll. GoneSmart now temporarily disables nested scrolling only on the verified `smartListRecyclerView`, restores its original value on cleanup, and keeps the native Smart toolbar fixed. The RecyclerView becomes the only vertical scroll owner for this decorated surface, so the same consumed `dy` drives native rows and the finite physical-folder band. A transparent clipping viewport below the breadcrumb prevents the translating folder row from drawing upward across the fixed breadcrumb/toolbar.

MaterialDialogs' small floating `New Folder Name` / `New Playlist Name` label is localized separately from the already-correct dialog title/buttons. Its TextInputLayout/field focus chrome is tinted from the same live GMMP Aesthetic `!mainColorAccent` stream used by Move UI, so the floating label and underline do not inherit the unrelated red static Android accent.


## 2026-09-29 Smart gesture ownership correction after `21c39d42`

Device log from the next build disproved the nested-scrolling theory: `SMART FOLDERS READY` reported the native `smartListRecyclerView` was already `nestedScroll=false`, and repeated visible swipes produced no `SMART FOLDERS SCROLL` callback. The toolbar still collapsed and the synthetic folder stayed fixed. Therefore toggling `isNestedScrollingEnabled` was ineffective and is removed.

The accepted normal Playlist-folder surface naturally avoids this because its full replacement `ScrollView` owns the gesture and GMMP's page Coordinator never gets to steal the vertical drag. Smart must keep native `ls4/vs4` rows, so its equivalent is a non-consuming `RecyclerView.OnItemTouchListener`: from ACTION_DOWN/MOVE it calls `requestDisallowInterceptTouchEvent(true)` on the native RecyclerView parent chain, while returning false so original GMMP click/long-click/ripple/fling behavior remains native. ACTION_UP/CANCEL releases the parent lock. If the gesture stays in the RecyclerView, its real consumed `onScrolled(...,dy)` once again becomes the single source moving the finite physical-folder band while the breadcrumb remains fixed; the Coordinator can no longer collapse the Smart toolbar during that gesture.

The previous transparent clip viewport remains: a translated physical-folder band may disappear upward only inside its own area below the fixed breadcrumb and cannot paint over breadcrumb/toolbar chrome.
