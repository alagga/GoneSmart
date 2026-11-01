# Remix-first external genre recommendation follow-up (2026-11-01)

## Evidence and scope

The 2026-10-30 report featured Armin van Buuren's *In And Out Of Love
(Re-Style Bootleg)* (GMMP track ID 19189). The existing metadata parser correctly
extracted `remixArtists=[Re-Style]`, `versionType=BOOTLEG`, but provider fallback
queried/expanded Armin's original-artist graph. A trance-oriented result is not
reliable evidence for Re-Style's hardstyle bootleg.

ListenBrainz Labs' current similar-recording/artist responses supply identities
and scores, not genre assignments. Last.fm supplies crowd-sourced genre/style
tags for an artist and a tag-specific top-tracks endpoint. We add **one**
conservative Last.fm genre rescue path for the preferred producer/artist. It
rejects arbitrary personal/community tags such as `seen live` and favors
recognized styles such as `hardstyle`, `trance` or `deep house`. It fetches
at most one genre's top tracks (50 max) and maps them through the existing
local-library match/quality/duplicate/rating gates with a lower recommendation
similarity score than direct track recommendations.

For a recognizable remix producer, Last.fm direct version lookup is attempted
first. On fallback, genre and similar-artist discovery uses the **remixer**.
Only if both are unproductive is the original song/artist considered, with
0.38x identity confidence. ListenBrainz retains exact version-matched recording
similarity but does not fall back to an unverified original-artist similarity
graph for a known remix. Unversioned tracks retain their prior direct paths;
genre rescue can supplement a sparse provider pool.

This is deliberately **not** a hard genre filter: the seed's remote tags can
be absent, noisy or misleading. Local MP3 genre fields are not used as the
authority because the user's library frequently contains missing/mislabeled
values. Cached remote tags/top tracks avoid duplicate requests across sessions.
No additional GMMP hooks or queue/decoder writers are introduced.

## Evidence still required

- Device-check a Re-Style bootleg; expect `LASTFM GENRE FALLBACK` with the
  producer's style, or `LASTFM REMIX ARTIST`, instead of Armin-only fallback.
- Compare recommendation relevance when some Last.fm tags are missing or broad;
  no match must retain the existing native-fallback policy.
- Confirm delay is acceptable on a cold pool; look at provider timing and
  the exact same queue Session ID, not only the visible track list.

## Audit

- **Compatibility:** external source selection only; existing native GMMP
  `qr.z(int)` handles insertion. No new R8/reflective binding or SQL write.
- **Performance:** genre path is bounded to the recovery phase, one recognized
  tag, cached results; no main-thread network call or extra polling.
- **Logging:** compact Logcat genre/remixer-fallback summaries under the
  existing `GoneSmart` tag. No routine companion event, new filter or Help
  string; historical support logs remain unaffected.
- **Localization:** no new translations required; no GMMP-facing text.
- **Validation:** host policy tests and exact-head Build CI; device acceptance
  still pending.
