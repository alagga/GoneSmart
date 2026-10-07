from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one block in {path}, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


main = ROOT / "app/src/main/java/io/github/alagga/gonesmart/MainActivity.kt"
replace_once(
    main,
    "import android.widget.FrameLayout\n",
    "import android.widget.CheckBox\nimport android.widget.FrameLayout\n",
)
replace_once(
    main,
    '        private const val GMMP_PACKAGE = "gonemad.gmmp"\n\n',
    '        private const val GMMP_PACKAGE = "gonemad.gmmp"\n'
    '        private const val COMPATIBILITY_PREFS = "gonesmart_compatibility"\n'
    '        private const val KEY_ACKNOWLEDGED_GMMP_UPDATE_WARNING_VERSIONS = "acknowledged_gmmp_update_warning_versions"\n\n',
)
replace_once(
    main,
    "        refreshStatus()\n        checkForUpdates()\n",
    "        refreshStatus()\n        if (savedInstanceState == null) {\n"
    "            showGmmpUpdateWarningIfNeeded()\n"
    "        }\n"
    "        checkForUpdates()\n",
)
replace_once(
    main,
    '''        container.addView(infoCard(\n            title = "Tested GMMP version",\n            body = "GoneSmart is currently tested against GoneMAD Music Player ${GmmpCompatibilityPolicy.TESTED_VERSION}. Other GMMP versions are considered untested because GoneSmart hooks GMMP internals that can change between releases."\n        ))\n        container.addView(verticalGap(12))\n        container.addView(infoCard(\n            title = "Xposed / LSPatch",\n            body = "The rooted setup targets the modern libxposed API 102 implementation in Vector 2.2 or newer. LSPatch 1.2 is documented as an experimental no-root path, but it has not yet been validated as thoroughly as the rooted Vector setup."\n        ))\n''',
    '''        container.addView(infoCard(\n            title = "Tested GMMP version",\n            body = "GoneSmart is currently tested against GoneMAD Music Player ${GmmpCompatibilityPolicy.TESTED_VERSION}. Other GMMP versions are considered untested because GoneSmart hooks GMMP internals that can change between releases."\n        ))\n        container.addView(verticalGap(12))\n        container.addView(infoCard(\n            title = "Automatic GMMP updates",\n            body = "A new GMMP version can change internal components and temporarily break GoneSmart features. We recommend disabling automatic Play Store updates for GMMP and checking GoneSmart compatibility before updating. The startup reminder is remembered per GMMP version and appears again after the installed GMMP version changes."\n        ))\n        container.addView(verticalGap(10))\n        container.addView(outlineButton("Open GMMP in Play Store") { openGmmpPlayStore() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))\n        container.addView(verticalGap(12))\n        container.addView(infoCard(\n            title = "Xposed / LSPatch",\n            body = "The rooted setup targets the modern libxposed API 102 implementation in Vector 2.2 or newer. LSPatch 1.2 is documented as an experimental no-root path, but it has not yet been validated as thoroughly as the rooted Vector setup."\n        ))\n''',
)
replace_once(
    main,
    '''    private fun getGmmpVersion(): String? {\n''',
    '''    private fun showGmmpUpdateWarningIfNeeded() {\n        val gmmpVersion = getGmmpVersion() ?: return\n        val preferences = getSharedPreferences(COMPATIBILITY_PREFS, Context.MODE_PRIVATE)\n        val acknowledgedVersions =\n            preferences.getStringSet(KEY_ACKNOWLEDGED_GMMP_UPDATE_WARNING_VERSIONS, emptySet())\n                ?.toSet()\n                .orEmpty()\n        if (!GmmpUpdateWarningPolicy.shouldShow(gmmpVersion, acknowledgedVersions)) return\n\n        val compatibilityState = GmmpCompatibilityPolicy.state(gmmpVersion)\n        val message = when (compatibilityState) {\n            GmmpCompatibilityPolicy.State.TESTED ->\n                "GoneSmart is tested with GMMP $gmmpVersion. Future GMMP updates can change internal components and temporarily break GoneSmart features.\\n\\nWe recommend disabling automatic Play Store updates for GMMP and checking GoneSmart compatibility before updating."\n            GmmpCompatibilityPolicy.State.UNTESTED ->\n                "Installed GMMP $gmmpVersion has not been verified with GoneSmart yet. New GMMP versions can temporarily break GoneSmart features.\\n\\nWe recommend disabling automatic Play Store updates for GMMP and checking the GoneSmart compatibility status before updating again."\n            GmmpCompatibilityPolicy.State.UNKNOWN -> return\n        }\n\n        val content = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            setPadding(dp(24), 0, dp(24), dp(6))\n        }\n        content.addView(textView(message, 14f, COLOR_TEXT_SECONDARY))\n        content.addView(verticalGap(14))\n        val doNotShowAgain = CheckBox(this).apply {\n            text = "Don't show again for GMMP $gmmpVersion"\n            setTextColor(COLOR_TEXT)\n            textSize = 14f\n        }\n        content.addView(doNotShowAgain)\n\n        fun acknowledgeIfRequested() {\n            if (!doNotShowAgain.isChecked) return\n            val updated = GmmpUpdateWarningPolicy.withAcknowledged(gmmpVersion, acknowledgedVersions)\n            preferences.edit()\n                .putStringSet(KEY_ACKNOWLEDGED_GMMP_UPDATE_WARNING_VERSIONS, updated)\n                .apply()\n        }\n\n        AlertDialog.Builder(this)\n            .setTitle("GMMP automatic updates")\n            .setView(content)\n            .setPositiveButton("Got it") { _, _ -> acknowledgeIfRequested() }\n            .setNeutralButton("Open Play Store") { _, _ ->\n                acknowledgeIfRequested()\n                openGmmpPlayStore()\n            }\n            .show()\n    }\n\n    private fun openGmmpPlayStore() {\n        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GMMP_PACKAGE")).apply {\n            addCategory(Intent.CATEGORY_BROWSABLE)\n            setPackage("com.android.vending")\n        }\n        try {\n            startActivity(marketIntent)\n        } catch (_: Throwable) {\n            openUrl("https://play.google.com/store/apps/details?id=$GMMP_PACKAGE")\n        }\n    }\n\n    private fun getGmmpVersion(): String? {\n''',
)

