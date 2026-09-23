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

## Optional UI integration: multi-playlist selection

`PlaylistMultiSelectController` is an independently enabled feature in the **UI** tab. It intercepts GMMP 4.2.0's native Add to Playlist row actions through `GoneSmartModule`, identifies destinations by the native playlist path rather than recycled RecyclerView holders, and dispatches each destination through GMMP's own `io3.r(Context, ie0)` operation.

It preserves the original source selection and single-playlist behavior. The multi-add batch suppresses only duplicate native navigation and per-destination success Toasts, issuing one aggregate result instead. GMMP's own Aesthetic color observables and localized resources provide theme-sensitive selection highlights and interface text. These hooks are version-sensitive and require on-device compatibility checks when GMMP changes.

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
