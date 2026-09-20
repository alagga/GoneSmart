# GoneSmart v0.3.2

The first public GoneSmart release turns GMMP Auto-DJ into a session-aware recommendation system while keeping GMMP in control of playback and queue management.

## Highlights

- Smart Auto-DJ recommendations from **ListenBrainz + Last.fm**.
- Local-library matching: GoneSmart only selects tracks already present in GMMP.
- Session-aware seed selection with stronger weighting for current/recent user-selected music.
- Real recommendation pool so consecutive Auto-DJ selections do not require a full provider round every time.
- Exactly one **broader second search** when the normal provider pass produces no usable local candidates.
- Artist fallback for cases where the exact externally recommended recording is missing locally.
- Song-family duplicate protection for Original / Radio / Extended / Club variants.
- Rating controls: Minimum rating, Smart rating, Rating fallback, higher-rated tie-breaks and 0.5-star exclusion.
- Optional studio-over-live preference, era matching and recently-added preference.
- Offline-aware behavior and configurable fallback to regular GMMP Auto-DJ.
- Green/red sparkle indicator on GMMP's Auto-DJ headphones icon.
- Companion app with live settings, module/GMMP status, runtime logs, FAQ and Restart GMMP shortcut.

## Compatibility

- Tested with **GoneMAD Music Player 4.2.0**.
- Rooted setup: **JingMatrix Vector v2.2+** / libxposed API 102.
- Android 8.0+.
- LSPatch v1.2 instructions are included as an **experimental / less-tested** no-root path.

## Known limitations

- GoneSmart currently hooks obfuscated GMMP internals, so future GMMP versions may require compatibility updates.
- LSPatch has not yet been validated as thoroughly as the rooted Vector setup.
- Provider availability and metadata quality can affect recommendation coverage.

## Installation

Download `GoneSmart-v0.3.2.apk`, install it, enable GoneSmart for `gonemad.gmmp` in Vector/LSPosed, force-stop GMMP, then reopen it and enable GMMP Auto-DJ.

See the repository README for full rooted and LSPatch instructions.