policy = ROOT / "app/src/main/java/io/github/alagga/gonesmart/GmmpUpdateWarningPolicy.kt"
policy.write_text(
    '''package io.github.alagga.gonesmart\n\ninternal object GmmpUpdateWarningPolicy {\n    fun shouldShow(\n        installedVersion: String?,\n        acknowledgedVersions: Set<String>\n    ): Boolean {\n        val version = installedVersion?.trim().orEmpty()\n        return version.isNotEmpty() && version !in acknowledgedVersions\n    }\n\n    fun withAcknowledged(\n        installedVersion: String,\n        acknowledgedVersions: Set<String>\n    ): Set<String> {\n        val version = installedVersion.trim()\n        if (version.isEmpty()) return acknowledgedVersions\n        return acknowledgedVersions + version\n    }\n}\n''',
    encoding="utf-8",
)

test = ROOT / "app/src/test/java/io/github/alagga/gonesmart/GmmpUpdateWarningPolicyTest.kt"
test.write_text(
    '''package io.github.alagga.gonesmart\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass GmmpUpdateWarningPolicyTest {\n    @Test\n    fun missingVersionDoesNotShowWarning() {\n        assertFalse(GmmpUpdateWarningPolicy.shouldShow(null, emptySet()))\n        assertFalse(GmmpUpdateWarningPolicy.shouldShow("   ", emptySet()))\n    }\n\n    @Test\n    fun installedVersionShowsUntilThatVersionIsAcknowledged() {\n        assertTrue(GmmpUpdateWarningPolicy.shouldShow("4.2.1", emptySet()))\n        assertFalse(GmmpUpdateWarningPolicy.shouldShow("4.2.1", setOf("4.2.1")))\n    }\n\n    @Test\n    fun changingGmmpVersionShowsWarningAgain() {\n        val acknowledged = GmmpUpdateWarningPolicy.withAcknowledged("4.2.1", emptySet())\n        assertTrue(GmmpUpdateWarningPolicy.shouldShow("4.2.2", acknowledged))\n    }\n\n    @Test\n    fun previouslyAcknowledgedVersionStaysAcknowledgedAfterOtherVersions() {\n        val acknowledged =\n            GmmpUpdateWarningPolicy.withAcknowledged(\n                "4.2.2",\n                GmmpUpdateWarningPolicy.withAcknowledged("4.2.1", emptySet())\n            )\n        assertFalse(GmmpUpdateWarningPolicy.shouldShow("4.2.1", acknowledged))\n        assertFalse(GmmpUpdateWarningPolicy.shouldShow("4.2.2", acknowledged))\n    }\n}\n''',
    encoding="utf-8",
)

