from pathlib import Path
import re
import subprocess

BASE = "473554043f047b6b0c82ef39452e915f8f3e56c5"


def run(*args: str) -> None:
    subprocess.run(args, check=True)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# r44 changed the Track Mix architecture, not merely 4.2.1 bindings. Restore
# its immediate pre-r44 source baseline first, then apply only the bounded r45
# compatibility corrections below.
restore = [
    "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt",
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
]
run("git", "checkout", BASE, "--", *restore)

for added in [
    Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixNativeFallbackPolicy.kt"),
    Path("app/src/test/java/io/github/alagga/gonesmart/TrackMixNativeFallbackPolicyTest.kt"),
]:
    if added.exists():
        added.unlink()

module = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
text = module.read_text()
text = replace_once(
    text,
    '        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r39"',
    '        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r45"',
    "compat revision",
)
text = replace_once(
    text,
    '        private const val TRACK_MIX_SMART_REMAINDER_WAIT_SECONDS =\n            12L',
    '        private const val TRACK_MIX_SMART_REMAINDER_WAIT_SECONDS =\n            60L',
    "Track Mix prepare timeout",
)
text = replace_once(
    text,
    '        return TrackMixPreparedInitialPool(sessionId, preparedIds)\n    }',
    '''        if (preparedIds.isEmpty()) {
            // Keep v0.3.2/4.2.0 semantics: zero GoneSmart matches are not a
            // successful smart pool. r44 incorrectly promoted native random
            // candidates to a prepared Track Mix pool, which made the badge
            // green and enabled post-materialization queue retargeting.
            playerBadgeController.setMode(
                PlayerAutoDjBadgeController.Mode.FALLBACK
            )
            runtimeReporter.report(
                mode = GoneSmartRuntimeContract.MODE_FALLBACK,
                message =
                    "No suitable GoneSmart match for Track Mix. " +
                        "GMMP playback was left untouched.",
                appendEvent = true
            )
        }
        return TrackMixPreparedInitialPool(sessionId, preparedIds)
    }''',
    "zero-match fallback status",
)
module.write_text(text)

controller = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt")
text = controller.read_text()
needle = '''                Log.i(
                    TAG,
                    "MIX PREPLAY SMART POOL | target=${resolution.track.id} | " +
                        "requested=$requiredUpcoming | prepared=${request.prePlayPrepared.trackIds.size}"
                )
            }

            main.post {'''
replacement = '''                Log.i(
                    TAG,
                    "MIX PREPLAY SMART POOL | target=${resolution.track.id} | " +
                        "requested=$requiredUpcoming | prepared=${request.prePlayPrepared.trackIds.size}"
                )
                if (
                    requiredUpcoming > 0 &&
                    request.prePlayPrepared.trackIds.isEmpty()
                ) {
                    // GMMP 4.2.1 can already have a stale current+1
                    // AudioSource cached while native Play is settling. Do
                    // not dispatch Play and then mutate that materialized row
                    // when GoneSmart has no real recommendation pool.
                    Log.w(
                        TAG,
                        "MIX PREPLAY FALLBACK | no GoneSmart matches; " +
                            "native Play not dispatched | failClosed=true"
                    )
                    if (pending === request) pending = null
                    main.post {
                        fail(
                            "No suitable GoneSmart matches were found for this song.",
                            context
                        )
                    }
                    return@execute
                }
            }

            main.post {'''
text = replace_once(text, needle, replacement, "pre-Play zero-match guard")
controller.write_text(text)

agents = Path("AGENTS.md")
text = agents.read_text()
text, removed = re.subn(
    r'\n## GMMP 4\.2\.1 Track Mix native-fallback continuity \(r44\)\n.*?(?=\n## |\Z)',
    "\n",
    text,
    count=1,
    flags=re.S,
)
if removed != 1:
    raise SystemExit(f"AGENTS r44 section: expected one section, removed {removed}")
