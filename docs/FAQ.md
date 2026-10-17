# GoneSmart FAQ

## Is GoneSmart only an Auto-DJ project?

No. GoneSmart is meant to extend GMMP with smart and quality-of-life features in general. Smart Auto-DJ is the current main focus and the first major feature.

Within Smart Auto-DJ, GoneSmart replaces only GMMP's track-selection step. GMMP continues to control playback, the queue and when Auto-DJ requests more songs.

## Where do recommendations come from?

GoneSmart combines three sources:

- **Local Playlists** — read-only co-occurrence in your normal GMMP playlists;
- **ListenBrainz** — recording similarity plus a lower-confidence artist rescue when needed;
- **Last.fm** — similar tracks plus a bounded artist/top-track rescue when needed.

Every final result is still matched against the local GMMP library. Providers never decide which remote file is played.

## What are Smart and Custom recommendation modes?

**Smart** is the recommended default and lets GoneSmart own conservative source/weight defaults. **Custom** exposes the three source switches, per-provider ranking weights, large-playlist weighting and recommendation variety.

Custom always keeps at least one source enabled. If Local Playlists is disabled, Large-playlist weighting is greyed out because it has no effect on ListenBrainz or Last.fm.

## What do provider weights actually do?

Weights change the contribution of that provider's evidence before aggregation; they are not quotas and do not force a fixed percentage of selected tracks to come from each service. A strong Local Playlist-only candidate may still win, while a logical track independently supported by several sources receives a small bounded corroboration bonus.

## What is Recommendation variety?

Variety changes only the ordering of **near-equal final matches**. Balanced uses a narrow near-tie window, High a wider one, and Off keeps score order unchanged. GoneSmart does not add global random score noise, so a clearly weaker recommendation cannot jump over a clearly stronger one just for variety.

## Are Smart Playlists used as recommendation evidence?

Not currently. Normal playlists are often deliberately curated, while rule-based Smart Playlists can be much broader or dynamic. A future opt-in may allow Smart Playlists to contribute a deliberately weaker affinity signal.

## Is Smart Auto-DJ only for electronic music?

No, but the current matching heuristics are especially tuned for electronic-music libraries. That is where naming differences such as Original Mix, Radio Edit, Extended Mix, Club Mix, Remix, Rework, Edit, featured artists and multi-artist credits show up particularly often.

Other genres should work too. If rock, metal, hip-hop, pop, classical or another library exposes bad matching behavior, please open a feature request with a few representative artist/title/version examples and explain which tracks should or should not be treated as the same song family.

## How many queue tracks are used?

GoneSmart uses up to five representative provider seeds. The current and recent tracks receive the strongest weight. The broader session still informs local ranking and drift control without causing an API request for every item in a long queue.

## What happens if direct recommendations do not provide enough local tracks?

GoneSmart first combines Local Playlist Affinity with direct ListenBrainz/Last.fm recording or track evidence and matches that set locally. Artist-level provider expansion is requested only when the number of unique local direct candidates is below a bounded reserve. This keeps the common path fast while still rescuing sparse/new/obscure seeds.

## Why not keep searching indefinitely?

Unbounded retries would increase API traffic, battery/network use and Auto-DJ latency without guaranteeing a local match. GoneSmart therefore uses bounded direct evidence, one conditional rescue stage and a reusable recommendation pool. A partial smart pool is valid; native GMMP fallback is reserved for the configured zero-suitable-track case.

## How does the recommendation pool work?

A successful pipeline produces multiple ranked local track IDs. GMMP then consumes those selections as Auto-DJ needs them. A refill is prepared only when the pool becomes low, the queue session changes, or recommendation-related settings invalidate the old pool.

## Minimum rating vs. Smart rating

- **Minimum rating** is a fixed 0–5 star floor in 0.5-star steps.
- **Smart rating** derives a dynamic floor from the median rating of the current recommendation context.
- If both are enabled, the stricter threshold wins.

## What does Rating fallback do?

The **Rating fallback** switch is available only when Minimum rating is above zero or Smart rating is enabled. If neither threshold is active, the switch is greyed out and reset to off. When hard Minimum/Smart rating filters are the reason no suitable track remains, GoneSmart can retry the same recommendation candidates without those two filters. Other rules, including Exclude 0.5-star tracks, still apply. A status message is shown when this fallback is used.

## What does the native GMMP fallback do?

If GoneSmart cannot supply a smart track and native fallback is enabled, regular GMMP Auto-DJ is allowed to choose. The player sparkle turns red while fallback/problem state is active.

## What happens offline?

Local Playlist Affinity can generate new recommendations without a network connection. A still-valid pool from the current session can also continue offline. Online providers are skipped while offline. Starting a different queue invalidates the old session pool; if no enabled local source can provide a suitable track, native GMMP fallback can take over when enabled.

## What does the sparkle mean?

- Green: GMMP Auto-DJ is active and GoneSmart is ready.
- Red: GoneSmart cannot currently provide smart selection, or regular GMMP Auto-DJ fallback is active.
- None: GoneSmart is disabled or GMMP is in Normal/Shuffle rather than Auto-DJ.

