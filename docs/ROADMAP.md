# GoneSmart roadmap

This roadmap describes the current direction for upcoming GoneSmart releases. Planned items may change as development and GMMP compatibility work progresses.

## 0.5.0 integration plan

### Smarter recommendation blend — feature complete

The recommendation-provider workstream is feature-complete and ready to be integrated into the future 0.5.0 release once the other planned 0.5 workstreams are ready.

It adds Local Playlist Affinity alongside ListenBrainz and Last.fm, Smart/Custom provider policy, per-provider weights, bounded cross-provider corroboration, controlled near-tie variety, staged artist rescue, local/offline recommendations, cached large-library/playlist reads, and provider/runtime diagnostics. Every final playable result still has to exist in the local GMMP library.

### Bluetooth device audio profiles

The 0.5.0 plan also includes per-device audio profiles so different Bluetooth headphones, speakers, car audio systems or the no-Bluetooth state can use different GMMP equalizer/effects overrides while retaining a native/default option for each setting.

This work is developed separately and is not considered complete merely because the recommendation branch is complete.

### GMMP 4.2.1 playlist compatibility

The 0.5.0 integration plan also includes the separate Playlist Link / playlist-sync compatibility fixes for GMMP 4.2.1. That workstream remains separate until it is accepted on its own branch.

## 0.6.0 outlook

### SoundCloud recommendations

SoundCloud is the strongest additional online-provider candidate currently being considered because its official API exposes related-track recommendations and is particularly interesting for electronic music, remixes and newer independent releases. Before implementation is accepted, GoneSmart needs a safe authorization/credential design for a distributed Android client and must not embed a shared confidential client secret.

### Optional Smart-Playlist affinity

Smart Playlists may become an opt-in local recommendation source with deliberately lower weight than hand-curated normal playlists. Their rule-based membership can be much broader and more dynamic, so they should never automatically count as equivalent evidence.

## Unlikely provider directions for now

- **Spotify:** not planned as a normal provider while current Web API Development Mode remains unsuitable for broad distribution and new development use cases lack the former Recommendations/Related Artists functionality. Private WebPlayer/GraphQL APIs would be too brittle for GoneSmart's compatibility goals.
- **YouTube:** the documented Data API no longer exposes the former related-video search path, so there is no stable direct related-track recommender. Search/playlist-derived approximations would be indirect and quota-heavy.
- **YouTube Music:** there is no suitable public recommendation API. An InnerTube/private integration would be undocumented and too experimental/brittle for a normal GoneSmart provider.

These directions can be reevaluated if the providers expose a stable documented API and practical public-client authorization model in the future.

## Later ideas

Additional smart-library, playback and GMMP workflow extensions can be considered after the 0.5.0 work is integrated and stable.
