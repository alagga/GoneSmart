# Building GoneSmart

## Requirements

- JDK 17
- Gradle 9.6.0
- Android Gradle Plugin 9.4.1
- Android SDK 37
- Android Build Tools 36.0.0 or newer compatible tooling

## Last.fm API key

GoneSmart uses a Last.fm application API key for public recommendation requests. It does not use or package a Last.fm shared secret.

For local builds, add the key to `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
LASTFM_API_KEY=your_api_key
```

`local.properties` is ignored by Git.

For GitHub Actions releases, configure the repository secret:

- `LASTFM_API_KEY`

The key is injected at build time through `BuildConfig.LASTFM_API_KEY`. Remember that any key embedded in a client APK can ultimately be extracted; the GitHub secret prevents accidental publication in source history, not reverse engineering of the installed APK.

## Debug build

```bash
./gradlew :app:assembleDebug
```

## Release signing

Local signing can use an ignored `keystore.properties` file:

```properties
storeFile=/absolute/path/to/gonesmart-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

CI signing uses these environment variables:

- `GONESMART_KEYSTORE_FILE`
- `GONESMART_KEYSTORE_PASSWORD`
- `GONESMART_KEY_ALIAS`
- `GONESMART_KEY_PASSWORD`

The GitHub release workflow expects the following repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `LASTFM_API_KEY`

`ANDROID_KEYSTORE_BASE64` should be the base64-encoded contents of the dedicated GoneSmart release keystore. Back up the original keystore securely. Losing it means future APKs cannot update installations signed with that key.

## Release workflow

The **Release APK** workflow:

1. verifies required secrets,
2. builds a signed release APK,
3. derives the tag from `versionName`,
4. creates/pushes `v<versionName>` if needed,
5. publishes the APK and `RELEASE_NOTES.md` to GitHub Releases.

For prereleases, run the workflow manually and enable the prerelease input.
