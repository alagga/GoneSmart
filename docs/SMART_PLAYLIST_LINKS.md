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
