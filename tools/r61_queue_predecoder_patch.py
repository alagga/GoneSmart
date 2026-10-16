from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt",
    '''    fun usePredecoderCompatibility(\n        source: String,\n        legacyQueue: Boolean\n    ): Boolean =\n        !legacyQueue && source != "menu_gm_context_queue"\n''',
    '''    fun usePredecoderCompatibility(\n        source: String,\n        legacyQueue: Boolean\n    ): Boolean {\n        // R61 device evidence proved Queue-context has the same 4.2.1\n        // predecoder timing requirement as the other song-menu starts. Keep\n        // source in the policy API for diagnostics/future source-specific gates.\n        return !legacyQueue\n    }\n'''
)

replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt",
    "@Test fun nonQueue421StartsUseThePredecoderCompatibilityPort()",
    "@Test fun all421SongMenuStartsUseThePredecoderCompatibilityPort()"
)
replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt",
    '''        assertFalse(\n            TrackMixInitialFillPolicy.usePredecoderCompatibility(\n                source = "menu_gm_context_queue",\n                legacyQueue = false\n            )\n        )\n''',
    '''        assertTrue(\n            TrackMixInitialFillPolicy.usePredecoderCompatibility(\n                source = "menu_gm_context_queue",\n                legacyQueue = false\n            )\n        )\n'''
)

replace_once(
    "AGENTS.md",
    "- For non-Queue song-menu Track Auto-DJ, restore the proven predecoder compatibility path:",
    "- For every GMMP 4.2.1 song-menu Track Auto-DJ start, including `menu_gm_context_queue`, restore the proven predecoder compatibility path:"
)
replace_once(
    "AGENTS.md",
    "- `menu_gm_context_queue` deliberately stays on the later R59 native-ownership path. Earlier predecoder state manipulation was device-problematic for Queue-context starts, while the latest Queue test already built the correct queue; the post-decode replay that caused its second-track freeze has been removed.",
    "- R61 device evidence retires the R59 Queue-context exception. The Queue start independently verified the exact selected seed, the accepted Current writer, seed isolation from absolute position 5 to logical position 1, and deletion of the four old rows; the failure was timing only: GMMP queried position 2 and cached `next audio source is null` before the four native fallback followers existed. Queue-context therefore uses the same natural `qr.z(int)` predecoder ordering as the successful R60 song-menu path. Do not repair this later through a playback replay."
)

replace_once(
    "docs/GMMP_COMPATIBILITY_PLAYBOOK.md",
    "For non-Queue song-menu starts, the compatibility port therefore restores",
    "For all GMMP 4.2.1 song-menu starts, including Queue-context starts, the compatibility port therefore restores"
)
replace_once(
    "docs/GMMP_COMPATIBILITY_PLAYBOOK.md",
    "Queue-context starts remain on the R59 native-ownership path because that path already produces the correct queue and earlier predecoder state writes were not accepted there.",
    "The 2026-10-16 R61 device trace retires the R59 Queue-context exception. That run independently verified the selected queue target, Current writer, seed isolation/rebase and final five-row native fallback queue, but GMMP reached `onTransitionComplete`, queried queue position 2 and cached `next audio source is null` before those four followers had been materialized. The later visible queue was correct but arrived after the next-source boundary. Queue-context Track Auto-DJ therefore now uses the same natural `qr.z(int)` predecoder ordering as the R60 path; the mutation semantics are unchanged, only their placement before decoder materialization changes. One Queue-context device re-acceptance remains required."
)
