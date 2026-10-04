from pathlib import Path
import re

path = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
text = path.read_text()


def sub_once(pattern: str, replacement: str, label: str, flags: int = 0) -> None:
    global text
    text, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")


sub_once(
    r'(        private const val COMPAT_PROBE_REVISION\s*=\s*\n\s*"gmmp421-r25"\s*\n)',
    r'''\1
        // Broad reflection/recycler inventories were needed while 4.2.1
        // was unknown. Keep the machinery for a future compatibility
        // investigation, but do not flood normal accepted-version logs.
        private const val ENABLE_DEEP_COMPAT_DIAGNOSTICS = false
''',
    "compat revision anchor",
)

sub_once(
    r'''        compatibilityExecutor\.execute\s*\{\s*\n\s*logCompatibilityStaticInventory\(param\.classLoader\)\s*\n\s*logCompatibilitySelfTest\(param\.classLoader\)\s*\n\s*\}''',
    '''        compatibilityExecutor.execute {
            if (ENABLE_DEEP_COMPAT_DIAGNOSTICS) {
                logCompatibilityStaticInventory(param.classLoader)
            }
            logCompatibilitySelfTest(param.classLoader)
        }''',
    "compat startup block",
)

sub_once(
    r'''(    private fun scheduleCompatibilityRuntimeInstance\(\s*\n\s*marker: String,\s*\n\s*instance: Any\?\s*\n\s*\)\s*\{\s*\n)''',
    r'''\1        if (!ENABLE_DEEP_COMPAT_DIAGNOSTICS) return
''',
    "runtime diagnostic function",
)

sub_once(
    r'''(    private fun logCompatibilityRecyclerAdapter\(\s*\n\s*view: android\.view\.View\?,\s*\n\s*adapterHint: Any\?,\s*\n\s*source: String\s*\n\s*\)\s*\{\s*\n)''',
    r'''\1        if (!ENABLE_DEEP_COMPAT_DIAGNOSTICS) return
''',
    "recycler diagnostic function",
)

sub_once(
    r'''        checks\["flipQueue"\] = result \{.*?(?=        checks\["playlistPlayback"\] = result \{)''',
    '        checks["flipQueue"] = "OPEN_CURRENT_POSITION_WRITER"\n\n',
    "stale flip self-test block",
    re.S,
)

path.write_text(text)
