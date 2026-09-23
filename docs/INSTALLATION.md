# Installation

## Rooted: Vector / modern libxposed

GoneSmart targets libxposed API 102. The recommended rooted setup is JingMatrix Vector v2.2 or newer.

1. Install the latest GoneSmart APK from GitHub Releases.
2. Install and enable Vector using the official instructions for your root environment.
3. In the module manager, enable GoneSmart and scope it to `gonemad.gmmp`.
4. Force stop GoneMAD Music Player and reopen it.
5. Open GoneSmart and verify that the Xposed service is connected and GMMP is detected.
6. For Smart DJ, enable GMMP's normal Auto-DJ mode. GoneSmart only replaces the track-selection portion of Auto-DJ.
7. For multi-playlist selection, open GoneSmart's **UI** tab and enable **Multi-playlist selection**. This feature also works when Smart DJ is disabled.

### Automatic update checks and Obtainium

On launch, GoneSmart checks the latest published stable version via the GitHub Releases API. The status appears on **Home → Updates** and no APK is downloaded automatically.

Tap **Add to Obtainium** in GoneSmart, or use the [Add to Obtainium link](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fadd%3Furl%3Dhttps%253A%252F%252Fgithub.com%252Falagga%252FGoneSmart) on the README. Obtainium tracks this GitHub repository and handles updates once you approve importing it. Keep your original APK's signing lineage: an APK signed with a different key cannot update an existing installation in place.

### If the module does not become active

- Confirm that GoneSmart is enabled and scoped to GMMP.
- Force stop GMMP after installing or updating GoneSmart.
- Use **Restart GMMP** from the GoneSmart companion app.
- Check the in-app Logs page and, for deeper debugging, Logcat with tag `GoneSmart`.
- Check the exact GMMP version. The currently tested version is 4.2.0.

## No root: LSPatch 1.2 (experimental)

This path has not been validated as thoroughly as the rooted Vector setup.

1. Install GoneSmart.
2. Install JingMatrix LSPatch v1.2.
3. Patch GMMP using Local Patch Mode and Inject loader dex.
4. Install the generated patched GMMP APK.
5. Open LSPatch → Manage → GMMP → Modules and enable GoneSmart.
6. Open the GoneSmart companion app and then reopen GMMP.
7. Enable GMMP Auto-DJ and verify that the sparkle indicator appears on the Auto-DJ headphones icon.

### LSPatch cautions

Patching changes the target APK and its signature. This can affect Play Store updates, licensing, signature checks or other app behavior. Keep a backup of your original GMMP installation/data and only patch an APK you are authorized to modify.
