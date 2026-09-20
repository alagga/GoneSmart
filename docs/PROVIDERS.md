# Recommendation providers

GoneSmart currently combines two external recommendation sources and performs the final selection locally against the user's GoneMAD Music Player library.

## ListenBrainz

[ListenBrainz](https://listenbrainz.org/) is used for MusicBrainz-backed recording lookup and similar-recording recommendations.

GoneSmart sends only the seed metadata needed for recommendation lookup. The full GMMP library is not uploaded to ListenBrainz.

## Last.fm

[Last.fm](https://www.last.fm/) contributes similar-track recommendations through the Last.fm API.

GoneSmart currently uses the public `track.getSimilar` style of request:

- an application **API key** is required;
- Last.fm user authentication is not required for this method;
- GoneSmart does **not** use a Last.fm shared secret or user session;
- release builds receive the application key at build time through the `LASTFM_API_KEY` environment/GitHub secret.

An API key embedded in an Android APK can ultimately be extracted from the client. Keeping it in a GitHub Actions secret prevents accidental publication in source history, but it is not a DRM mechanism.

Last.fm usage remains subject to the current [Last.fm API Terms of Service](https://www.last.fm/api/tos). Those terms include attribution requirements and additional restrictions for public/commercial use. The maintainer should review the current terms whenever preparing a public release.

## Local-only final selection

Provider responses are recommendation signals, not playable media. GoneSmart normalizes and merges those signals, matches them against GMMP's local database, and only selects a track that exists locally.

GoneSmart does not stream music from ListenBrainz or Last.fm.
