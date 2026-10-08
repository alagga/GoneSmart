#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


# --- PlaylistBridgeController.kt -------------------------------------------------
controller_path = ROOT / "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt"
controller = controller_path.read_text(encoding="utf-8")

# The upstream boolean presenter action is proven only on the accepted 4.2.0
# mapping (ds4.g2(boolean)). 4.2.1 is correlated at the downstream as4$g.accept
# callback instead, so the presenter method must be optional.
controller = controller.replace(
    "        val presenterLinkSmartPlaylist: Method,\n",
    "        val presenterLinkSmartPlaylist: Method?,\n",
)
if controller.count("val presenterLinkSmartPlaylist: Method?") != 2:
    raise RuntimeError("expected nullable presenterLinkSmartPlaylist in Bindings and HookTargets")

controller = replace_once(
    controller,
    '''    @Volatile private var presenterRef: WeakReference<Any>? = null\n    @Volatile private var contextRef: WeakReference<Context>? = null\n    private val nativeSmartChooserBypass = ThreadLocal.withInitial { false }\n''',
    '''    @Volatile private var presenterRef: WeakReference<Any>? = null\n    @Volatile private var contextRef: WeakReference<Context>? = null\n    private val nativeSmartChooserBypass = ThreadLocal.withInitial { false }\n\n    private data class NativeSmartChooserContinuation(\n        val callback: Any,\n        val payload: Any?\n    )\n\n    @Volatile\n    private var nativeSmartChooserContinuation: NativeSmartChooserContinuation? = null\n''',
    "native chooser continuation state",
)

controller = replace_once(
    controller,
    '''        if (!value) cache.clear()\n        Log.i(TAG, "BRIDGE SETTINGS | enabled=$value")\n''',
    '''        if (!value) {\n            cache.clear()\n            nativeSmartChooserContinuation = null\n        }\n        Log.i(TAG, "BRIDGE SETTINGS | enabled=$value")\n''',
    "clear continuation when disabled",
)

controller = replace_once(
    controller,
    '''        // Keep GMMP's one ORIGINAL toolbar action/icon. Its click now opens\n        // the host AppCompat popup menu below that exact action item and\n        // dispatches either back into ds4.g2(false) or Playlist Bridge.\n''',
    '''        // Keep GMMP's one ORIGINAL toolbar action/icon. Its click now opens\n        // the host AppCompat popup menu below that exact action item and\n        // dispatches either back into the proven native continuation or\n        // Playlist Bridge.\n''',
    "menu comment",
)

controller = replace_once(
    controller,
    '''                        when (selected?.order) {\n                            0 -> openNativeSmartPlaylistChooser()\n                            1 -> openChooser(edit = false)\n                        }\n''',
    '''                        when (selected?.order) {\n                            0 -> openNativeSmartPlaylistChooser()\n                            1 -> {\n                                nativeSmartChooserContinuation = null\n                                openChooser(edit = false)\n                            }\n                        }\n''',
    "type menu dispatch",
)

old_native_chooser = '''    private fun openNativeSmartPlaylistChooser() {\n        val native = bindings ?: return\n        val presenter = presenterRef?.get() ?: run {\n            Log.w(TAG, "BRIDGE SMART CHOOSER | no active SmartEditorPresenter")\n            return\n        }\n        runCatching {\n            nativeSmartChooserBypass.set(true)\n            try {\n                native.presenterLinkSmartPlaylist.invoke(presenter, false)\n            } finally {\n                nativeSmartChooserBypass.set(false)\n            }\n        }.onFailure {\n            Log.e(\n                TAG,\n                "BRIDGE SMART CHOOSER | original native linker failed",\n                it\n            )\n        }\n    }\n'''
new_native_chooser = '''    private fun openNativeSmartPlaylistChooser() {\n        val native = bindings ?: return\n\n        // GMMP 4.2.1: resume the exact native callback instance/payload that\n        // was naturally reached by the real toolbar action. This keeps the\n        // original async result/event chain intact instead of guessing an\n        // upstream obfuscated presenter method.\n        val continuation = nativeSmartChooserContinuation\n        val accept = native.chooserConsumerAccept\n        if (\n            continuation != null &&\n            accept != null &&\n            accept.declaringClass.isInstance(continuation.callback)\n        ) {\n            nativeSmartChooserContinuation = null\n            runCatching {\n                nativeSmartChooserBypass.set(true)\n                try {\n                    accept.invoke(continuation.callback, continuation.payload)\n                } finally {\n                    nativeSmartChooserBypass.set(false)\n                }\n            }.onFailure {\n                Log.e(\n                    TAG,\n                    "BRIDGE SMART CHOOSER | captured native callback failed",\n                    it\n                )\n            }\n            return\n        }\n        nativeSmartChooserContinuation = null\n\n        // Accepted GMMP 4.2.0 fallback. Do not structurally substitute another\n        // boolean method on remapped versions: only ds4.g2(boolean) is proven.\n        val legacyAction = native.presenterLinkSmartPlaylist ?: run {\n            Log.w(\n                TAG,\n                "BRIDGE SMART CHOOSER | no proven native continuation available"\n            )\n            return\n        }\n        val presenter = presenterRef?.get() ?: run {\n            Log.w(TAG, "BRIDGE SMART CHOOSER | no active SmartEditorPresenter")\n            return\n        }\n        runCatching {\n            nativeSmartChooserBypass.set(true)\n            try {\n                legacyAction.invoke(presenter, false)\n            } finally {\n                nativeSmartChooserBypass.set(false)\n            }\n        }.onFailure {\n            Log.e(\n                TAG,\n                "BRIDGE SMART CHOOSER | original native linker failed",\n                it\n            )\n        }\n    }\n'''
controller = replace_once(
    controller,
    old_native_chooser,
    new_native_chooser,
    "native chooser continuation",
)