forum_dir = ROOT / "docs/forum"
forum_dir.mkdir(parents=True, exist_ok=True)
(forum_dir / "THREAD_SUBJECT.txt").write_text(
    "GoneSmart – Smart Auto-DJ, Playlist & UI Extensions for GMMP\n",
    encoding="utf-8",
)
(forum_dir / "THREAD_START_TEMPLATE.bbcode").write_text(
    '''[size=5][b]GoneSmart – Smart Auto-DJ, Playlist & UI Extensions for GoneMAD Music Player[/b][/size]\n\nHi everyone,\n\nI'd like to share [b]GoneSmart[/b], an independent Xposed/libxposed module and companion app that extends GoneMAD Music Player with a smarter Auto-DJ and several optional playlist, playback and UI features.\n\nI'll keep this first post updated and use the thread replies for future releases.\n\n[b]GitHub:[/b]\n[url=https://github.com/alagga/GoneSmart]https://github.com/alagga/GoneSmart[/url]\n\n[b]Current release:[/b]\n[url={{RELEASE_URL}}]GoneSmart v{{VERSION}}[/url]\n\n\n[size=4][b]What is GoneSmart?[/b][/size]\n\nGoneSmart keeps GMMP as the actual music player and adds extra functionality around it.\n\n[list]\n[*][b]Smart Auto-DJ[/b] – uses the current listening session to find related music and matches those recommendations against tracks that already exist in your local GMMP library.\n[*][b]UI & workflow extensions[/b] – additional playlist, Smart-Playlist, queue and playback tools integrated into GMMP.\n[/list]\n\nFor Smart Auto-DJ, GoneSmart currently combines recommendations from [b]ListenBrainz[/b] and [b]Last.fm[/b]. Your complete music library is not uploaded to those services; matching against your library happens locally.\n\n\n[size=4][b]Main features[/b][/size]\n\n[b]Smart Auto-DJ[/b]\n[list]\n[*]Session-aware recommendations based on the current and recently selected tracks\n[*]ListenBrainz + Last.fm recommendation sources\n[*]Only selects music that exists in your GMMP library\n[*]Recommendation pool for faster Auto-DJ refills\n[*]Rating and matching preferences\n[*]Optional fallback to GMMP's normal Auto-DJ\n[*]Player indicator showing GoneSmart Auto-DJ status\n[/list]\n\n[b]Playlist & Smart-Playlist tools[/b]\n[list]\n[*]Playlist folders\n[*]Smart-Playlist folders\n[*]Multi-selection\n[*]Playlist Link – use a normal playlist as a live source inside a Smart Playlist\n[/list]\n\n[b]Playback tools[/b]\n[list]\n[*]Flip the current queue\n[*]Play normal playlists or Smart Playlists from last track to first\n[*]Track Auto-DJ – start a new Smart Auto-DJ session directly from an individual song\n[/list]\n\n[b]Companion app[/b]\n[list]\n[*]Module and GMMP status\n[*]Smart DJ and UI settings\n[*]Compatibility information\n[*]High-level activity/error logs\n[*]Update checking and built-in help\n[/list]\n\n\n[size=4][b]Current compatibility[/b][/size]\n\n[list]\n[*][b]GoneSmart:[/b] v{{VERSION}}\n[*][b]Tested GMMP version:[/b] {{TESTED_GMMP}}\n[*][b]Android:[/b] 8.0+\n[*][b]Hooking API:[/b] libxposed API 102\n[*][b]Recommended rooted setup:[/b] JingMatrix Vector 2.2+\n[/list]\n\nLSPatch is also possible, but is currently less extensively tested.\n\n\n[size=4][b]Important note about GMMP updates[/b][/size]\n\nGoneSmart integrates with GMMP internals. A future GMMP update can therefore temporarily break individual GoneSmart features even though many hooks and lookups are designed to be more dynamic across versions.\n\n[b]I recommend disabling automatic Play Store updates for GoneMAD Music Player while using GoneSmart.[/b]\n\nBefore manually updating GMMP, check this thread or the GoneSmart GitHub page to see whether the new GMMP version has already been tested. GoneSmart itself also warns again when it detects a different GMMP version.\n\n\n[size=4][b]Feedback & bug reports[/b][/size]\n\nFeedback is very welcome, especially with different libraries, Android setups and GMMP configurations. If something breaks, please include your exact GMMP version and, if possible, the relevant GoneSmart logs.\n\n[url=https://github.com/alagga/GoneSmart/issues]GitHub issues and feature requests[/url]\n\nGoneSmart is an independent project and is not affiliated with GoneMAD Software, Last.fm, MusicBrainz, MetaBrainz or ListenBrainz.\n''',
    encoding="utf-8",
)

