# Smart Auto-DJ provider roadmap

Status: **integrated into `0.5.0-rc.1` / feature-complete** (2026-10-17)
Origin branch: `feature/auto-dj-providers`

## Goal

Improve Smart Auto-DJ coverage for very recent releases, electronic music and other tracks that are missing or weakly represented in public recording-level similarity graphs, while also making better use of the user's own GMMP library structure.

GoneSmart keeps the existing architectural boundary:

- external services only provide recommendation signals;
- the final playable track must exist in the local GoneMAD Music Player library;
- provider work must not replace GMMP's native Auto-DJ queue/refill mechanics;
- network work stays off the UI thread and fails soft;
- a weak or unavailable provider must not prevent other providers from contributing;
- provider priority is expressed through ranking weights rather than artificial request ordering.

## Current providers

### Local Playlist Affinity

Normal GMMP playlists are a first-class local recommendation source. GoneSmart reads the playlist catalogue and supported M3U/M3U8 membership without modifying GMMP's database or playlist files.

For a seed track, other local-library tracks that occur in the same normal playlists become candidates. Multiple independent shared playlists strengthen the relationship. Playlist membership and the local path index are cached so unchanged playlists do not need to be reparsed on every refill.

Smart Playlists are deliberately excluded today. Rule-based membership can be much broader than a hand-curated normal playlist, so a future opt-in may use them only as a substantially weaker signal.

Playlist-size weighting is configurable:

- **Off** — all normal playlists contribute equally regardless of size;
- **Balanced** — the default; focused playlists count more while large playlists remain useful;
- **Strong** — large catch-all playlists are reduced more aggressively.

### ListenBrainz

GoneSmart resolves recording identities and requests similar recordings first. If that recording graph is not productive, it can use the official ListenBrainz Labs `similar-artists` data as a lower-confidence signal and expand those artists only into tracks already present in the local GMMP library.

Sparse/new releases sometimes have no recording match good enough to use as a track identity even though ListenBrainz recording search already returns a trustworthy artist-credit MBID. GoneSmart can now recover that artist identity conservatively and use it only for the artist fallback; the rejected recording row itself never becomes a recommendation.

Additional official ListenBrainz Labs datasets remain interesting for future experiments, including `mlhd-similar-recordings`, tag similarity and metadata/MBID mappings.

### Last.fm

Recording-level `track.getSimilar` remains the preferred Last.fm signal. GoneSmart exhausts its bounded artist/title identity variants before allowing the lower-confidence artist fallback:

1. `artist.getSimilar` for related artists;
2. `artist.getTopTracks` for a bounded number of tracks per related artist;
3. a score penalty and rank decay so direct recording similarity remains stronger;
4. normal local-library matching, rating, duplicate and quality gates afterwards.

The existing application API key is sufficient; no Last.fm user login is required.

## Provider execution and weighting

GoneSmart keeps one deterministic recommendation-pool fill per queue session, but independent network providers run in parallel inside that fill. ListenBrainz no longer has to finish before Last.fm can start (or vice versa).

Each contribution carries a provider weight before aggregation. Existing provider+seed deduplication still runs first, and independent providers confirming the same logical track receive only a small bounded corroboration bonus.

The companion app exposes two recommendation modes:

### Smart — default

Smart is the recommended mode and lets GoneSmart own conservative provider defaults. The current policy is:

- Local Playlists: `×1.00`;
- ListenBrainz: `×1.00`;
- Last.fm: `×0.95`;
- playlist-size weighting: **Balanced**;
- all stable providers enabled.

Because these values belong to the Smart policy rather than user-stored tuning, GoneSmart can refine the defaults in a future update without requiring existing users to retune their settings.

### Custom

Custom mode lets the user:

- enable or disable Local Playlists, ListenBrainz and Last.fm independently, while always keeping at least one recommendation source enabled;
- set each enabled provider's ranking weight from `×0.25` to `×1.50`;
- choose Off, Balanced or Strong playlist-size weighting when Local Playlists is enabled; the playlist-size control is disabled when that source is off.

Request order is intentionally not configurable. Network providers run in parallel; weights express preference without adding avoidable latency.

Provider-related setting changes apply live and invalidate the previous recommendation pool so the next refill follows the new policy.

## Evaluated additional sources