controller = replace_once(
    controller,
    '''    fun shouldBypassNativeLinkHook(): Boolean =\n        nativeSmartChooserBypass.get()\n\n    private fun decoratedPlaylistTitle(context: Context): CharSequence {\n''',
    '''    fun shouldBypassNativeLinkHook(): Boolean =\n        nativeSmartChooserBypass.get()\n\n    /**\n     * GMMP 4.2.1 dispatch boundary proven from the naturally opened native\n     * MaterialDialog stack: as4$g.accept(...). Store that exact callback and\n     * payload only while GoneSmart owns an add-link choice. Native edit paths\n     * and disabled mode pass through unchanged.\n     */\n    fun interceptNativeChooserCallback(\n        callback: Any?,\n        payload: Any?\n    ): Boolean {\n        if (!enabled || callback == null || nativeSmartChooserBypass.get()) {\n            return false\n        }\n        val native = bindings ?: return false\n        if (native.presenterLinkSmartPlaylist != null) {\n            // Accepted 4.2.0 keeps its exact ds4.g2(boolean) boundary.\n            return false\n        }\n        val accept = native.chooserConsumerAccept ?: return false\n        if (!accept.declaringClass.isInstance(callback)) return false\n        val presenter = presenterRef?.get() ?: return false\n        if (!native.presenterClass.isInstance(presenter)) return false\n\n        // The editor's native selected-rule state is the semantic distinction\n        // between add and edit. Do not infer the old boolean argument from an\n        // unrelated remapped method signature.\n        val edit = selectedRule(presenter) != null\n        if (!edit) {\n            nativeSmartChooserContinuation =\n                NativeSmartChooserContinuation(callback, payload)\n        }\n\n        val intercepted = interceptNativeLinkAction(presenter, edit)\n        if (edit || !intercepted) {\n            nativeSmartChooserContinuation = null\n        }\n        Log.i(\n            TAG,\n            "BRIDGE LINK DISPATCH | proven chooser callback" +\n                " | edit=$edit | intercepted=$intercepted"\n        )\n        return intercepted\n    }\n\n    private fun decoratedPlaylistTitle(context: Context): CharSequence {\n''',
    "callback interception method",
)

controller = replace_once(
    controller,
    '''        val presenterLinkSmartPlaylist = r.method(\n            presenterClass,\n            listOf("g2"),\n            "native linked-Smart-Playlist action"\n        ) { method ->\n            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&\n                method.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))\n        }\n''',
    '''        // Only the accepted GMMP 4.2.0 boundary is authoritative here.\n        // On 4.2.1 as4 exposes h2(boolean), but the real user action was\n        // passively correlated downstream at as4$g.accept(...); never promote\n        // h2 solely because it has the old boolean shape.\n        val presenterLinkSmartPlaylist = if (presenterClass.name == "ds4") {\n            presenterClass.declaredMethods.singleOrNull { method ->\n                method.name == "g2" &&\n                    !java.lang.reflect.Modifier.isStatic(method.modifiers) &&\n                    method.parameterTypes.contentEquals(\n                        arrayOf(java.lang.Boolean.TYPE)\n                    ) &&\n                    method.returnType == java.lang.Void.TYPE\n            }?.apply { isAccessible = true }\n                ?: throw IllegalStateException(\n                    "Accepted ds4.g2(boolean) link action is unavailable"\n                )\n        } else {\n            null\n        }\n''',
    "exact legacy presenter link boundary",
)

