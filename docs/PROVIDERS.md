# Smart Auto-DJ recommendation providers

The planned GoneSmart 0.5 recommendation blend combines three sources while keeping every final selection local to the GoneMAD Music Player library.

## Current sources

### Local Playlist Affinity

Normal GMMP playlists are a first-class personal recommendation source. GoneSmart reads them **read-only** and treats tracks that repeatedly occur together as evidence that they fit together musically.

- Only normal playlists are used today; Smart Playlists are intentionally excluded from the default signal.
- Independent shared playlists reinforce a relationship.
- Focused playlists count more than very large catch-all lists when **Large-playlist weighting** is Balanced or Strong.
- Playlist membership and local path indexes are cached and cheaply reused.
- The source works offline because it uses only local GMMP data.

### ListenBrainz

GoneSmart prefers recording-level similarity. If recording-level evidence does not produce enough usable local coverage, a lower-confidence artist rescue can resolve a reliable artist identity, request similar artists, and expand those artists only into tracks already present in the local library.

### Last.fm

GoneSmart prefers `track.getSimilar`. When direct track evidence is insufficient, the bounded rescue path can use related artists and a small number of their top tracks. Rescue evidence is deliberately penalized so a direct track match remains stronger.

No Last.fm user login is required for the current provider.

## How the blend is ranked

Each contribution is scored using provider similarity, seed weight, identity confidence and the user/provider weight. Duplicate rows from the same provider and seed do not multiply a song's score. If independent providers confirm the same logical song, GoneSmart applies only a small bounded corroboration bonus.

This means a Local Playlist-only candidate can still win when it is strong, while a track supported by both a playlist relationship and an online provider gains additional evidence.

## Smart and Custom modes

**Smart** is the default. GoneSmart owns conservative defaults and can refine them in future releases without requiring users to retune stored values:

- Local Playlists: `×1.00`
- ListenBrainz: `×1.00`
- Last.fm: `×0.95`
- Large-playlist weighting: **Balanced**
- Recommendation variety: **Balanced**

**Custom** lets users enable/disable the three sources, set ranking weights from `×0.25` to `×1.50`, choose playlist-size weighting, and choose Off/Balanced/High controlled variety. At least one source must remain enabled. The playlist-size control is unavailable when Local Playlists is disabled because it has no effect on the online providers.

## Staged rescue instead of repeated broad searches

GoneSmart first collects Local Playlist Affinity plus direct recording/track recommendations and matches them to the local library. Artist-level provider expansion is a **rescue stage**, not a normal tax on every refill. It runs only when unique direct local coverage is below a bounded reserve derived from GMMP's visible Auto-DJ demand and the internal recommendation-pool target.

A partially populated GoneSmart pool is valid. GoneSmart never pads a successful partial smart result with random untouched native rows. Native GMMP fallback remains available for the zero-suitable-track case according to the user's fallback setting.

## Controlled variety

GoneSmart never adds global random score noise. Balanced/High variety only changes ordering inside bounded groups of near-equal final ranking scores. Clear score gaps remain deterministic.

## Privacy and offline behavior

The local GMMP library and playlist membership stay on-device. Online providers receive only the seed metadata needed for their lookup. Local Playlist Affinity can create new recommendations offline, and an already valid recommendation pool can continue to be consumed offline.

## Compatibility design

Recommendation reads deliberately avoid making obfuscated R8 names a requirement:

- the live Auto-DJ instance is resolved by structural/semantic evidence;
- the read-only GMMP database/query boundary is resolved structurally and cached;
- playlist catalogue path/URI columns are identified from their data shape;
- the historical TrackDao field/method mapping is only an optional tested-version fast path; the structural read-only Cursor path remains sufficient if it moves.

GMMP still owns native Auto-DJ queue insertion and playback. Recommendation providers never write directly to GMMP's database.

## Future sources

### SoundCloud

SoundCloud is a credible **0.6 investigation** because its official public API exposes related-track recommendations. Shipping it still requires a safe authorization/credential model for a distributed Android module: GoneSmart must not embed a shared client secret, and current SoundCloud application registration/authentication requirements add deployment friction.

### Smart Playlists

A future opt-in may allow Smart Playlists to contribute a deliberately weaker local affinity signal. Dynamic/rule-based membership can be much broader than a hand-curated normal playlist, so it must not be weighted like normal Playlist Affinity by default.

### Spotify, YouTube and YouTube Music

These are **unlikely as normal providers for now**:

- Spotify's current Web API access model is unsuitable for a broadly distributed independent client: Development Mode is intentionally restricted, and new development use cases no longer have access to the former Recommendations/Related Artists functionality. Depending on private WebPlayer/GraphQL endpoints would be brittle and unsupported.
- The official YouTube Data API no longer exposes the former `relatedToVideoId` search path, so there is no direct stable related-track equivalent. Reconstructing recommendations indirectly from search/playlist co-occurrence would add quota and quality trade-offs.
- YouTube Music has no public recommendation API suitable for this use case. InnerTube/private endpoints would be undocumented, brittle and conflict with the goal of a robust normal provider.

GoneSmart will prefer documented provider APIs with a stable public-client authorization model over reverse-engineered/private integrations.