| Source | Recommendation value | Authentication / credentials | Main issue | Recommendation |
| --- | --- | --- | --- | --- |
| Last.fm user taste | Medium/High personalization | Public username is enough for several read methods | Optional profile setting and taste blending needed | **Good later personalization option** |
| Spotify official Web API | Potentially high catalogue/taste value | OAuth + developer application | Recommendations/Related Artists are unavailable to new/development use cases; 2026 Development Mode remains constrained | **Do not make a default provider** |
| Spotify private/WebPlayer APIs | Technically possible | Spotify session/token | Undocumented/private surface, brittle; SpotUI implementation cannot simply be copied into this MIT project | **Do not ship as normal provider** |
| YouTube Data API | Good catalogue/search and freshness value | API key/OAuth depending on operation | Official related-video recommendation parameter was removed; playlist co-occurrence requires expensive discovery/search quota | **Not a primary recommender** |
| YouTube Music / InnerTube | Potentially very high | Can work anonymously or with account context depending on method | Undocumented YouTube API; current YouTube developer policy forbids undocumented API use/reverse engineering | **Do not ship as normal provider** |
| SoundCloud | High for electronic/independent music | OAuth; application credentials | Official related-tracks endpoint exists, but a distributed Android client cannot safely keep an app client secret | **Revisit with a safe credential model** |
| TasteDive | Medium, artist/entity level | API key | Mostly artist/entity similarity rather than track-level recommendations; request limits | **Possible lightweight artist signal** |
| Apple Music | High catalogue/personalization | Developer token + Music User Token for personalized data | Signing/token lifecycle normally needs controlled developer credentials/backend | **Low priority for local module** |
| MusiMap / commercial music-intelligence APIs | Potentially high | OAuth client credentials | Backend secret/cost/vendor dependency | **Optional future backend provider** |
| MusicBrainz alone | Metadata only | Public API rules | Not a recommendation engine | **Use for identity, not recommendations** |

## Spotify / SpotUI notes

SpotUI is useful as an architectural reference, but not as code to copy:

- its recommender builds a composite candidate set from Spotify data instead of relying on the deprecated Spotify Recommendations endpoint;
- some Spotify access in SpotUI uses WebPlayer/private GraphQL surfaces rather than a stable public recommendation API;
- SpotUI is GPL-3.0 while GoneSmart is MIT, so GoneSmart should independently implement ideas rather than copying GPL implementation code.

A future Spotify experiment should therefore be explicitly optional and isolated. If pursued, prefer user-owned Spotify developer credentials with PKCE over embedding a shared secret, and do not make Smart Auto-DJ dependent on Spotify availability.

## Login strategy

Do **not** add Spotify or YouTube Music login as a default dependency.

Lower-friction personalization paths remain:

1. optional Last.fm username, because public read endpoints such as recent/top artists do not require a user session;
2. optional ListenBrainz username/profile integration if user-specific recommendations prove useful;
3. only later consider OAuth providers where measured recommendation quality clearly justifies token/credential complexity.

Provider login must always remain optional: Smart Auto-DJ must work with the non-personalized providers and local signals.

## Implemented blend

The branch now implements the first local/provider quality pass as one coordinated pipeline:

- Last.fm direct recording evidence before artist fallback;
- ListenBrainz direct recording evidence before similar-artist fallback, including conservative artist-identity recovery for sparse recording matches;
- Local Playlist Affinity with cached read-only normal-playlist membership;
- configurable playlist-size weighting;
- Smart and Custom provider policies in the companion app;
- parallel ListenBrainz/Last.fm execution;
- configurable provider enablement and ranking weights;
- bounded cross-provider corroboration;
- the same local matching, preference, rating, duplicate and minimum-quality gates for every source;
- local-only recommendation preparation while offline;
- concise provider and latency diagnostics.

### Deferred sources

SoundCloud stays on the later-evaluation list because its related-track signal is attractive for electronic/new music, but a public GoneSmart build must not embed a shared confidential client secret. Spotify, YouTube and YouTube Music remain evaluated but unaccepted directions rather than committed providers.

## Quality metrics

For future provider/ranking changes, compare the blend against the accepted 0.5.0 provider baseline with seeds containing:

- newly released tracks;
- progressive house / festival EDM;
- remixes and extended mixes;
- collaborations / featured artists;
- obscure tracks with a known artist;
- mainstream control tracks.

For each seed record:

- whether each enabled provider resolved the seed;
- raw contribution count;
- local-library matched count;
- candidates surviving quality gates;
- provider-round and playlist-affinity latency;
- final selected track and contributing providers.

The target is not simply more remote recommendations. The target is a higher **usable local candidate hit rate** with lower latency and without destabilizing native GMMP Auto-DJ behavior.

### Controlled variety and cache/runtime follow-up

- Final local matches keep their real ranking scores. GoneSmart only randomizes order inside bounded near-equal score buckets, so obvious score gaps stay deterministic. Smart uses Balanced; Custom exposes Off, Balanced and High.
- In Custom mode a disabled provider also disables and visually mutes its ranking-weight slider because that weight is inactive.
- ListenBrainz recording search currently does not return artist-credit MBIDs. The artist fallback therefore resolves the MBID lazily from the selected recording through the official recording-MBID lookup endpoint; direct recording similarity does not pay this extra request.
- The GMMP library cache is cheaply revalidated before a repeated full-library read, with a bounded hard refresh retained as a safety net.

## Staged artist rescue and partial fills

GoneSmart treats artist-level provider expansion as a rescue stage. Playlist Affinity and direct track/recording recommendations are collected and matched against the local GMMP library first. Artist fallback is requested only when the number of unique local direct candidates is below a reserve derived from GMMP's configured Auto-DJ demand: the larger of roughly two times the visible demand and 60% of the internal recommendation-pool target, capped by that pool target. This deliberately favors one somewhat richer first pass over several closely spaced provider rounds.

