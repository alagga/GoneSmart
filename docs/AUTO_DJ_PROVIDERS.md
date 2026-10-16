# Smart Auto-DJ provider roadmap

Status: 2026-10-16
Branch: `feature/auto-dj-providers`

## Goal

Improve Smart Auto-DJ coverage for very recent releases, electronic music and other tracks that are missing or weakly represented in the current recording-level similarity graphs.

GoneSmart must keep the existing architectural boundary:

- external services only provide recommendation signals;
- the final playable track must exist in the local GoneMAD Music Player library;
- provider work must not replace GMMP's native Auto-DJ queue/refill mechanics;
- network work stays off the UI thread and must fail soft;
- a weak or unavailable provider must not prevent other providers from contributing.

## Current providers

### ListenBrainz

Current production source. GoneSmart resolves recording identities and requests similar recordings.

Additional official ListenBrainz Labs datasets are promising for future fallback layers:

- `similar-artists`;
- `mlhd-similar-recordings`;
- `tag-similarity`;
- metadata/MBID mappings for Spotify, Apple Music and SoundCloud IDs.

These are preferable to scraping or private APIs because they keep the integration open and service-specific credentials minimal.

### Last.fm

Current production source. Recording-level `track.getSimilar` remains the preferred Last.fm signal.

The first experiment on this branch adds a bounded fallback for seeds where recording-level similarity is unavailable:

1. `artist.getSimilar` for at most three related artists;
2. `artist.getTopTracks` for at most eight tracks per related artist;
3. a score penalty plus rank decay so direct recording-level similarity remains stronger;
4. local-library matching and all existing GoneSmart ranking still happen afterwards.

Both Last.fm methods are official read APIs and require the existing application API key, but no Last.fm user login.

### Integration caveat for the first prototype

`GoneSmartModule.collectLastFmContributions()` deliberately tries multiple artist/title query variants. A fallback must not prematurely terminate that identity-resolution cascade when a later query variant could still resolve the real recording. The initial client-side prototype is therefore not considered final until this interaction has been verified and, if necessary, the fallback trigger is moved to the provider orchestration layer after all recording identities have been exhausted.

## Evaluated additional sources

| Source | Recommendation value | Authentication / credentials | Main issue | Recommendation |
| --- | --- | --- | --- | --- |
| ListenBrainz artist/tag datasets | High | No user login for Labs datasets | Need an artist-level candidate path and robust score normalization | **High priority** |
| Last.fm artist fallback | High | Existing API key; no user login | Must run only after track identity attempts are exhausted | **Implement first** |
| Last.fm user taste | Medium/High personalization | Public username is enough for several read methods | Optional profile setting and taste blending needed | **Good later personalization option** |
| Spotify official Web API | Potentially high catalogue/taste value | OAuth + developer application | Recommendations/Related Artists are unavailable to new/development use cases; 2026 Development Mode remains constrained | **Do not make a default provider** |
| Spotify private/WebPlayer APIs | Technically possible | Spotify session/token | Undocumented/private surface, brittle; SpotUI implementation cannot simply be copied into this MIT project | **Do not ship as normal provider** |
| YouTube Data API | Good catalogue search, poor recommendation primitive | API key/OAuth depending on operation | Official related-video recommendation parameter was removed; search is not a similarity engine | **Not useful as primary recommender** |
| YouTube Music / InnerTube | Potentially very high | Can work anonymously or with account context depending on method | Undocumented YouTube API; current YouTube developer policy forbids undocumented API use/reverse engineering | **Do not ship as normal provider** |
| SoundCloud | High for electronic/independent music | OAuth; application credentials | Official related-tracks endpoint exists, but a distributed Android client cannot safely keep an app client secret | **Revisit with a safe credential model** |
| TasteDive | Medium, artist/entity level | API key | Mostly artist/entity similarity rather than track-level recommendations; request limits | **Possible lightweight artist signal** |
| Apple Music | High catalogue/personalization | Developer token + Music User Token for personalized data | Signing/token lifecycle normally needs controlled developer credentials/backend | **Low priority for local module** |
| MusiMap / commercial music-intelligence APIs | Potentially high | OAuth client credentials | Backend secret/cost/vendor dependency | **Optional future backend provider** |
| MusicBrainz alone | Metadata only | Public API rules | Not a recommendation engine | **Use for identity, not recommendations** |

## Spotify / SpotUI notes

SpotUI is useful as an architectural reference, but not as code to copy:

- its current recommender builds a composite candidate set from Spotify data instead of relying on the deprecated Spotify Recommendations endpoint;
- some Spotify access in SpotUI uses WebPlayer/private GraphQL surfaces rather than a stable public recommendation API;
- SpotUI is GPL-3.0 while GoneSmart is MIT, so GoneSmart should independently implement ideas rather than copying GPL implementation code.

A future Spotify experiment should therefore be explicitly optional and isolated. If pursued, prefer user-owned Spotify developer credentials with PKCE over embedding a shared secret, and do not make Smart Auto-DJ dependent on Spotify availability.

## Login strategy

Do **not** add Spotify or YouTube Music login as the first step.

The lowest-friction personalization paths are:

1. optional Last.fm username, because public read endpoints such as recent/top artists do not require a user session;
2. optional ListenBrainz username/profile integration if user-specific recommendations are useful;
3. only later consider OAuth providers where the recommendation quality clearly justifies the token/credential complexity.

Provider login must always be optional: Smart Auto-DJ must remain functional with the non-personalized providers.

## Proposed implementation order

### Phase 1 — coverage without new accounts

- finish the Last.fm track-to-artist fallback without short-circuiting normal query variants;
- add diagnostics that distinguish `track` vs `artist-fallback` contributions without noisy per-result logging;
- add unit tests for fallback score normalization, request budget and deduplication;
- evaluate ListenBrainz `similar-artists` as the second artist-level signal.

### Phase 2 — provider abstraction

Introduce a small provider contract instead of growing provider-specific logic inside `GoneSmartModule`. The contract should expose at least:

- stable provider ID;
- track-similarity and/or artist-similarity capability;
- authentication requirement;
- request budget per seed;
- normalized confidence/similarity output;
- provider availability/failure state;
- optional experimental flag.

The aggregator and local-library matcher should continue to own cross-provider deduplication and ranking.

### Phase 3 — optional personalization

- Last.fm username taste signals;
- ListenBrainz user recommendation signals;
- measure whether personalization actually improves local-library hit rate before adding heavier OAuth integrations.

### Phase 4 — optional external providers

Only after the above is measurable, reconsider SoundCloud and Spotify under their then-current API/terms constraints.

## Quality metrics

Before merging a new provider into `main`, compare it against the current two-provider baseline using a seed set containing:

- newly released tracks;
- progressive house / festival EDM;
- remixes and extended mixes;
- collaborations / featured artists;
- obscure tracks with a known artist;
- mainstream control tracks.

For each seed record:

- whether the provider resolved the seed;
- raw recommendation count;
- local-library matched count;
- candidates surviving quality gates;
- provider latency/failure;
- final selected track and contributing providers.

The target is not simply more remote recommendations. The target is a higher **usable local candidate hit rate** without lowering recommendation quality or destabilizing native Auto-DJ behavior.


## Implemented provider blend on this branch

The branch now implements the first six provider/quality steps as one pipeline rather than isolated experiments:

- Last.fm exhausts recording identity/title variants and direct `track.getSimilar` evidence before its bounded similar-artist/top-track fallback is allowed to contribute.
- ListenBrainz keeps recording-level similarity first, then uses the official `similar-artists` Labs dataset as a lower-confidence artist signal that is expanded only into tracks already present in the local GMMP library.
- Normal GMMP playlists contribute through `LOCAL_PLAYLIST`. GoneSmart enumerates the already indexed `playlist_file_table` read-only, parses only supported M3U/M3U8 membership, and never mutates playlist files or database rows for recommendation purposes.
- Playlist membership is cached by canonical playlist path, modification time, file length and local-library fingerprint. Unchanged playlists are not reparsed on every refill.
- Playlist affinity uses saturating co-occurrence evidence. Small/focused playlists carry more weight than very large catch-all playlists, and the same pair appearing in multiple independent playlists becomes stronger evidence.
- Smart Playlists are deliberately excluded from affinity today. A future opt-in may add them only as a substantially weaker signal because rule-based membership can be very broad.
- The aggregator deduplicates provider+seed evidence as before, then applies a small bounded bonus when independent providers agree on the same logical track. Local Playlist Affinity still passes the same local matching, rating, duplicate and minimum-quality gates as network recommendations.
- Provider metrics now summarize contribution counts, multi-provider corroboration and playlist cache/membership/seed-hit counts without per-result logging spam.
- Local Playlist Affinity remains usable without network; network-only providers are skipped while offline before any HTTP request is attempted.

### Deferred sources

SoundCloud stays on the later-evaluation list because its related-track signal is attractive for electronic/new music, but a public GoneSmart build must not embed a shared confidential client secret. Spotify, YouTube and YouTube Music remain evaluated but unaccepted directions rather than committed providers.