## Is my entire library sent to Last.fm or ListenBrainz?

No. GoneSmart sends the seed metadata required for recommendation lookup. The GMMP library is loaded and matched locally on-device.

## Why can another GMMP version break GoneSmart?

GoneSmart integrates with obfuscated GMMP internals, so structural details can change even when the visible GMMP feature does not. The current accepted/tested target is **GMMP 4.2.1**.

The recommendation feature deliberately avoids treating one R8 name as semantic identity: the live Auto-DJ owner, read-only Room/Cursor query boundary and playlist path/URI data are resolved structurally/semantically, with tested names used only as optional fast paths where appropriate. This improves forward robustness, but a new GMMP version is still unverified until the compatibility workflow has been run.

## Does changing settings require a restart?

**Home → Settings** explains the shared live behavior for both feature tabs. No restart is needed for ordinary **Smart DJ** or **UI** setting changes: both are sent to the running GMMP process. Recommendation-related changes invalidate the current pool so the next refill uses the new settings without interrupting playback. UI tweaks can be switched on or off independently of Smart DJ. Restart GMMP after installing or updating the module, changing its scope, or when troubleshooting hook state.

## How do I add several tracks to several playlists?

Enable **UI → Playlists → Multi-selection**. In GMMP's Add to Playlist dialog, long-press the first destination and tap further destinations. The confirmation checkmark adds all original source files to each selected playlist using GMMP's own playlist writer. The confirmation appears once, with the number of files and successfully updated destinations. Press Back to cancel selection without dismissing the picker.

The selected rows and action bar follow GMMP's dynamic colors. GoneSmart reuses GMMP's own localized strings for this feature; another GMMP language does not require a separate GoneSmart translation. Normal taps and GMMP's create-playlist plus button remain unchanged.

## Can I use the UI feature without Smart DJ?

Yes. Playlist/Smart-Playlist folders, both multi-selection options, Playlist Link, Flip and Track Auto-DJ are UI extensions that do not require Smart DJ to be enabled unless the feature itself explicitly starts Smart DJ (Track Auto-DJ).

## How does Track Auto-DJ work, and can I turn it off?

**Track Auto-DJ** appears after **Play next** in an individual song's three-dot menu. It starts that song, keeps it as the first entry of a fresh queue, enables Smart DJ if necessary and lets GMMP Auto-DJ fill the queue to its configured Initial Size. Turn this feature on or off independently under **GoneSmart → UI → Track Auto-DJ**. It is enabled by default for existing users.

After a successful, verified mix, GoneSmart displays just **one short confirmation**. GMMP's intermediate Play/Auto-DJ Toasts and Snackbars—including delayed Auto-DJ-rules-changed status UI—are suppressed only during the bounded Track Auto-DJ startup window. A genuine error still produces one warning and detailed information in the Logs tab. If Auto-DJ starts refilling the old queue while a mix is being prepared, GoneSmart temporarily defers further old-session refills, then atomically removes the other native queue entries by their unique queue IDs. No repeated asynchronous queue-clearing commands are needed; the selected song and native playback pointer are verified before filling.

## Why does the GoneSmart app say “Track Auto-DJ” when GMMP is German?

GoneSmart's companion app remains English: its UI, Logs and Help always call the feature **Track Auto-DJ**. The action inside GMMP uses **GMMP's own localized strings** for *track* and *Auto-DJ*, so German GMMP shows **Titel Auto-DJ** and English GMMP shows **Track Auto-DJ**. Other GMMP languages use their existing translations, including any in-player language override. For the sole success confirmation, GoneSmart uses GMMP's translated *started* string if available; otherwise it uses the readable English fallback *started*. No copied GMMP translations are maintained in GoneSmart.

## How are GoneSmart updates handled?

The companion app checks the latest published stable GitHub Release at launch and shows the result on **Home → Updates**, without downloading APKs. **Add to Obtainium** opens GoneSmart's GitHub repository in Obtainium; Obtainium then handles notifications, downloads and future APK installation. Prerelease and locally built versions may display as development builds or not compare with stable releases.


## How do Playlist folders work?

Enable **UI → Playlists → Folders**. GMMP's original Playlists navigation entry gains a lilac GoneSmart sparkle. Both the main Playlists tab and Add to Playlist picker show real nested physical folders before loose playlists. **Group external playlists** and **Group root playlists** independently determine which entries appear in the virtual **Other Locations** folder; a real physical directory of the same name is independent. The main tab remembers its folder, while every newly opened Add picker starts in root. Android Back and the picker's top-left Back button navigate up one folder at a time before closing the picker from root.

## Where are new playlists and folders created?

GoneSmart reuses GMMP's original creation and native playlist-writing functionality. The current physical folder is the new playlist's destination. With **Group root playlists** on, root playlists are created from virtual Other Locations; with that option off, they are created directly from the root view. Folder creation/deletion operates on supported physical folders, not on the virtual grouping node. The main tab hides only its native **Add** menu entry when the location forbids creating a playlist, while leaving the other overflow actions intact. The Add picker offers native-styled new-playlist and new-folder choices while keeping multi-selection confirmation available.