script = ROOT / "scripts/generate_forum_posts.py"
script.parent.mkdir(parents=True, exist_ok=True)
script.write_text(
    '''#!/usr/bin/env python3\nfrom __future__ import annotations\n\nimport argparse\nimport re\nimport sys\nfrom pathlib import Path\n\nROOT = Path(__file__).resolve().parents[1]\nFORUM_DIR = ROOT / "docs/forum"\nTHREAD_TEMPLATE = FORUM_DIR / "THREAD_START_TEMPLATE.bbcode"\nTHREAD_OUTPUT = FORUM_DIR / "THREAD_START.bbcode"\nREPLY_OUTPUT = FORUM_DIR / "LATEST_RELEASE_REPLY.bbcode"\n\n\ndef read_version() -> str:\n    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")\n    match = re.search(r'versionName\\s*=\\s*"([^"]+)"', text)\n    if not match:\n        raise RuntimeError("Could not find versionName in app/build.gradle.kts")\n    return match.group(1)\n\n\ndef read_tested_gmmp() -> str:\n    text = (ROOT / "app/src/main/java/io/github/alagga/gonesmart/GmmpCompatibilityPolicy.kt").read_text(encoding="utf-8")\n    match = re.search(r'TESTED_VERSION\\s*=\\s*"([^"]+)"', text)\n    if not match:\n        raise RuntimeError("Could not find GmmpCompatibilityPolicy.TESTED_VERSION")\n    return match.group(1)\n\n\ndef inline_bbcode(text: str) -> str:\n    text = re.sub(r'\\[([^]]+)\\]\\((https?://[^)]+)\\)', r'[url=\\2]\\1[/url]', text)\n    text = re.sub(r'\\*\\*([^*]+)\\*\\*', r'[b]\\1[/b]', text)\n    text = re.sub(r'(?<!\\*)\\*([^*]+)\\*(?!\\*)', r'[i]\\1[/i]', text)\n    text = text.replace('`', '')\n    return text\n\n\ndef markdown_release_to_bbcode(markdown: str) -> str:\n    out: list[str] = []\n    list_open = False\n    skip_section = False\n\n    def close_list() -> None:\n        nonlocal list_open\n        if list_open:\n            out.append("[/list]")\n            list_open = False\n\n    for raw in markdown.splitlines():\n        line = raw.rstrip()\n        if line.startswith("# "):\n            continue\n        if line.startswith("## "):\n            close_list()\n            title = line[3:].strip()\n            skip_section = title.lower() == "installation"\n            if skip_section:\n                continue\n            out.extend(["", f"[size=4][b]{inline_bbcode(title)}[/b][/size]", ""])\n            continue\n        if skip_section:\n            continue\n        if line.startswith("### "):\n            close_list()\n            out.extend(["", f"[b]{inline_bbcode(line[4:].strip())}[/b]", ""])\n            continue\n        if line.startswith("- "):\n            if not list_open:\n                out.append("[list]")\n                list_open = True\n            out.append(f"[*]{inline_bbcode(line[2:].strip())}")\n            continue\n        close_list()\n        if not line or line == "---":\n            if out and out[-1] != "":\n                out.append("")\n            continue\n        out.append(inline_bbcode(line))\n\n    close_list()\n    while out and out[-1] == "":\n        out.pop()\n    return "\\n".join(out).strip()\n\n\ndef render() -> tuple[str, str]:\n    version = read_version()\n    tested_gmmp = read_tested_gmmp()\n    release_url = f"https://github.com/alagga/GoneSmart/releases/tag/v{version}"\n\n    thread = THREAD_TEMPLATE.read_text(encoding="utf-8")\n    thread = (\n        thread.replace("{{VERSION}}", version)\n        .replace("{{TESTED_GMMP}}", tested_gmmp)\n        .replace("{{RELEASE_URL}}", release_url)\n    )\n\n    notes = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")\n    body = markdown_release_to_bbcode(notes)\n    reply = f'''[size=5][b]GoneSmart v{version} released[/b][/size]\n\nThe new release is now available. I've updated the first post with the current compatibility information as well.\n\n[b]GitHub release / APK:[/b]\n[url={release_url}]{release_url}[/url]\n\n{body}\n'''
    return thread.rstrip() + "\\n", reply.rstrip() + "\\n"\n\n\ndef main() -> int:\n    parser = argparse.ArgumentParser(description="Generate copy-ready BBCode for the GMMP forum thread.")\n    parser.add_argument("--check", action="store_true", help="Fail if committed generated files are stale.")\n    args = parser.parse_args()\n\n    thread, reply = render()\n    expected = {THREAD_OUTPUT: thread, REPLY_OUTPUT: reply}\n\n    if args.check:\n        stale = []\n        for path, content in expected.items():\n            if not path.exists() or path.read_text(encoding="utf-8") != content:\n                stale.append(path.relative_to(ROOT))\n        if stale:\n            print("Forum copy is stale. Run: python3 scripts/generate_forum_posts.py", file=sys.stderr)\n            for path in stale:\n                print(f" - {path}", file=sys.stderr)\n            return 1\n        print("Forum copy is up to date.")\n        return 0\n\n    for path, content in expected.items():\n        path.write_text(content, encoding="utf-8")\n        print(f"Wrote {path.relative_to(ROOT)}")\n    return 0\n\n\nif __name__ == "__main__":\n    raise SystemExit(main())\n''',
    encoding="utf-8",
)

forum_readme = forum_dir / "README.md"
forum_readme.write_text(
    '''# GoneMAD Music Player forum copy\n\nThis directory keeps copy-ready BBCode for the GoneSmart thread in the GMMP General Discussion forum:\n\n- `THREAD_SUBJECT.txt` — suggested thread subject.\n- `THREAD_START_TEMPLATE.bbcode` — maintained source for the first post.\n- `THREAD_START.bbcode` — generated first post with the current GoneSmart / tested-GMMP versions.\n- `LATEST_RELEASE_REPLY.bbcode` — generated reply for the current `RELEASE_NOTES.md`.\n\nGenerate both copy-ready files with:\n\n```bash\npython3 scripts/generate_forum_posts.py\n```\n\nDo not hand-edit the generated `THREAD_START.bbcode` or `LATEST_RELEASE_REPLY.bbcode`; edit the template and/or `RELEASE_NOTES.md`, then regenerate.\n\nThe normal Build workflow checks that the generated files are current. The Release APK workflow regenerates them, uploads versioned BBCode copies as release assets, and on a manual release from `main` refreshes the generated files in the repository if necessary.\n\nForum: https://gonemadmusicplayer.proboards.com/board/5/general-discussion\n''',
    encoding="utf-8",
)

