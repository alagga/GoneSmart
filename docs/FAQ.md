# GoneSmart FAQ

## Is GoneSmart only an Auto-DJ project?

No. GoneSmart is meant to extend GMMP with smart and quality-of-life features in general. Smart Auto-DJ is the current main focus and the first major feature.

Within Smart Auto-DJ, GoneSmart replaces only GMMP's track-selection step. GMMP continues to control playback, the queue and when Auto-DJ requests more songs.

## Where do recommendations come from?

GoneSmart combines similar-music data from ListenBrainz and Last.fm, then matches those suggestions against the local GMMP database. The providers do not decide which file is played; the final selection must be a local GMMP track.

## Is Smart Auto-DJ only for electronic music?

No, but the current matching heuristics are especially tuned for electronic-music libraries. That is where naming differences such as Original Mix, Radio Edit, Extended Mix, Club Mix, Remix, Rework, Edit, featured artists and multi-artist credits show up particularly often.

Other genres should work too. If rock, metal, hip-hop, pop, classical or another library exposes bad matching behavior, please open a feature request with a few representative artist/title/version examples and explain which tracks should or should not be treated as the same song family.

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

The **Rating fallback** switch is available only when Minimum rating is above zero or Smart rating is enabled. If neither threshold is active, the switch is greyed out and reset to off. When hard Minimum/Smart rating filters are the reason no suitable track remains, GoneSmart can retry the same recommendation candidates without those two filters. Other rules, including Exclude 0.5-star tracks, still apply. A status message is shown when this fallback is used.

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

**Home → Settings** explains the shared live behavior for both feature tabs. No restart is needed for ordinary **Smart DJ** or **UI** setting changes: both are sent to the running GMMP process. Recommendation-related changes invalidate the current pool so the next refill uses the new settings without interrupting playback. UI tweaks can be switched on or off independently of Smart DJ. Restart GMMP after installing or updating the module, changing its scope, or when troubleshooting hook state.

## How do I add several tracks to several playlists?

Enable **Multi-playlist selection** under GoneSmart's **UI** tab. In GMMP's Add to Playlist dialog, long-press the first destination and tap further destinations. The confirmation checkmark adds all original source files to each selected playlist using GMMP's own playlist writer. The confirmation appears once, with the number of files and successfully updated destinations. Press Back to cancel selection without dismissing the picker.

The selected rows and action bar follow GMMP's dynamic colors. GoneSmart reuses GMMP's own localized strings for this feature; another GMMP language does not require a separate GoneSmart translation. Normal taps and GMMP's create-playlist plus button remain unchanged.

## Can I use the UI feature without Smart DJ?

Yes. Multi-playlist selection has its own switch and works when Smart DJ is turned off, provided the module is enabled for GMMP.

## How does Track Auto-DJ work, and can I turn it off?

**Track Auto-DJ** appears after **Play next** in an individual song's three-dot menu. It starts that song, keeps it as the first entry of a fresh queue, enables Smart DJ if necessary and lets GMMP Auto-DJ fill the queue to its configured Initial Size. Turn this feature on or off independently under **GoneSmart → UI → Track Auto-DJ**. It is enabled by default for existing users.

After a successful, verified mix, GoneSmart displays just **one short confirmation**. GMMP's intermediate Play/Auto-DJ Toasts and Snackbars—including delayed Auto-DJ-rules-changed status UI—are suppressed only during the bounded Track Auto-DJ startup window. A genuine error still produces one warning and detailed information in the Logs tab. If Auto-DJ starts refilling the old queue while a mix is being prepared, GoneSmart temporarily defers further old-session refills, then atomically removes the other native queue entries by their unique queue IDs. No repeated asynchronous queue-clearing commands are needed; the selected song and native playback pointer are verified before filling.

## Is “Track Auto-DJ” localized in every GMMP language?

Yes, the feature name is composed of the two **existing native GMMP
translations** for *track* and *Auto-DJ*, separated by a normal space.
German is **Titel Auto-DJ** and English is **Track Auto-DJ**. Other
languages use their own GMMP strings instead of a separately invented
translation for *Mix*. If GMMP provides a translated *started* string,
GoneSmart uses it for the sole success confirmation; otherwise it
appends a language-neutral checkmark. The companion UI reads the
installed GMMP's strings where available; an explicit in-player
language override is always honored inside GMMP's own context menus.

## How are GoneSmart updates handled?

The companion app checks the latest published stable GitHub Release at launch and shows the result on **Home → Updates**, without downloading APKs. **Add to Obtainium** opens GoneSmart's GitHub repository in Obtainium; Obtainium then handles notifications, downloads and future APK installation. Prerelease and locally built versions may display as development builds or not compare with stable releases.