## Can I move several playlists between folders?

Yes. In the main Playlists tab, long-press the first playlist and select additional playlists, choose **Move**, navigate to an eligible destination and tap the white checkmark. The destination FAB follows GMMP's native theme and stays above the mini-player. GoneSmart stages originals privately, uses GMMP's original playlist deletion and library-scan operations, verifies the native playlist index and can recover from a partially completed operation. It does not perform a blind file rename or direct database manipulation. Successful moves update the native list without an extra Toast; genuine errors remain visible. Use disposable playlists when testing a new GMMP version.

## How do Smart-Playlist folders work?

Enable **UI → Smart-Playlists → Folders**. GMMP's original Smart-Playlist rows remain native; GoneSmart adds the physical-folder header, breadcrumb and folder actions. **Group root Smart-Playlists** optionally places root `.spl` files in virtual **Other Locations**.

Enable **Multi-selection** in the same section to long-press one native Smart-Playlist row, select more rows and move them together. This selection mode is provided by GoneSmart on the accepted GMMP 4.2.1 runtime because native Smart-row long press does not provide the multi-move workflow GoneSmart needs.

## Can I move Smart-Playlists between folders?

Yes. Use **Move** from an individual Smart-Playlist menu or select several Smart-Playlists with **UI → Smart-Playlists → Multi-selection**. GoneSmart restricts moves to verified `.spl` files inside the configured Smart root, never overwrites an existing destination and rolls earlier files back if a later multi-move fails.

Before moving, GoneSmart checks native linked-Smart rules across the Smart root. If another native Smart-Playlist references a selected source by absolute path, the move is blocked instead of silently breaking that link.

## What is Playlist Link?

Enable **UI → Smart-Playlists → Playlist Link**. Playlist Link extends the Smart-Playlist editor's existing Link action. Choose **Playlist** to use the current membership of an ordinary GMMP playlist as a Smart-rule source. GoneSmart reads that source through GMMP's native playlist parser and compiles it through GMMP's native Smart query predicates, so changing the ordinary playlist changes later Smart-Playlist results without copying a snapshot.

## What happens to existing Playlist Link rules if Playlist Link or GoneSmart is disabled?

Saved Playlist Link rules are not deleted. They remain visible inside the Smart-Playlist, and GMMP can still open and use that Smart-Playlist. The persisted V2 Playlist Link representation contains a native boolean-neutral linked-`.spl` compatibility rule, so while **UI → Smart-Playlists → Playlist Link** is off — or when GoneSmart itself is unavailable — the Link leaves stop contributing membership instead of breaking the Smart-Playlist. The remaining native rules continue to work. Re-enabling Playlist Link restores live membership from the ordinary playlists.

Both the disabled-GoneSmart compatibility and the separate live Playlist Link switch have now been confirmed on the maintainer's GMMP 4.2.0 setup. With Playlist Link enabled, a missing, empty or unreadable ordinary source still fails closed and matches nothing.

## Does Playlist Link survive moving or renaming its source playlist?

Playlist Link identifies the ordinary source by its stored path. If that source is moved or renamed outside a Playlist-Link-aware migration, GoneSmart does not guess a replacement from the display name; the source is treated as unavailable and the Link rule fails closed. Edit the Link rule and choose the intended source again.

## Why does Move have a separate translation table?

GMMP 4.2.0 has localized resources for its built-in playlist/folder actions, but no suitable native **Move** resource for GoneSmart's new playlist relocation action. GoneSmart resolves other injected player text from the installed GMMP's resources and uses one central host-locale translation for **Move**, covering the known GMMP 4.2.0 language codes. The additional feature-owned virtual **Other Locations** name also has host-language fallbacks when no installed native translation exists. Unknown future languages fall back to English. This is distinct from the **English-only** GoneSmart companion UI; these custom translations still need independent native-speaker review before broader release claims.

## Why not Spotify, YouTube or YouTube Music?

GoneSmart prefers stable, documented recommendation APIs that can be used safely by a distributed Android module.

- **Spotify:** current Web API Development Mode is intentionally restricted for broad distribution, and new development use cases no longer receive the former Recommendations/Related Artists functionality. A private WebPlayer/GraphQL integration would be too brittle for a normal provider.
- **YouTube:** the documented Data API no longer offers the former related-video search parameter, so there is no direct stable related-track equivalent.
- **YouTube Music:** there is no suitable public recommendation API. An InnerTube/private integration would be undocumented and too experimental for GoneSmart's normal compatibility contract.

These choices can be reevaluated if stable public APIs become available.

## Is SoundCloud planned?

SoundCloud is a strong candidate for investigation in the 0.6 timeframe because the official API exposes related-track recommendations. The blocker is not recommendation quality but safe credential/authentication design for a distributed Android client; GoneSmart will not embed a shared confidential client secret.