text += '''\n## GMMP 4.2.1 Track Mix architecture correction (r45)\n\n- The accepted behavioral baseline is the v0.3.2 Smart-DJ pipeline plus the last known-good GMMP 4.2.0 Track Auto-DJ implementation (`fcc9a9003a0b64196ac61b04e53d003dc056ab92`). Porting to 4.2.1 may change native bindings/timing, not selection semantics.\n- r44 native-fallback preselection/retarget is retired. Native GMMP candidates must never be reclassified as successful GoneSmart matches, and an already materialized `current+1` Track ID must never be rewritten.\n- GMMP 4.2.1 eagerly prepares `current+1` during native Play, so selected-seed GoneSmart preparation remains before Play. Use the established 60 s Smart-DJ preparation bound rather than falling back while the same provider/local-match future is still running.\n- If Track Mix has zero real GoneSmart matches, set FALLBACK/red state and fail closed before native Play. Leave playback/queue untouched until a 4.2.1 native seed-isolation boundary equivalent to the accepted 4.2.0 transaction is independently proven.\n- r35 exact-path CURRENT rebind and r37 pinned `queue_id` bootstrap remain compatibility adaptations; neither changes recommendation ownership or permits post-materialization Track-ID mutation.\n'''
agents.write_text(text)

for path in [
    Path("docs/GMMP_COMPATIBILITY_PLAYBOOK.md"),
    Path("docs/GMMP_421_COMPLETION.md"),
    Path("docs/GMMP_421_REACCEPTANCE_CHECKLIST.md"),
]:
    body = path.read_text()
    marker = "GMMP 4.2.1 Track Mix architecture correction (r45)"
    if marker not in body:
        body += f'''\n\n## {marker}\n\nThe 2026-10-14 r44 device trace proved that native-fallback preselection plus post-materialization `current+1` retargeting violates the accepted playback ownership model: BUNT. had zero GoneSmart recommendations but was presented as a successful four-track prepared pool, and the queue row for slot 2 diverged from GMMP's already cached decoder AudioSource. r45 retires that path. Track Mix again waits on the same provider/local-match pipeline up to the established 60 s preparation bound. A zero real-match result is FALLBACK/red and fails closed before native Play, leaving queue/audio state untouched. Device re-acceptance must first prove Levels uses real GoneSmart matches again; BUNT. must no longer turn green or mutate playback when the smart pool is empty. A native no-match fallback may be restored only after the 4.2.1 equivalent of the accepted 4.2.0 seed-isolation boundary is independently proven before decoder materialization.\n'''
    path.write_text(body)

r44 = Path("docs/GMMP_421_QUEUE_R44.md")
if r44.exists():
    body = r44.read_text()
    retired = "> **Retired by r45:** device evidence showed queue/decoder divergence and false-success fallback semantics. Keep this file as historical evidence only; do not re-enable its post-materialization current+1 retarget design.\n\n"
    if not body.startswith("> **Retired by r45:**"):
        r44.write_text(retired + body)

# Guard the architectural correction itself.
current = controller.read_text()
module_text = module.read_text()
for forbidden in [
    "primeNativeFallbackCurrentPlusOne",
    "finalizeNativeFallbackPreparedQueue",
    "TrackMixPreparedOrigin.NATIVE_FALLBACK",
]:
    if forbidden in current:
        raise SystemExit(f"retired r44 controller path still present: {forbidden}")
if "preselectTrackMixNativeFallback" in module_text:
    raise SystemExit("retired r44 native fallback preselection still present")
if "retargetTrackMixPreloadedNext" in Path("app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt").read_text():
    raise SystemExit("retired r44 post-materialization retarget still present")
if "TRACK_MIX_SMART_REMAINDER_WAIT_SECONDS =\n            60L" not in module_text:
    raise SystemExit("Track Mix did not restore 60 s prepare bound")
if "gmmp421-r45" not in module_text:
    raise SystemExit("r45 compat marker missing")