# Generate the current 0.4.0 copy now.
exec(compile(script.read_text(encoding="utf-8"), str(script), "exec"), {"__name__": "__not_main__", "__file__": str(script)})
# Import-like execution above intentionally avoids main(); call render through runpy instead.
import runpy
ns = runpy.run_path(str(script), run_name="forum_generator")
thread, reply = ns["render"]()
(forum_dir / "THREAD_START.bbcode").write_text(thread, encoding="utf-8")
(forum_dir / "LATEST_RELEASE_REPLY.bbcode").write_text(reply, encoding="utf-8")

# Build workflow: keep forum copy synchronized on every normal CI run.
build = ROOT / ".github/workflows/build.yml"
replace_once(
    build,
    '''      - name: Set up JDK 17\n''',
    '''      - name: Verify generated forum copy\n        run: python3 scripts/generate_forum_posts.py --check\n\n      - name: Set up JDK 17\n''',
)

# Release workflow: regenerate, publish BBCode assets, and refresh main after manual releases.
release = ROOT / ".github/workflows/release.yml"
replace_once(
    release,
    '''      - name: Verify release secrets\n''',
    '''      - name: Verify manual releases run from main\n        if: github.event_name == 'workflow_dispatch'\n        shell: bash\n        run: |\n          set -euo pipefail\n          test "$GITHUB_REF_NAME" = "main" || { echo "Manual releases must be dispatched from main."; exit 1; }\n\n      - name: Generate GMMP forum copy\n        run: python3 scripts/generate_forum_posts.py\n\n      - name: Verify release secrets\n''',
)
replace_once(
    release,
    '''          APK_NAME="GoneSmart-$TAG.apk"\n          test -f "$APK_SOURCE" || { echo "Release APK not found at $APK_SOURCE"; exit 1; }\n          cp "$APK_SOURCE" "$APK_NAME"\n          echo "version=$VERSION" >> "$GITHUB_OUTPUT"\n          echo "tag=$TAG" >> "$GITHUB_OUTPUT"\n          echo "apk=$APK_NAME" >> "$GITHUB_OUTPUT"\n''',
    '''          APK_NAME="GoneSmart-$TAG.apk"\n          FORUM_THREAD="GoneSmart-$TAG-forum-thread.bbcode.txt"\n          FORUM_REPLY="GoneSmart-$TAG-forum-reply.bbcode.txt"\n          test -f "$APK_SOURCE" || { echo "Release APK not found at $APK_SOURCE"; exit 1; }\n          cp "$APK_SOURCE" "$APK_NAME"\n          cp docs/forum/THREAD_START.bbcode "$FORUM_THREAD"\n          cp docs/forum/LATEST_RELEASE_REPLY.bbcode "$FORUM_REPLY"\n          echo "version=$VERSION" >> "$GITHUB_OUTPUT"\n          echo "tag=$TAG" >> "$GITHUB_OUTPUT"\n          echo "apk=$APK_NAME" >> "$GITHUB_OUTPUT"\n          echo "forum_thread=$FORUM_THREAD" >> "$GITHUB_OUTPUT"\n          echo "forum_reply=$FORUM_REPLY" >> "$GITHUB_OUTPUT"\n''',
)
replace_once(
    release,
    '''          APK="${{ steps.meta.outputs.apk }}"\n          TITLE="GoneSmart $TAG"\n\n          if gh release view "$TAG" >/dev/null 2>&1; then\n            gh release upload "$TAG" "$APK" --clobber\n            gh release edit "$TAG" --title "$TITLE" --notes-file RELEASE_NOTES.md\n          else\n            EXTRA=()\n            if [ "$PRERELEASE" = "true" ]; then\n              EXTRA+=(--prerelease)\n            fi\n            gh release create "$TAG" "$APK" --title "$TITLE" --notes-file RELEASE_NOTES.md "${EXTRA[@]}"\n          fi\n''',
    '''          APK="${{ steps.meta.outputs.apk }}"\n          FORUM_THREAD="${{ steps.meta.outputs.forum_thread }}"\n          FORUM_REPLY="${{ steps.meta.outputs.forum_reply }}"\n          TITLE="GoneSmart $TAG"\n\n          if gh release view "$TAG" >/dev/null 2>&1; then\n            gh release upload "$TAG" "$APK" "$FORUM_THREAD" "$FORUM_REPLY" --clobber\n            gh release edit "$TAG" --title "$TITLE" --notes-file RELEASE_NOTES.md\n          else\n            EXTRA=()\n            if [ "$PRERELEASE" = "true" ]; then\n              EXTRA+=(--prerelease)\n            fi\n            gh release create "$TAG" "$APK" "$FORUM_THREAD" "$FORUM_REPLY" --title "$TITLE" --notes-file RELEASE_NOTES.md "${EXTRA[@]}"\n          fi\n\n      - name: Refresh forum copy on main\n        if: github.event_name == 'workflow_dispatch' && github.ref_name == 'main'\n        shell: bash\n        run: |\n          set -euo pipefail\n          git add docs/forum/THREAD_START.bbcode docs/forum/LATEST_RELEASE_REPLY.bbcode\n          if git diff --cached --quiet; then\n            echo "Forum copy already current."\n            exit 0\n          fi\n          git config user.name "github-actions[bot]"\n          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"\n          git commit -m "docs: refresh GMMP forum copy for ${{ steps.meta.outputs.tag }} [skip ci]"\n          git push origin HEAD:main\n''',
)

