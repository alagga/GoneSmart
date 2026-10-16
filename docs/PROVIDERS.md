# Recommendation providers

GoneSmart combines external recommendation sources and performs the final selection locally against the user's GoneMAD Music Player library.

The current production sources are ListenBrainz and Last.fm. Experimental provider work is tracked in [`AUTO_DJ_PROVIDERS.md`](AUTO_DJ_PROVIDERS.md).

## ListenBrainz

[ListenBrainz](https://listenbrainz.org/) is used for MusicBrainz-backed recording lookup and similar-recording recommendations.

GoneSmart sends only the seed metadata needed for recommendation lookup. The full GMMP library is not uploaded to ListenBrainz.

ListenBrainz Labs also exposes artist-, tag- and additional recording-similarity datasets. These are being evaluated as future fallback signals for seeds that do not resolve well at recording level.

## Last.fm

[Last.fm](https://www.last.fm/) contributes recommendation signals through the Last.fm API.

Recording-level `track.getSimilar` remains the preferred Last.fm path.

On the `feature/auto-dj-providers` branch, GoneSmart is also evaluating a bounded artist fallback for seeds that have no usable recording-level similarity:

1. `artist.getSimilar` supplies a small set of related artists;
2. `artist.getTopTracks` supplies concrete track candidates for those artists;
3. fallback candidates receive a score penalty and rank decay so direct track similarity stays stronger.

All of these read methods use the same application API key and do not require Last.fm user authentication.

- an application **API key** is required;
- GoneSmart does **not** require a Last.fm shared secret or user session for the current recommendation calls;
- release builds receive the application key at build time through the `LASTFM_API_KEY` environment/GitHub secret.

An API key embedded in an Android APK can ultimately be extracted from the client. Keeping it in a GitHub Actions secret prevents accidental publication in source history, but it is not a DRM mechanism.

Last.fm usage remains subject to the current [Last.fm API Terms of Service](https://www.last.fm/api/tos). Those terms include attribution requirements and additional restrictions for public/commercial use. The maintainer should review the current terms whenever preparing a public release.

## Local-only final selection

Provider responses are recommendation signals, not playable media. GoneSmart normalizes and merges those signals, matches them against GMMP's local database, and only selects a track that exists locally.

GoneSmart does not stream music from recommendation providers.
