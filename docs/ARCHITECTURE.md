# Architecture

GoneSmart is an Android companion app plus a libxposed module that extends GoneMAD Music Player (GMMP). The current main feature is Smart Auto-DJ, but the project structure is intentionally broader so future GMMP-focused smart/QoL features can live in the same module and companion app.

## High-level flow

```text
GMMP queue / current session
        |
        v
libxposed hook in GMMP process
        |
        v
Queue/session tracking
        |
        v
Representative seed selection (max 5 provider seeds)
        |
        +------------------+
        |                  |
        v                  v
  ListenBrainz          Last.fm
        |                  |
        +--------+---------+
                 |
                 v
Recommendation aggregation / normalization
                 |
                 v
Local GMMP-library matching
                 |
                 v
Preference ranking + hard filters
                 |
                 v
Session recommendation pool
                 |
                 v
GMMP Auto-DJ selection
```

GMMP remains responsible for playback, queue lifecycle and deciding when Auto-DJ needs another track. GoneSmart only replaces the selection decision when Smart Auto-DJ is active.

## Main components

### Module / hook layer

`GoneSmartModule.kt` is loaded into the GMMP target process through libxposed. It installs the Auto-DJ hooks, reads queue state, coordinates recommendation preparation and hands a selected local track back to GMMP.

Because GMMP internals are obfuscated, compatibility is version-sensitive. The current tested target is GMMP 4.2.0.

### Queue and session context

`GmmpQueueReader`, `QueueSessionTracker` and `SeedSelector` build a session-aware view of what the user is listening to.

Provider traffic is intentionally bounded: up to five representative seed tracks are sent through the external recommendation pipeline rather than blindly querying every queue item.

### Provider layer

- `ListenBrainzClient` handles MusicBrainz/ListenBrainz-backed lookup and similar-recording recommendations.
- `LastFmClient` handles Last.fm similar-track requests.
- `ListenBrainzMatchSelector` ranks plausible recording identities before similar-track lookup.
- `RecommendationAggregator` merges provider contributions into one recommendation signal.

The normal pass is the fast path. If it yields no usable local candidates, GoneSmart performs exactly one broader provider pass and then stops.

### Metadata normalization and local matching

`TrackInputResolver` and `TrackMetadataNormalizer` clean up artist/title input before provider lookup and local matching.

This layer is currently especially tuned for electronic-music naming conventions such as:

- Original Mix
- Radio Edit
- Extended Mix
- Club Mix
- Remix / Rework / Edit
- featured artists
- multi-artist credits
- filename fallbacks when tags are incomplete

`LocalLibraryMatcher`, `LocalArtistFallbackMatcher`, `TrackFamilyKeyBuilder` and `LocalPreferenceRanker` then map recommendations to real local GMMP tracks and apply duplicate/version/rating/era/date-added preferences.

The final selected track must exist locally in GMMP.

## Recommendation pool

`SessionRecommendationPool` keeps multiple already-ranked local track IDs ready for upcoming Auto-DJ requests. This avoids a full network/provider round for every single song.

A queue/session change or recommendation-affecting settings change invalidates the old pool. A new queue never inherits an unrelated old session pool.

## Companion app

The maintainer's detailed, persistent rules for GoneSmart's dark/lilac
companion UI, grouped options, exact design tokens, two-star sparkle
branding of GoneSmart-added GMMP actions and in-app/GitHub documentation
live in [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md); overarching coding and
new-chat handoff rules live in [AGENTS.md](../AGENTS.md).
The companion's fixed brand palette is distinct from GMMP's live
Aesthetic/album-art-driven injected-UI palette.


The companion app provides:

- module / target status
- Smart Auto-DJ settings
- live settings updates
- high-level GoneSmart runtime logs
- FAQ/help
- independently enabled UI-tab extensions
- GitHub Releases update status and Obtainium deep link
- GMMP restart shortcut

Settings are shared with the module through libxposed remote preferences so normal preference changes do not require restarting GMMP.

## Player indicator

`PlayerAutoDjBadgeController` overlays a small sparkle on GMMP's Auto-DJ playback-mode icon:

- green: Smart Auto-DJ is ready
- red: smart selection cannot currently supply a track / native fallback is active
- none: GMMP Auto-DJ is inactive or GoneSmart is disabled

## Optional GMMP UI integrations

All UI extensions are independently configurable from Smart DJ. They follow the same native-first rule: reuse GMMP's real data models, writers, dialogs, resources and theme signals wherever a verified native path exists; add only the behavior GMMP does not provide.

### Playlist multi-selection

`PlaylistMultiSelectController` extends GMMP 4.2.0's native Add to Playlist picker. Selection is keyed to the native playlist path rather than a RecyclerView holder, and every selected destination is dispatched through GMMP's original `io3.r(Context, ie0)` operation. Normal one-destination taps and native playlist creation remain original GMMP behavior.