If fewer suitable GoneSmart tracks exist than GMMP requests in a refill, GoneSmart returns only those suitable tracks. It does not preserve untouched native/random rows to pad the request. A partial successful fill is treated as temporary scarcity: while useful tracks remain in that partial pool, low-water background top-up does not immediately call the online providers again. Once those tracks are consumed, a fresh refill is allowed. Visible multi-track underfills may show one concise status message when status messages are enabled. The configured native fallback remains relevant when there are zero suitable GoneSmart tracks.

`Prevent Version Duplicates` groups the classic base variants Original, Radio, Extended and Club Mix. Remixes remain independent by default. A separate `Prevent Remix Duplicates` option is off by default; when enabled, it rejects a remix if another version of the same normalized artist + base title is already in duplicate context, and symmetrically rejects a non-remix if a remix of that song is already present. Edit, Bootleg, Mashup, Acoustic and Live variants keep their own version families unless a future policy explicitly broadens them.

## Feature-completion audit

The provider work is feature-complete and integrated into the `0.5.0-rc.1` release candidate. Stable `0.5.0` still depends on prerelease validation of the combined tree.

Accepted behavior on GMMP 4.2.1 includes:

- Local Playlist Affinity, ListenBrainz and Last.fm blended through the same local quality pipeline;
- Smart and Custom provider modes with live enablement/weights;
- at least one Custom source always remaining enabled;
- playlist-size weighting disabled when Local Playlists is disabled;
- bounded corroboration and controlled near-tie variety;
- staged artist rescue only when direct local coverage is insufficient;
- local/offline playlist recommendations and session-pool continuation;
- cheap library-cache revalidation and cached playlist membership/indexes;
- partial smart fills without random native padding;
- exact native GMMP Auto-DJ refill ownership retained.

### Compatibility / hook audit

The recommendation feature does not require a new guessed state-changing GMMP writer. Its GMMP-facing read boundaries are deliberately semantic/dynamic:

- `NativeAutoDjAccessorResolver` identifies the live Auto-DJ object from structural ownership evidence rather than an accessor's obfuscated name.
- `GmmpReadOnlySql` resolves the live GMMP Room database and compatible Cursor query boundary structurally, caches the proved binding, and fails closed on ambiguity.
- `GmmpPlaylistCatalogReader` discovers the playlist path/URI column from actual data instead of assuming an R8 member name.
- `GmmpLibraryReader` keeps historical TrackDao names only as an optional tested-version fast path. Failure to locate that fast path now falls directly to the structural read-only Room/Cursor route, so an R8 field rename cannot by itself disable recommendations.

Future GMMP versions remain unverified until the normal compatibility workflow is run, but the provider feature no longer depends on one exact obfuscated library-DAO field in order to function.

### Translation / wording audit

The GoneSmart companion app is intentionally English-only. Provider names/settings/help text are GoneSmart-owned companion UI and therefore do not need a host-language translation table. The recommendation feature injects no new provider-specific labels into GMMP itself. Existing GMMP-facing actions continue to use GMMP's own localized resources; if a required host term is unavailable, the injected action is omitted or kept language-neutral rather than inventing arbitrary English inside GMMP.

### Code hygiene / logging audit

The accepted provider path no longer emits the development-era per-candidate merged/local recommendation dumps. Normal Logcat output keeps compact provider counts, policy/rescue/fallback decisions, cache behavior, controlled-variety metrics, resolver failures and latency summaries without flooding normal operation. Broad compatibility inventories remain disabled/gated on the accepted GMMP version.

The companion Logs tab is deliberately higher-level: provider/hook unavailability, fallback/no-match/offline states and other support-relevant transitions remain visible, while routine successful pool deliveries refresh Smart-DJ status without appending one history row per refill. The Logs/Help copy points to the current detailed tag family, including the audio-profile tags added by the combined 0.5.0 tree, and recommends copying the companion timeline before collecting filtered Logcat for deeper diagnosis.

The closeout pass also verifies that temporary probes/helper workflows are absent from the release tree, removes obsolete always-on test/feature gates and dead logging helpers, and keeps compatibility fallback machinery for future GMMP updates only when it is inactive/cheap until an unknown or failing boundary actually needs it.

## 0.6 provider outlook

SoundCloud is the most credible next online-provider experiment because the documented SoundCloud API exposes related tracks. A release implementation still needs a safe distributed-client credential/authentication model; GoneSmart must not embed a shared client secret.

Smart Playlists are also a candidate for an optional, deliberately weaker local affinity signal.

Spotify, YouTube and YouTube Music are unlikely normal providers for now. Spotify's current developer-access model and recommendation-endpoint restrictions are unsuitable for a broadly distributed independent client; YouTube no longer exposes the former documented related-video search path; and YouTube Music has no suitable public recommendation API. GoneSmart will not make undocumented/private WebPlayer, GraphQL or InnerTube endpoints a normal dependency simply to add those providers.
