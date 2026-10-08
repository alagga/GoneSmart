#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


# app/build.gradle.kts -----------------------------------------------------------
path = ROOT / "app/build.gradle.kts"
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''val releaseSigningConfigured =\n    !releaseStoreFilePath.isNullOrBlank() &&\n        !releaseStorePassword.isNullOrBlank() &&\n        !releaseKeyAlias.isNullOrBlank() &&\n        !releaseKeyPassword.isNullOrBlank()\n\nandroid {\n''',
    '''val releaseSigningConfigured =\n    !releaseStoreFilePath.isNullOrBlank() &&\n        !releaseStorePassword.isNullOrBlank() &&\n        !releaseKeyAlias.isNullOrBlank() &&\n        !releaseKeyPassword.isNullOrBlank()\n\nfun gitOutput(vararg args: String): String? =\n    runCatching {\n        val process = ProcessBuilder(listOf("git", *args))\n            .directory(rootProject.projectDir)\n            .redirectErrorStream(true)\n            .start()\n        val output = process.inputStream\n            .bufferedReader()\n            .use { it.readText() }\n            .trim()\n        if (process.waitFor() == 0) output else null\n    }.getOrNull()\n\nval gitHead =\n    gitOutput("rev-parse", "--short=12", "HEAD")\n        ?.takeUnless(String::isBlank)\n        ?: "unknown"\n\nval gitDirty =\n    gitOutput("status", "--porcelain")\n        ?.isNotBlank() == true\n\nval buildRevision =\n    if (gitDirty) "$gitHead-dirty" else gitHead\n\nandroid {\n''',
    "git revision helper",
)
text = replace_once(
    text,
    '''        buildConfigField(\n            "String",\n            "LASTFM_API_KEY",\n            "\\\"$lastFmApiKey\\\""\n        )\n''',
    '''        buildConfigField(\n            "String",\n            "LASTFM_API_KEY",\n            "\\\"$lastFmApiKey\\\""\n        )\n        buildConfigField(\n            "String",\n            "GIT_REVISION",\n            "\\\"$buildRevision\\\""\n        )\n''',
    "BuildConfig git revision",
)
path.write_text(text, encoding="utf-8")


# GoneSmartModule.kt ------------------------------------------------------------
path = ROOT / "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''            "GMMP COMPAT PROBE | revision=$COMPAT_PROBE_REVISION" +\n                " | moduleVersion=${BuildConfig.VERSION_NAME}" +\n                " | buildDebug=${BuildConfig.DEBUG}"\n''',
    '''            "GMMP COMPAT PROBE | revision=$COMPAT_PROBE_REVISION" +\n                " | moduleVersion=${BuildConfig.VERSION_NAME}" +\n                " | git=${BuildConfig.GIT_REVISION}" +\n                " | buildDebug=${BuildConfig.DEBUG}"\n''',
    "startup build identity",
)
text = replace_once(
    text,
    '''        playlistBridgeInfo(\n            "BRIDGE READY | hooks=$installed | bindings=$bindingsReady"\n        )\n''',
    '''        playlistBridgeInfo(\n            "BRIDGE READY | hooks=$installed | bindings=$bindingsReady" +\n                " | enabled=${playlistBridgeController.isEnabled()}" +\n                " | git=${BuildConfig.GIT_REVISION}"\n        )\n''',
    "bridge readiness build identity",
)
path.write_text(text, encoding="utf-8")


# PlaylistBridgeController.kt ---------------------------------------------------
path = ROOT / "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt"
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''        presenterRef = WeakReference(presenter)\n        context?.let { contextRef = WeakReference(it) }\n        if (enabled) {\n            Log.i(TAG, "BRIDGE PRESENTER | captured=${presenter.javaClass.name}")\n        }\n''',
    '''        presenterRef = WeakReference(presenter)\n        context?.let { contextRef = WeakReference(it) }\n        Log.i(\n            TAG,\n            "BRIDGE PRESENTER | captured=${presenter.javaClass.name}" +\n                " | enabled=$enabled" +\n                " | git=${BuildConfig.GIT_REVISION}"\n        )\n''',
    "presenter build identity",
)
path.write_text(text, encoding="utf-8")


# AGENTS.md ---------------------------------------------------------------------
path = ROOT / "AGENTS.md"
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''## 10. Logging and diagnostics\n\nNormal logs record meaningful runtime decisions, resolver success/failure, verified mutation/rollback, Track Auto-DJ phases, provider/fallback decisions and user-visible failures.\n\nBroad class/method/recycler inventories are gated to unknown/failing compatibility boundaries. Do not hide native GMMP tags such as `w6`; remove GoneSmart-caused unnecessary work instead.\n''',
    '''## 10. Logging and diagnostics\n\nNormal logs record meaningful runtime decisions, resolver success/failure, verified mutation/rollback, Track Auto-DJ phases, provider/fallback decisions and user-visible failures.\n\nEvery device-debug build exposes the exact local Git revision through `BuildConfig.GIT_REVISION`; local uncommitted source is suffixed `-dirty`. The startup compatibility line and Playlist Link readiness/presenter logs include that revision. Before interpreting a device failure or asking for another probe build, verify that the captured runtime revision matches the intended branch head. A missing/mismatched revision means build identity is unverified and must be resolved before changing feature logic.\n\nBroad class/method/recycler inventories are gated to unknown/failing compatibility boundaries. Do not hide native GMMP tags such as `w6`; remove GoneSmart-caused unnecessary work instead.\n''',
    "AGENTS build identity rule",
)
path.write_text(text, encoding="utf-8")


# docs/GMMP_COMPATIBILITY_PLAYBOOK.md ------------------------------------------
path = ROOT / "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''### Detect\n\nCompare the installed GMMP version with the tested version. Missing/unknown versions are never green Compatibility.\n\n### Run one bounded read-only self-test\n''',
    '''### Detect\n\nCompare the installed GMMP version with the tested version. Missing/unknown versions are never green Compatibility. For every device acceptance/debug run, first verify the runtime `BuildConfig.GIT_REVISION` against the intended GoneSmart branch head; a `-dirty` suffix identifies local uncommitted source. Do not diagnose a feature regression from a log whose GoneSmart build identity is absent or mismatched.\n\n### Run one bounded read-only self-test\n''',
    "playbook build identity gate",
)
path.write_text(text, encoding="utf-8")

print("Applied durable runtime Git revision logging")
