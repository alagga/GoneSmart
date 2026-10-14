from pathlib import Path
import re

p = Path("docs/GMMP_421_REACCEPTANCE_CHECKLIST.md")
t = p.read_text()
t = t.replace(
    "This checklist is the single device pass for the current **r44** Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.\n\nRepair source commit: `ab139568db9df78f49929e6621ca7c222c5fe359`.",
    "This checklist is the single device pass for the current **r45** Playlist Link / Track Mix architecture-correction and read-only mapping candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.\n\nArchitecture correction source: `019c837ce624cdb721621b95829a8080314b1dcf`.\n\nRead-only 4.2.1 context-Play observer source: `6ea2c00f649a524ae0132da1fbe77470d5737815`."
)
t = t.replace("Compatibility startup log reports revision `gmmp421-r44`;", "Compatibility startup log reports revision `gmmp421-r45`;")

start = t.index("## Track Mix\n")
end_marker = "Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK."
end = t.index(end_marker, start) + len(end_marker)
new = '''## Track Mix

1. Start Track Auto-DJ on Avicii `Levels (Radio Edit)` first. This is the positive SMART-path regression.
2. Verify `MIX CLICK`, `MIX PREPLAY AUTO-DJ | available=true`, and `MIX PREPLAY TARGET | resolved=true | trackId=3737`.
3. The pre-Play smart preparation may wait up to the established 60 s bound. It must finish with real GoneSmart local matches; `origin=NATIVE_FALLBACK`, `SMART DJ TRACK MIX NATIVE PRESELECT`, `MIX NATIVE FALLBACK PRELOAD`, and post-materialization Track-ID retarget markers are forbidden.
4. `MIX PREPLAY SMART POOL` must occur before `MIX START | ... nativePlay=dispatched`. Prepared GoneSmart IDs must continue through the accepted target-owned `qr.z(int)` bootstrap before GMMP materializes/caches the following AudioSource.
5. During this Levels request, the one-shot r45 observer may log `MIX CONTEXT PLAY BOUNDARY ... readOnly=true`. If present, record `listSize`, element classes, `resolvedTrack`, and `expectedTrack`. The observer must not change playback, arguments, queue rows, or method invocation order.
6. Let Levels cross naturally into Track 2. Queue metadata and the opened decoder/audio path must agree.
7. Then invoke Track Auto-DJ on the known zero-provider BUNT. / Røam `Nothing Lasts Forever` row. It must finish smart preparation with zero real GoneSmart matches, switch the GoneSmart badge/runtime to FALLBACK/red, log `MIX PREPLAY FALLBACK ... native Play not dispatched | failClosed=true`, and leave the currently playing audio plus queue untouched.
8. BUNT. must not turn green and must not emit `MIX START`, `SMART DJ TRACK MIX NATIVE PRESELECT`, `MIX NATIVE FALLBACK PRELOAD`, `TRACK MIX PRELOAD NEXT`, post-materialization Track-ID writes, `CLEAR_QUEUE`, or manual replay/refresh of a MusicService next-source boundary.
9. Do not treat BUNT. native-fallback playback as accepted in r45. Restoring that behavior requires independent proof of a 4.2.1 pre-materialization native boundary equivalent in ownership semantics to the accepted 4.2.0 queue-owner transaction; direct generated-DAO mutation is not sufficient evidence.
10. Playlist Link and the accepted span-preserving **Smart Playlist:** label remain regression invariants and need no repeated exploratory changes.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.'''
t = t[:start] + new + t[end:]

# Clearly retire the r44 expectations rather than leaving contradictory current criteria.
t = t.replace("## r44 regression focus\n", "## Historical r44 regression focus — retired by r45\n")

p.write_text(t)
