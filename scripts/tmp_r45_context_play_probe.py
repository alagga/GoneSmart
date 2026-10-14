from pathlib import Path
import subprocess


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)

controller = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt")
text = controller.read_text()
anchor = '''    /**
     * Called from the proven native qr.z refill hook before GMMP materializes
     * its Auto-DJ selection rows. Only a refill whose native Current already
     * matches the pre-Play-resolved target may consume bootstrap Track IDs.
     */
    fun bootstrapSelectionForNativeRefill('''
method = '''    /**
     * r45 bounded read-only mapping for GMMP 4.2.1's context-action
     * MenuItem,List -> boolean boundary. It runs only while the native Play
     * dispatched by this Track Mix request is settling. No argument is
     * modified and no host method is invoked from this observer.
     */
    fun observeNativeContextPlayBoundary(
        menuItem: MenuItem?,
        nativeItems: List<*>?
    ) {
        val request = pending ?: return
        if (request.stage != "WAIT_PLAY") return
        val item = menuItem ?: return
        val playId = request.context.resources.getIdentifier(
            "menuContextPlay", "id", GMMP_PACKAGE
        )
        if (playId == 0 || item.itemId != playId) return
        val items = nativeItems.orEmpty().filterNotNull()
        val classes = items.map { it.javaClass.name }.distinct().take(6)
        val autoDj = nativeAutoDj?.get()
        val resolution = if (autoDj != null && items.isNotEmpty()) {
            runCatching {
                resolvePrePlayTarget(autoDj, items)
            }.getOrNull()
        } else null
        val expected = request.prePlayTarget?.id
        Log.i(
            TAG,
            "MIX CONTEXT PLAY BOUNDARY | observed=true | item=${item.itemId} | " +
                "listSize=${items.size} | elementClasses=${classes.joinToString(",")} | " +
                "resolvedTrack=${resolution?.track?.id ?: -1L} | " +
                "expectedTrack=${expected ?: -1L} | readOnly=true"
        )
    }

'''
text = replace_once(text, anchor, method + anchor, "TrackMix context boundary observer")
controller.write_text(text)

module = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
text = module.read_text()
anchor = '''        // current queue_position. Bundle service-event and Auto-DJ child
        // writer observation in one device pass. No candidate is invoked by
        // discovery; every hook only wraps a call GMMP makes naturally.
        runCatching {
            val serviceClass = param.classLoader.loadClass(
                "gonemad.gmmp.playback.service.MusicService"
            )'''
hook = '''        // r45: 4.2.1 reused the historical class name ex3 for a context-
        // action handler. Its only declared MenuItem,List -> boolean method is
        // a useful pre-materialization mapping candidate. Observe the exact
        // native Play call only while Track Mix is pending; never mutate the
        // list and never invoke this method ourselves.
        runCatching {
            val contextActionClass = param.classLoader.loadClass("ex3")
            val candidates = contextActionClass.declaredMethods.filter { method ->
                method.parameterCount == 2 &&
                    android.view.MenuItem::class.java.isAssignableFrom(
                        method.parameterTypes[0]
                    ) &&
                    java.util.List::class.java.isAssignableFrom(
                        method.parameterTypes[1]
                    ) &&
                    (method.returnType == Boolean.TYPE ||
                        method.returnType == java.lang.Boolean::class.java)
            }
            check(candidates.size == 1) {
                "Expected one context Play boundary candidate, found ${candidates.size}"
            }
            val method = candidates.single().apply { isAccessible = true }
            hook(method).intercept { chain ->
                trackMixController.observeNativeContextPlayBoundary(
                    chain.getArg(0) as? android.view.MenuItem,
                    chain.getArg(1) as? java.util.List<*>
                )
                chain.proceed()
            }
            Log.i(
                "GoneSmartTrackMix",
                "MIX CONTEXT PLAY OBSERVER READY | method=" +
                    method.declaringClass.name + "." + method.name +
                    "(MenuItem,List) | readOnly=true"
            )
        }.onFailure {
            Log.w(TAG, "Track Mix context Play observer unavailable", it)
        }

'''
text = replace_once(text, anchor, hook + anchor, "module context Play hook")
module.write_text(text)

agents = Path("AGENTS.md")
body = agents.read_text()
marker = "- r45 carries one bounded read-only mapping observer"
if marker not in body:
    body += '''\n- r45 carries one bounded read-only mapping observer for GMMP 4.2.1's unique declared `MenuItem,List -> boolean` context-action boundary (`ex3.r` on the tested build). It is request-local, observes only native Play, does not mutate arguments, and is intended to determine whether this pre-materialization boundary can replace the lost 4.2.0 queue-owner transaction. Retire it after one conclusive device trace.\n'''
    agents.write_text(body)

subprocess.run(["git", "diff", "--check"], check=True)
