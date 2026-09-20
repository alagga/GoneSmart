# GoneSmart FAQ

## What does GoneSmart replace?

Only GMMP Auto-DJ's track-selection step. GMMP continues to control playback, the queue and when Auto-DJ requests more songs.

## Where do recommendations come from?

GoneSmart combines similar-music data from ListenBrainz and Last.fm, then matches those suggestions against the local GMMP database. The providers do not decide which file is played; the final selection must be a local GMMP track.

## How many queue tracks are used?

GoneSmart uses up to five representative provider seeds. The current and recent tracks receive the strongest weight. The broader session still informs local ranking and drift control without causing an API request for every item in a long queue.

## What happens if the normal search finds nothing locally?

Exactly one broader provider pass is performed. Last.fm requests a wider similar-track set and ListenBrainz explores a wider set of plausible recording identities. If the broader pass also yields no usable local candidate, GoneSmart stops broadening and follows the configured fallback rules.

## Why not keep searching indefinitely?

Unbounded retries would increase API traffic, battery/network use and Auto-DJ latency without guaranteeing a local match. One bounded broad pass gives sparse libraries a second chance without turning every difficult seed into a long search loop.

## How does the recommendation pool work?

A successful pipeline produces multiple ranked local track IDs. GMMP then consumes those selections as Auto-DJ needs them. A refill is prepared only when the pool becomes low, the queue session changes, or recommendation-related settings invalidate the old pool.

## Minimum rating vs. Smart rating

- **Minimum rating** is a fixed 0–5 star floor in 0.5-star steps.
- **Smart rating** derives a dynamic floor from the median rating of the current recommendation context.
- If both are enabled, the stricter threshold wins.

## What does Rating fallback do?

When hard Minimum/Smart rating filters are the reason no suitable track remains, GoneSmart can retry the same recommendation candidates without those two filters. Other rules, including Exclude 0.5-star tracks, still apply. A status message is shown when this fallback is used.

## What does the native GMMP fallback do?

If GoneSmart cannot supply a smart track and native fallback is enabled, regular GMMP Auto-DJ is allowed to choose. The player sparkle turns red while fallback/problem state is active.

## What happens offline?

A still-valid pool from the current session can continue offline. Starting a different queue invalidates the old session pool. If no cached smart candidate remains, native GMMP fallback can take over when enabled.

## What does the sparkle mean?

- Green: GMMP Auto-DJ is active and GoneSmart is ready.
- Red: GoneSmart cannot currently provide smart selection, or regular GMMP Auto-DJ fallback is active.
- None: GoneSmart is disabled or GMMP is in Normal/Shuffle rather than Auto-DJ.

## Is my entire library sent to Last.fm or ListenBrainz?

No. GoneSmart sends the seed metadata required for recommendation lookup. The GMMP library is loaded and matched locally on-device.

## Why can another GMMP version break GoneSmart?

GoneSmart currently hooks obfuscated GMMP internals. Internal class, method or field names can change between GMMP releases even if the visible feature behaves the same. Version 4.2.0 is the current tested target.

## Does changing settings require a restart?

Normally no. Settings are sent to the running GMMP process and recommendation-related changes invalidate the existing pool. Restart GMMP is mainly needed after module updates or when troubleshooting hook state.