# Release documentation.
releasing = ROOT / "docs/RELEASING.md"
replace_once(
    releasing,
    '''2. Set `versionCode` / `versionName` in `app/build.gradle.kts` and finalize `RELEASE_NOTES.md`.\n3. Confirm the normal **Build** workflow is green on the exact feature head.\n''',
    '''2. Set `versionCode` / `versionName` in `app/build.gradle.kts` and finalize `RELEASE_NOTES.md`. Update `docs/forum/THREAD_START_TEMPLATE.bbcode` if the public feature overview changed, then run `python3 scripts/generate_forum_posts.py`.\n3. Review the generated `docs/forum/THREAD_START.bbcode` and `docs/forum/LATEST_RELEASE_REPLY.bbcode`, then confirm the normal **Build** workflow is green on the exact feature head. CI fails if the generated forum copy is stale.\n''',
)
replace_once(
    releasing,
    '''The release workflow verifies secrets, restores the release keystore, builds the signed APK, derives the tag from Gradle, creates the tag for a manual dispatch, uploads `GoneSmart-v<version>.apk` and uses `RELEASE_NOTES.md` as the release body.\n''',
    '''The release workflow verifies secrets, regenerates the GMMP forum copy, restores the release keystore, builds the signed APK, derives the tag from Gradle, creates the tag for a manual dispatch, uploads `GoneSmart-v<version>.apk` plus versioned forum-thread/reply BBCode assets, and uses `RELEASE_NOTES.md` as the release body. Manual releases must be dispatched from `main`. If the generated forum files changed, the workflow refreshes the copy-ready files on `main` after publication.\n\nAfter publishing, copy `docs/forum/LATEST_RELEASE_REPLY.bbcode` into the existing GMMP forum thread and update its first post from `docs/forum/THREAD_START.bbcode` whenever the overview or compatibility information changed. The suggested subject is in `docs/forum/THREAD_SUBJECT.txt`.\n''',
)

