# Playlist Link

**Current status (30 September 2026): feature-complete on the maintainer's tested GMMP 4.2.0 setup and enabled in both debug and release build variants of the v0.4 development branch.** This is branch acceptance, not a published v0.4 release or a compatibility claim for other GMMP versions.

Playlist Link lets a GMMP Smart-Playlist use the **current contents of an ordinary playlist** as a native Smart-rule membership source. It is independently controlled by **UI → Smart-Playlists → Playlist Link**, which defaults to enabled to preserve existing behavior. It extends GMMP's existing Link action; it does not create a copied Smart-Playlist or persist a static track snapshot.

## User flow

In GMMP's Smart-Playlist editor, the existing Link button opens a host-styled choice:

- **Smart-Playlist** — dispatches GMMP's original linked-Smart-Playlist flow.
- **Playlist** — opens GoneSmart's Playlist Link chooser using GMMP's ordinary playlist data and native list-dialog infrastructure.

A saved Bridge rule appears as a normal playlist-style rule in the editor. Editing that Bridge rule reopens the ordinary-playlist chooser. Native linked Smart-Playlist rules remain native and unchanged.

## Runtime architecture

The implementation intentionally reuses GMMP's original 4.2.0 internals rather than building a parallel Smart-Playlist evaluator:

1. Ordinary choices come from GMMP's native Playlist DAO.
2. The selected playlist is parsed through GMMP's original M3U/PLS/WPL reader.
3. GoneSmart stores a distinguishable Bridge reference in the native linked-rule representation.
4. When GMMP compiles that leaf rule, GoneSmart intercepts **only** verified Bridge references.
5. Current source membership is turned into GMMP's native URI `IN` query predicate. Large lists are chunked at the verified safe size and combined with GMMP's native OR predicate.
6. GMMP's original group/AND/OR/order/limit pipeline continues to evaluate the surrounding Smart-Playlist.

No GMMP playlist database schema is modified. No ordinary playlist is rewritten by Playlist Link.

## Dynamic source updates

Bridge membership is not a persisted snapshot. Parsed membership is cached only in memory and keyed by canonical source path, `lastModified` and file length. A changed source playlist invalidates that cache automatically; a later Smart-Playlist evaluation reads its current contents again.

The maintainer's first end-to-end device test showed the same saved Bridge rule evaluating 16 source members and, after the ordinary playlist changed, 6 members without editing the Smart-Playlist.

## Empty, missing and unsupported sources

While GoneSmart is active, an empty, inaccessible, missing, unsupported or parse-failed Bridge source compiles to a native impossible song-ID predicate. It therefore fails closed instead of accidentally matching the whole library or falling through to GMMP's linked-`.spl` reader.

Supported ordinary playlist file types on the tested native parser path are M3U/M3U8, PLS and WPL.

## Disabled setting and disabled-module compatibility

Turning **UI → Smart-Playlists → Playlist Link** off does not remove or rewrite saved Bridge rules. The Bridge UI/evaluation hooks remain registered only so the setting can change live, but when disabled they fall through to GMMP's original behavior. Persisted V2 Bridge leaves therefore use their native compatibility `.spl` and become boolean-neutral. They remain visible in the Smart-Playlist while no longer filtering its results. Re-enabling Playlist Link makes GoneSmart recognize those same leaves and resumes live ordinary-playlist membership.

The same persisted representation is what protects Smart-Playlists when the entire GoneSmart module is unavailable:

The accepted V2 persisted representation is still a valid native linked-Smart-Playlist rule:

`<neutral .spl>|<visible playlist name>|gonesmart-playlist-v2:<encoded ordinary path>`

Before GMMP's original `ws4.t(File)` serializer writes a Smart-Playlist, GoneSmart temporarily substitutes each Bridge reference with a deterministic private compatibility `.spl`. Each occurrence gets its own path so GMMP's native recursion detector does not confuse separate Bridge leaves.

The compatibility files contain one native predicate:

- logical true: `track_id != Long.MIN_VALUE`
- logical false: `track_id = Long.MIN_VALUE`

GoneSmart chooses true/false according to the surrounding native AND/OR tree so removing the Bridge contribution is boolean-neutral to the nearest surviving expression. The live editor objects are restored immediately after the original writer returns.

The maintainer disabled GoneSmart for GMMP, restarted the player and verified on GMMP 4.2.0 that both top-level and nested Bridge rules remained visible but no longer filtered results; surrounding native rules continued to work. Re-enabling GoneSmart restored Bridge evaluation. The new live companion switch deliberately uses this same native fallback path; its toggle transition should receive one targeted device smoke test before v0.4 release.

## Native UI and localization

The original GMMP Link toolbar item remains the only top-level button. GoneSmart reuses its anchor/icon and opens a host AppCompat popup rather than adding a second permanent toolbar action. The ordinary Playlist choice receives the GoneSmart lilac two-star badge.

Inside GMMP, wording is native-resource-first. The companion app and repository documentation remain English. Literal product terminology uses **Smart-Playlist** with a hyphen.

## Move interaction

A Bridge reference stores the ordinary playlist path. Moving or renaming that source outside a Bridge-aware migration changes its identity; if the source becomes unavailable, the active Bridge rule fails closed. This is deliberately safer than silently guessing a new source by display name.

Smart-Playlist-folder Move separately checks **native Smart-Playlist links** to selected `.spl` files and blocks moves that would break those absolute references. Bridge sentinel leaves are excluded from that native-link check because they are not links to the moved `.spl` source.

## Logging and privacy

The functional path uses the dedicated `GoneSmartPlaylistBridge` Logcat tag. User playlist names, full paths and track contents are not required for high-level Companion Logs. Where internal path diagnostics are still useful, `PlaylistBridgePolicy` reduces them to extension, hash and length.

The old development-only hooks that traced native reader pages, generic Smart-rule compilation, query-helper calls and playlist-save call stacks were removed during the 30 September release-prep pass. They are not part of the normal release build path.

## Device verification already completed

On the maintainer's GMMP 4.2.0 device, the following have been accepted:

- Link popup and ordinary playlist choice.
- Add/save/reopen/edit of a Bridge rule.
- Native Smart-Playlist display and normal Play with Bridge membership.
- Membership update after modifying the source ordinary playlist.
- Top-level and nested disabled-GoneSmart compatibility behavior.
- Coexistence with Smart-Playlist folders and the shared Smart writer hook.

These accepted tests should not be repeated merely because documentation was cleaned up. Re-test when code that affects the corresponding native boundary changes.

## Known release boundaries

- GMMP **4.2.0** is the tested target; obfuscated internals are version-sensitive.
- Very large ordinary playlists rely on the current verified URI-IN chunking behavior.
- A missing source intentionally matches nothing while GoneSmart is active.
- Moving/renaming a Bridge source is not guessed from its display name.
- The compatibility contract is verified on the tested GMMP runtime, not promised for unknown future Smart-rule serializers/evaluators.

See also [Smart-Playlist folders](SMART_PLAYLIST_FOLDERS.md), [native GMMP audit](NATIVE_GMMP_AUDIT.md), [architecture](ARCHITECTURE.md), and [persistent coding rules](../AGENTS.md).
