# GoneSmart 0.5.0-rc.1 combined release audit

**Date:** 2026-10-17
**Accepted GMMP target:** 4.2.1
**Integrated workstreams:** Bluetooth Audio Profiles, Auto-DJ Providers, GMMP 4.2.1 Playlist Link / Track Auto-DJ compatibility

This document is the combined closeout required by `AGENTS.md`. Feature-branch acceptance remains documented in the feature-specific files; this audit covers the merged release tree and must be paired with exact-head CI before publication.

## Integration

- The three workstreams are present in one release tree with their merge history preserved.
- Shared `MainActivity` functionality keeps both provider/settings UI and the Audio Profiles entry point.
- Xposed initialization keeps `GoneSmartModule`, `GoneSmartAudioProfileApplyModule` and `GoneSmartAudioModule`.
- `GmmpLibraryReader` keeps both the accepted 4.2.1 read-only fallback/path-index behavior and provider cache-revalidation behavior.
- Shared compatibility rules/docs were reconciled semantically rather than choosing one branch wholesale.

## Native / compatibility audit

- State-changing GMMP work remains native-first and fail-closed on ambiguous mutation boundaries.
- Obfuscated member names are treated as tested-version fast paths/evidence, not semantic identity.
- Queue Flip hard-coded queue members are reachable only through the explicit legacy `ex3` capture; the current path uses the semantic Auto-DJ/mutation bridge and bounded read-only verification.
- Provider/library observation can use the structurally resolved read-only GMMP Room/Cursor path; it does not become write authority.
- Playlist Link and Track Auto-DJ retain their device-accepted 4.2.1 contracts.
- Audio Profiles observes/proves the native preference/Equalizer boundaries and does not invoke unknown methods simply to discover behavior.
- Accepted runtime bindings are cached or reduced to cheap paths; broad inventories are reserved for unknown/failing shapes.

## Localization audit

- The GoneSmart companion UI remains intentionally English-only.
- Provider and Audio Profiles settings add no new GMMP-facing translation requirements.
- GMMP-facing feature wording uses the live host resources/current GMMP locale. Required native Track Auto-DJ nouns fail closed if unavailable.
- Track Auto-DJ success uses GMMP's native `started` wording when available; otherwise it keeps the already-native action label and appends only `✓`, avoiding an arbitrary English fallback inside GMMP.
- Existing centralized feature-owned Move / Other Locations translations remain the documented exceptions.

## Logging / performance audit

- Normal provider logging is aggregate/bounded rather than per-candidate spam.
- Audio Profiles is event-driven/debounced; no polling loop was introduced.
- The companion Logs view remains high-level; deep support guidance includes the current `GoneSmartAudioProfiles` and `GoneSmartAudioApply` tags alongside the existing GoneSmart tag family.
- Debug-only probes are not part of normal support instructions.

## Public documentation / release surface

- README, FAQ, roadmap, release notes and feature completion docs describe the combined 0.5.0 candidate rather than separate planned branches.
- Forum copy is generated from the current version/compatibility data and focuses on user-visible features rather than reverse-engineering details.
- `versionName` is `0.5.0-rc.1`; stable `0.5.0` is intentionally deferred until prerelease testing succeeds.
- The release workflow recognizes a SemVer prerelease suffix such as `-rc.1` as a GitHub prerelease even on tag-triggered runs.

## Required final gates

1. `python3 scripts/generate_forum_posts.py --check` succeeds.
2. No temporary integration/audit workflow remains in the release tree.
3. Normal **Build** succeeds on the exact release-candidate head: unit tests, Debug APK, unsigned Release smoke and artifact upload.
4. After merging the candidate to `main`, the same normal **Build** succeeds on the exact merged `main` head.
5. Only then create/publish `v0.5.0-rc.1` as a prerelease. Stable `v0.5.0` remains blocked on maintainer prerelease testing.
