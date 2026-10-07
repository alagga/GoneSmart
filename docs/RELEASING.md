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
2. Set `versionCode` / `versionName` in `app/build.gradle.kts` and finalize `RELEASE_NOTES.md`. Update `docs/forum/THREAD_START_TEMPLATE.bbcode` if the public feature overview changed, then run `python3 scripts/generate_forum_posts.py`.
3. Review the generated `docs/forum/THREAD_START.bbcode` and `docs/forum/LATEST_RELEASE_REPLY.bbcode`, then confirm the normal **Build** workflow is green on the exact feature head. CI fails if the generated forum copy is stale.
4. Open/review the feature → `main` pull request and merge it without discarding unrelated `main` history.
5. Confirm the normal **Build** workflow is green on the exact merged `main` head.
6. Open **Actions → Release APK → Run workflow** with **prerelease** disabled.
7. Verify the resulting `v<versionName>` tag, GitHub Release and signed APK asset.

If an exact-head Build is clearly stuck in GitHub Actions infrastructure before GoneSmart's own test/build steps start, do not treat that run as a code failure and do not publish by bypassing the gate. Trigger a fresh exact-head Build and require that replacement run to complete successfully before releasing.

The release workflow verifies secrets, regenerates the GMMP forum copy, restores the release keystore, builds the signed APK, derives the tag from Gradle, creates the tag for a manual dispatch, uploads `GoneSmart-v<version>.apk` plus versioned forum-thread/reply BBCode assets, and uses `RELEASE_NOTES.md` as the release body. Manual releases must be dispatched from `main`. If the generated forum files changed, the workflow refreshes the copy-ready files on `main` after publication.

After publishing, copy `docs/forum/LATEST_RELEASE_REPLY.bbcode` into the existing GMMP forum thread and update its first post from `docs/forum/THREAD_START.bbcode` whenever the overview or compatibility information changed. The suggested subject is in `docs/forum/THREAD_SUBJECT.txt`.

### Patch releases after a major feature release

Use a new patch version instead of replacing an already published APK under the old version number. This preserves a clean Android update path, release history and reproducible artifacts.

When a small patch becomes GitHub's **Latest Release** immediately after a large feature release, start the patch notes with a prominent sentence that it is a small follow-up and link back to the preceding major release. Briefly name the major release's headline features so visitors landing on the Latest Release page do not mistake the small patch for the project's full feature scope.

## Prereleases

Use a version such as `0.5.0-beta1`, update release notes and dispatch **Release APK** with **prerelease** enabled.

## Compatibility gate

Before declaring a new GMMP version supported, follow `docs/GMMP_COMPATIBILITY_PLAYBOOK.md`: prove mutation boundaries, validate changed semantics on device, retire discovery/runtime overhead, update the tested-version source of truth and run exact-head CI.

## Version checks and Obtainium

The companion app queries GitHub's latest **stable** release on launch, rather than treating feature-branch or CI debug builds as published updates. After merging, bump `versionName` and `versionCode` before creating a new signed release, and use a matching `v<versionName>` release tag. The repository README and app have an **Add to Obtainium** link. Obtainium, not GoneSmart, handles future APK downloads and installation.

## Local development

Keep the Last.fm key in ignored `local.properties`. For local release signing use ignored `keystore.properties` as described in `BUILDING.md`; never place signing secrets in source.