controller = replace_once(
    controller,
    '''            "BRIDGE MAPPING | presenter=${presenterClass.name}" +\n                " | leaf=${smartRuleClass.name}" +\n                " | smart=${smartPlaylistClass.name}" +\n                " | parser=${parserClass.name}" +\n                " | dao=${playlistDaoClass.name}" +\n                " | predicate=${predicateClass.name}"\n''',
    '''            "BRIDGE MAPPING | presenter=${presenterClass.name}" +\n                " | leaf=${smartRuleClass.name}" +\n                " | smart=${smartPlaylistClass.name}" +\n                " | parser=${parserClass.name}" +\n                " | dao=${playlistDaoClass.name}" +\n                " | predicate=${predicateClass.name}" +\n                " | linkBoundary=" +\n                (presenterLinkSmartPlaylist?.let {\n                    it.declaringClass.name + "." + it.name\n                } ?: chooserConsumerAccept?.let {\n                    it.declaringClass.name + "." + it.name\n                } ?: "none")\n''',
    "mapping dispatch log",
)

controller_path.write_text(controller, encoding="utf-8")


# --- GoneSmartModule.kt ----------------------------------------------------------
module_path = ROOT / "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
module = module_path.read_text(encoding="utf-8")

module = replace_once(
    module,
    '''    // Temporary, bounded read-only correlation for the still-unaccepted\n    // GMMP 4.2.1 Playlist Link action boundary. Retire after the real native\n    // call chain has been passively observed on device.\n    private val playlistBridgeActionProbeUntilMs =\n        AtomicLong(0L)\n\n    private val playlistBridgeActionProbeDialogsLeft =\n        AtomicLong(0L)\n\n''',
    '',
    "retire probe state",
)

module = replace_once(
    module,
    '''        val loader = param.classLoader\n        installPlaylistBridgeActionProbe(loader)\n        val bindingsReady = playlistBridgeController.configure(loader)\n''',
    '''        val loader = param.classLoader\n        val bindingsReady = playlistBridgeController.configure(loader)\n''',
    "retire probe install call",
)

module, count = re.subn(
    r'''\n    private fun installPlaylistBridgeActionProbe\(loader: ClassLoader\) \{.*?\n    \}\n\n(?=    private fun installPlaylistBridgeEditorHooks)''',
    '\n',
    module,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f"retire probe function: expected 1 match, got {count}")

old_presenter_hook = '''        runCatching {\n            hook(targets.presenterLinkSmartPlaylist).intercept { chain ->\n                if (playlistBridgeController.shouldBypassNativeLinkHook()) {\n                    return@intercept chain.proceed()\n                }\n                val edit = chain.getArg(0) as? Boolean == true\n                if (\n                    playlistBridgeController.interceptNativeLinkAction(\n                        chain.getThisObject(),\n                        edit\n                    )\n                ) {\n                    null\n                } else {\n                    chain.proceed()\n                }\n            }\n            installed++\n        }.onFailure {\n            playlistBridgeWarn("Smart link chooser hook unavailable", it)\n        }\n\n'''
new_presenter_hook = '''        targets.presenterLinkSmartPlaylist?.let { method ->\n            runCatching {\n                hook(method).intercept { chain ->\n                    if (playlistBridgeController.shouldBypassNativeLinkHook()) {\n                        return@intercept chain.proceed()\n                    }\n                    val edit = chain.getArg(0) as? Boolean == true\n                    if (\n                        playlistBridgeController.interceptNativeLinkAction(\n                            chain.getThisObject(),\n                            edit\n                        )\n                    ) {\n                        null\n                    } else {\n                        chain.proceed()\n                    }\n                }\n                installed++\n            }.onFailure {\n                playlistBridgeWarn("Smart link chooser hook unavailable", it)\n            }\n        }\n\n'''
module = replace_once(
    module,
    old_presenter_hook,
    new_presenter_hook,
    "optional legacy presenter hook",
)