# Persistent collaboration/release rules.
agents = ROOT / "AGENTS.md"
replace_once(
    agents,
    '''- `docs/ROADMAP.md`: public planned direction for upcoming releases; keep it concise and user-facing.\n- Feature docs: detailed user/developer behavior.\n''',
    '''- `docs/ROADMAP.md`: public planned direction for upcoming releases; keep it concise and user-facing.\n- `docs/forum/`: copy-ready GMMP forum thread/release BBCode. `THREAD_START_TEMPLATE.bbcode` is maintained source; `THREAD_START.bbcode` and `LATEST_RELEASE_REPLY.bbcode` are generated by `scripts/generate_forum_posts.py` and must not be hand-edited.\n- Feature docs: detailed user/developer behavior.\n''',
)
replace_once(
    agents,
    '''10. the merged `main` head is green before tagging/publishing.\n\nAfter 0.4.0, new features should start on a normal named feature branch from the chosen clean `main` integration point.\n''',
    '''10. the merged `main` head is green before tagging/publishing;\n11. GMMP forum copy is current: update `THREAD_START_TEMPLATE.bbcode` when public features/compatibility change, regenerate via `python3 scripts/generate_forum_posts.py`, and review the generated first-post and release-reply BBCode;\n12. the release workflow publishes versioned forum BBCode assets and keeps the copy-ready `docs/forum` outputs synchronized for manual main releases.\n\nAfter 0.4.0, new features should start on a normal named feature branch from the chosen clean `main` integration point.\n''',
)

print("Applied GMMP update warning and forum release workflow changes.")
