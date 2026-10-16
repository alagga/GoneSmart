# GoneSmart roadmap

This roadmap describes the current direction for upcoming GoneSmart releases. Planned items may change as development and GMMP compatibility work progresses.

## Ideas for 0.5.0

### Smarter recommendation blend

Continue strengthening the existing Last.fm and ListenBrainz signals and combine them with local Playlist Affinity: tracks that repeatedly occur together in the user's normal GMMP playlists can reinforce one another even when a new release is not yet represented well by public recommendation graphs. Independent provider agreement receives a bounded ranking boost while every final result still has to resolve to the local GMMP library.

SoundCloud remains an interesting later provider to investigate, especially for electronic music, remixes and new releases, but it is not committed until a safe public-client credential model is practical. Spotify, YouTube and YouTube Music remain evaluated examples rather than planned integrations.

### Bluetooth device audio profiles

Add per-device audio profiles for Bluetooth playback devices.

GoneSmart should be able to automatically use different GMMP equalizer and effects settings depending on which Bluetooth headphones, speakers, car audio system or other device is currently connected.

For example, connecting one pair of headphones could activate one EQ/effects profile, while connecting a car stereo could automatically switch to another.

## Later ideas

- Optionally let Smart Playlists contribute a deliberately much weaker affinity signal; broad/dynamic Smart Playlist membership must never be treated like a hand-curated normal playlist by default.
- Revisit SoundCloud recommendations if authentication/credential handling becomes suitable for a distributed Android module.
- Additional smart-library, playback and GMMP workflow extensions can be considered after the 0.5.0 work is stable.