old_callback_hook = '''        targets.chooserConsumerAccept?.let { method ->\n            runCatching {\n                hook(method).intercept { chain ->\n                    val previous = playlistBridgeSmartChooserTitleDepth.get()\n                    playlistBridgeSmartChooserTitleDepth.set(previous + 1)\n                    try {\n                        chain.proceed()\n                    } finally {\n                        playlistBridgeSmartChooserTitleDepth.set(previous)\n                    }\n                }\n                installed++\n            }.onFailure {\n                playlistBridgeWarn("Smart chooser title scope unavailable", it)\n            }\n        }\n'''
new_callback_hook = '''        targets.chooserConsumerAccept?.let { method ->\n            runCatching {\n                hook(method).intercept { chain ->\n                    // GMMP 4.2.1: this exact callback is the passively proven\n                    // action boundary from the real native dialog stack.\n                    if (\n                        targets.presenterLinkSmartPlaylist == null &&\n                        !playlistBridgeController.shouldBypassNativeLinkHook() &&\n                        playlistBridgeController.interceptNativeChooserCallback(\n                            chain.getThisObject(),\n                            chain.getArg(0)\n                        )\n                    ) {\n                        return@intercept null\n                    }\n\n                    val previous = playlistBridgeSmartChooserTitleDepth.get()\n                    playlistBridgeSmartChooserTitleDepth.set(previous + 1)\n                    try {\n                        chain.proceed()\n                    } finally {\n                        playlistBridgeSmartChooserTitleDepth.set(previous)\n                    }\n                }\n                installed++\n            }.onFailure {\n                playlistBridgeWarn("Smart chooser callback hook unavailable", it)\n            }\n        }\n'''
module = replace_once(
    module,
    old_callback_hook,
    new_callback_hook,
    "proven callback hook",
)

module_path.write_text(module, encoding="utf-8")


# --- AGENTS.md -----------------------------------------------------------------
agents_path = ROOT / "AGENTS.md"
agents = agents_path.read_text(encoding="utf-8")
agents = replace_once(
    agents,
    '''- A remapped Playlist Link UI action is not proven by an old obfuscated method name plus a matching signature. Correlate the real user-triggered native call passively (for example from the resulting native dialog/event stack) before promoting an intercept boundary.\n- Playlist Link dispatch must not rely solely on an after-inflate `MenuItem` listener because GMMP may replace it later. A presenter/action fallback is authoritative only after that exact native boundary has been passively correlated from the real user action; until then it remains diagnostic evidence and must not be treated as proven.\n''',
    '''- A remapped Playlist Link UI action is not proven by an old obfuscated method name plus a matching signature. Correlate the real user-triggered native call passively (for example from the resulting native dialog/event stack) before promoting an intercept boundary.\n- GMMP 4.2.1 Playlist Link is now passively correlated at the naturally triggered `as4$g.accept(...)` callback (`MaterialDialog.show()` stack). The lone `as4.h2(boolean)` candidate must **not** be treated as the old link action merely because its signature matches 4.2.0.\n- Playlist Link dispatch must not rely solely on an after-inflate `MenuItem` listener because GMMP may replace it later. GMMP 4.2.0 keeps the accepted exact `ds4.g2(boolean)` boundary; GMMP 4.2.1 intercepts the proven chooser callback and resumes that exact captured callback/payload for the native Smart-Playlist option instead of guessing an upstream obfuscated method.\n''',
    "AGENTS Playlist Link proof",
)
agents_path.write_text(agents, encoding="utf-8")


# --- compatibility playbook -----------------------------------------------------
playbook_path = ROOT / "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
playbook = playbook_path.read_text(encoding="utf-8")
playbook = replace_once(
    playbook,
    '''For the still-pending Playlist Link re-acceptance, the native editor Link action itself must be passively correlated from a real user-triggered GMMP dialog/event chain. A historical method name plus the same parameter shape is insufficient evidence for interception.\n\nFor Playlist Link specifically, do not rely on a post-inflate `MenuItem` listener as the sole dispatch boundary. GMMP 4.2.1 can replace that listener later in the Smart editor lifecycle. The validated native presenter link action is therefore the authoritative fallback: add-link requests may be rerouted to GoneSmart's type chooser, while the Smart-Playlist option re-enters GMMP's original native linker through a bounded reentrancy bypass.\n''',
    '''For the still-pending Playlist Link re-acceptance, the native editor Link action itself must be passively correlated from a real user-triggered GMMP dialog/event chain. A historical method name plus the same parameter shape is insufficient evidence for interception. The 2026-10-08 device trace now proves the GMMP 4.2.1 chooser boundary at `as4$g.accept(...)`: that callback appears immediately upstream of GMMP's native event chain and resulting `MaterialDialog.show()`. The unrelated `as4.h2(boolean)` shape therefore remains untrusted.\n\nFor Playlist Link specifically, do not rely on a post-inflate `MenuItem` listener as the sole dispatch boundary. GMMP can replace that listener later in the Smart editor lifecycle. Keep the accepted 4.2.0 `ds4.g2(boolean)` path exact. On 4.2.1, intercept the proven `as4$g.accept(...)` callback for GoneSmart's type chooser and retain the exact callback instance/payload as a one-shot native continuation; choosing Smart Playlist resumes that original continuation. This avoids inventing an upstream mapping while keeping ordinary native edit/disabled paths pass-through.\n''',
    "playbook Playlist Link proof",
)
playbook_path.write_text(playbook, encoding="utf-8")

print("Applied proven Playlist Link callback dispatch repair")
