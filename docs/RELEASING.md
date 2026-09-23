# Releasing GoneSmart

GoneSmart releases are built and published by GitHub Actions so the public APK is reproducible from the tagged source.

## One-time repository setup

Configure these repository secrets under **Settings → Secrets and variables → Actions**:

- `LASTFM_API_KEY`
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the original release keystore backed up offline. Every future APK update must be signed with the same key.

## Normal release flow

1. Make changes on a feature branch.
2. Open/review a pull request and merge it into `main`.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
4. Update `RELEASE_NOTES.md`.
5. Confirm the **Build** workflow is green on `main`.
6. Open **Actions → Release APK → Run workflow**.
7. Leave **prerelease** disabled for a stable release, or enable it for a testing release.

The release workflow:

- verifies the provider/signing secrets,
- builds a signed release APK,
- derives `v<versionName>` from Gradle,
- creates the tag when manually dispatched,
- uploads `GoneSmart-v<version>.apk`,
- publishes `RELEASE_NOTES.md` as the GitHub Release notes.

## Prereleases

For alpha/beta builds, use a version name such as `0.4.0-beta1`, update the release notes, then manually run **Release APK** with **prerelease** enabled.

## Version checks and Obtainium

The companion app queries GitHub's latest **stable** release on launch, rather than treating feature-branch or CI debug builds as published updates. After merging, bump `versionName` and `versionCode` before creating a new signed release, and use a matching `v<versionName>` release tag. The repository README and app have an **Add to Obtainium** link. Obtainium, not GoneSmart, handles future APK downloads and installation.

## Local development

After cloning the repository, put your local Last.fm application key in the ignored `local.properties` file. Do not copy the GitHub signing secrets into source files.

For local release signing, use an ignored `keystore.properties` as described in [BUILDING.md](BUILDING.md).

## Compatibility discipline

GoneSmart hooks internal GMMP implementation details. Before marking a new GMMP version as supported:

1. test normal Smart Auto-DJ selection,
2. test recommendation-pool reuse,
3. test new-session invalidation,
4. test offline/native fallback,
5. test the green/red player sparkle,
6. test rating and broad-search fallbacks,
7. test normal single-playlist and optional multi-playlist selection, including multi-file adds, the aggregate Toast, scrolling, theme changes and returning to the original screen,
8. verify the in-app version check against the published stable GitHub Release and the Obtainium hand-off,
9. update compatibility documentation only after verification.
