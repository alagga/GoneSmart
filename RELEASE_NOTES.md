# GoneSmart v0.4.0 (upcoming)

Smart Auto-DJ remains the core feature; this update adds a separately controlled GMMP interface extension and companion-app update awareness.

## New: UI extensions

- **Multi-playlist selection**, independently enabled under the new **UI** tab. Long-press a playlist in GMMP's Add to Playlist dialog, select multiple destinations and confirm once.
- Writes through GMMP's native playlist-add operation for every selected source file. Normal single-destination taps and create-playlist behavior are unchanged.
- Correct highlights while scrolling, GMMP's dynamic theme colors, native localized selection text, back cancellation and one aggregate completion message.
- A compact GMMP-style headphones icon replaces the Smart DJ sparkle-only bottom-navigation icon. The player itself keeps its colored sparkle status indicator.

## Update management

- **Home → Updates** checks GitHub's latest published stable release on companion-app launch and shows current/newer/development/error states.
- **Add to Obtainium** hands the repository over to Obtainium for update management. GoneSmart does not download or silently install APKs.

## Compatibility and limitations

- Currently tested with **GoneMAD Music Player 4.2.0**, **libxposed API 102**, rooted Vector v2.2+ and Android 8.0+.
- The optional LSPatch 1.2 path remains experimental. GMMP upgrades may change internal obfuscated hooks or localized string resources.
- A future release must be built and signed with the same release key to update existing installations.

## Release process

Merge and review the feature branch, verify on-device compatibility, then update the release tag and publish the signed APK through GitHub Actions. These notes describe the **upcoming release**, not an APK already published.
