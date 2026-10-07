from pathlib import Path
p = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
s = p.read_text()
old = '''        val queue = nativeQueue?.get()
            ?: nativeAutoDj?.get()?.let { field(it, "q") }
            ?: run {
                Log.e(TAG, "MIX ISOLATE | native queue not captured")
                return null
            }
'''
new = '''        val queue = nativeQueue?.get()
            ?.takeIf { it.javaClass.name == "ex3" }
            ?: nativeAutoDj?.get()?.let { field(it, "q") }
                ?.takeIf { it.javaClass.name == "ex3" }
            ?: run {
                Log.e(TAG, "MIX ISOLATE | legacy ex3 queue not captured")
                return null
            }
'''
assert old in s, 'legacy isolate queue fallback changed unexpectedly'
s = s.replace(old, new, 1)
p.write_text(s)
