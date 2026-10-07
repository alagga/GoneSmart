# Releasing GoneSmart

GoneSmart public APKs are built and published by GitHub Actions from tagged source.

## Required repository secrets

- `LASTFM_API_KEY`
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the original release keystore backed up offline; every update must use the same signing key.

## Stable release flow

1. Finish code and documentation on the active feature branch.
2. Set `versionCode` / `versionName` in `app/build.gradle.kts` and finalize `RELEASE_NOTES.md`.
3. Confirm the normal **Build** workflow is green on the exact feature head.
4. Open/review the feature → `main` pull request and merge it without discarding unrelated `main` history.
5. Confirm the normal **Build** workflow is green on the exact merged `main` head.
6. Open **Actions → Release APK → Run workflow** with **prerelease** disabled.
7. Verify the resulting `v<versionName>` tag, GitHub Release and signed APK asset.

The release workflow verifies secrets, restores the release keystore, builds the signed APK, derives the tag from Gradle, creates the tag for a manual dispatch, uploads `GoneSmart-v<version>.apk` and uses `RELEASE_NOTES.md` as the release body.

## Prereleases

Use a version such as `0.5.0-beta1`, update release notes and dispatch **Release APK** with **prerelease** enabled.

## Compatibility gate

Before declaring a new GMMP version supported, follow `docs/GMMP_COMPATIBILITY_PLAYBOOK.md`: prove mutation boundaries, validate changed semantics on device, retire discovery/runtime overhead, update the tested-version source of truth and run exact-head CI.

## Version checks and Obtainium

The companion app queries GitHub's latest **stable** release on launch, rather than treating feature-branch or CI debug builds as published updates. After merging, bump `versionName` and `versionCode` before creating a new signed release, and use a matching `v<versionName>` release tag. The repository README and app have an **Add to Obtainium** link. Obtainium, not GoneSmart, handles future APK downloads and installation.

## Local development

Keep the Last.fm key in ignored `local.properties`. For local release signing use ignored `keystore.properties` as described in `BUILDING.md`; never place signing secrets in source.