### Playlist folders

`PlaylistFolderPreviewController` builds a physical-folder view over GMMP's native playlist dataset while preserving the original playlist writer, context actions and adapter refresh path. `PlaylistFolderUiKit` and `PlaylistFolderMoveChrome` centralize shared native-looking folder rows, breadcrumb and Move chrome.

Playlist Move uses a recoverable `PlaylistMoveStager`, GMMP's original delete operation and native playlist rescan/index path. It does not directly edit GMMP's database or blindly rename the indexed original.

### Smart-Playlist folders

`SmartPlaylistFolderController` keeps GMMP's real `ls4/vs4/ws4` list visible. GoneSmart submits current-directory native `ws4` models through the original differ and adds only physical-folder chrome around the list.

Smart Move is extension-owned because GMMP 4.2.0 has no verified physical `.spl` Move writer. The transaction is restricted to the configured Smart root, rolls back partial multi-moves and first scans native linked-Smart rules so an absolute-path dependency blocks a destructive move.

Both folder surfaces share `PlaylistFolderUiKit` and `PlaylistFolderMoveChrome`; visual behavior should not be reimplemented independently.

### Playlist Bridge

`PlaylistBridgeController` extends the original Smart-Playlist editor Link action. It obtains ordinary playlists from GMMP's native Playlist DAO, parses membership through GMMP's original playlist reader and intercepts only verified Bridge leaf compilation to return GMMP-native URI-IN predicates. Surrounding native Smart groups/order/limits remain owned by GMMP.

The V2 persisted representation temporarily substitutes boolean-neutral private native linked-`.spl` compatibility files during GMMP's original `ws4.t(File)` save. On the tested GMMP 4.2.0 runtime this keeps the Smart-Playlist usable when GoneSmart is disabled while preserving the meaning of remaining native rules.

### Flip and Track Auto-DJ

`QueueFlipController` reverses native queue/playlist playback order while preserving the selected native queue-entry identity and using GMMP's original queue/playback operations.

`TrackMixController` (user-facing **Track Auto-DJ**) starts a selected native song, isolates its exact queue entry through the verified native Room transaction, enables Auto-DJ and lets the existing GoneSmart recommendation path fill GMMP's configured Initial Size.

### Host UI / classloader boundary

GMMP and GoneSmart may load equivalent AndroidX classes through different classloaders. Host RecyclerView/AppCompat widgets therefore must not be assumed cast-compatible with module-side AndroidX types. Where required, GoneSmart observes verified original host callbacks reflectively and crosses the boundary using framework types such as `View` and `MotionEvent`.

## Update checking and distribution

`GitHubReleaseChecker` performs a lightweight asynchronous, read-only check against the latest published **stable** GitHub Release when the companion app starts. The Home tab compares the published version with `BuildConfig.VERSION_NAME` and shows newer/current/development/unavailable states. It never downloads or installs APKs. **Add to Obtainium** delegates subsequent signed APK updates to Obtainium's documented deep link.

## Tech stack

- Kotlin
- Android SDK / AndroidX
- Material Components
- libxposed API 102
- Gradle Kotlin DSL
- GitHub Actions
- ListenBrainz / MusicBrainz recommendation infrastructure
- Last.fm API

## Design goals

GoneSmart aims to keep:

1. **GMMP in control** of playback and queue lifecycle.
2. **External traffic bounded** rather than querying every queue track indefinitely.
3. **Final selection local** so recommendations never become streaming dependencies.
4. **Fallbacks explicit** so native GMMP Auto-DJ can still take over.
5. **Session context stronger than self-generated drift**.
6. **Future features modular** so GoneSmart can grow beyond Smart Auto-DJ.


## Native GMMP reuse and localization audit (28 September 2026)

The accepted Playlist folders feature builds its hierarchy from GMMP's native complete playlist dataset, creates playlists through its original scoped writer and physical directories through GMMP's original new-folder dialog, and deletes folders through original bulk-delete/Files workflows with a verified already-empty-directory cleanup. The one/many playlist Move uses a recoverable private stage around GMMP's original native delete worker and native index scanner; the potentially reusable existing-playlist save primitive has **not** been independently shown safe for cross-folder relocation. The normal Playlists and Add picker retain original localized titles, row models, fonts, native palette and native click flows; GoneSmart's only host-specific missing-native text concepts are Move and the virtual Other Locations node, centralized in one file and resolved using the current GMMP context. Smart DJ/Flip/Track Auto-DJ user-visible player notices now use native translated words and neutral symbols instead of hardcoded English/German. See the [full feature-by-feature localization and native API audit](NATIVE_GMMP_AUDIT.md) for precisely confirmed reuse, justified extension logic and unverified candidates. Branch acceptance is not a signed public release.
