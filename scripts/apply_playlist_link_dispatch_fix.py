#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

# Pure policy + host tests keep the native-link dispatch contract durable.
policy = '''package io.github.alagga.gonesmart

internal enum class PlaylistBridgeLinkAction {
    PASS_THROUGH,
    SHOW_TYPE_MENU,
    EDIT_BRIDGE
}

internal object PlaylistBridgeLinkDispatchPolicy {
    fun action(
        enabled: Boolean,
        edit: Boolean,
        selectedIsBridge: Boolean
    ): PlaylistBridgeLinkAction {
        if (!enabled) return PlaylistBridgeLinkAction.PASS_THROUGH
        if (!edit) return PlaylistBridgeLinkAction.SHOW_TYPE_MENU
        return if (selectedIsBridge) {
            PlaylistBridgeLinkAction.EDIT_BRIDGE
        } else {
            PlaylistBridgeLinkAction.PASS_THROUGH
        }
    }
}
'''
(ROOT / 'app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeLinkDispatchPolicy.kt').write_text(policy, encoding='utf-8')

test = '''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBridgeLinkDispatchPolicyTest {
    @Test
    fun disabledFeatureAlwaysPassesThrough() {
        assertEquals(
            PlaylistBridgeLinkAction.PASS_THROUGH,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = false,
                edit = false,
                selectedIsBridge = false
            )
        )
    }

    @Test
    fun nativeAddLinkActionShowsTypeMenu() {
        assertEquals(
            PlaylistBridgeLinkAction.SHOW_TYPE_MENU,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = true,
                edit = false,
                selectedIsBridge = false
            )
        )
    }

    @Test
    fun bridgeRuleEditUsesBridgeChooser() {
        assertEquals(
            PlaylistBridgeLinkAction.EDIT_BRIDGE,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = true,
                edit = true,
                selectedIsBridge = true
            )
        )
    }

    @Test
    fun ordinaryNativeLinkedRuleEditPassesThrough() {
        assertEquals(
            PlaylistBridgeLinkAction.PASS_THROUGH,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = true,
                edit = true,
                selectedIsBridge = false
            )
        )
    }
}
'''
(ROOT / 'app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeLinkDispatchPolicyTest.kt').write_text(test, encoding='utf-8')

controller_path = ROOT / 'app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt'
controller = controller_path.read_text(encoding='utf-8')
controller = replace_once(
    controller,
    '''    @Volatile private var presenterRef: WeakReference<Any>? = null\n    @Volatile private var contextRef: WeakReference<Context>? = null\n''',
    '''    @Volatile private var presenterRef: WeakReference<Any>? = null\n    @Volatile private var contextRef: WeakReference<Context>? = null\n    private val nativeSmartChooserBypass = ThreadLocal.withInitial { false }\n''',
    'controller bypass state'
)
controller = replace_once(
    controller,
    '''    private fun showLinkTypeMenu(\n        context: Context,\n        nativeLinkId: Int\n    ): Boolean {\n        val native = bindings ?: return false\n        val activity = findActivity(context)\n            ?: contextRef?.get()?.let(::findActivity)\n            ?: return false\n        val anchor = activity.window?.decorView\n            ?.findViewById<View>(nativeLinkId)\n            ?: return false\n\n        return runCatching {\n''',
    '''    private fun showLinkTypeMenu(\n        context: Context,\n        nativeLinkId: Int\n    ): Boolean {\n        val native = bindings ?: return false\n        val activity = findActivity(context)\n            ?: contextRef?.get()?.let(::findActivity)\n            ?: run {\n                Log.w(TAG, "BRIDGE TYPE MENU | active Activity unavailable")\n                return false\n            }\n        val anchor = activity.window?.decorView\n            ?.findViewById<View>(nativeLinkId)\n            ?: run {\n                Log.w(\n                    TAG,\n                    "BRIDGE TYPE MENU | native menuLink anchor unavailable | id=$nativeLinkId"\n                )\n                return false\n            }\n\n        return runCatching {\n''',
    'type-menu diagnostics'
)
controller = replace_once(
    controller,
    '''    private fun openNativeSmartPlaylistChooser() {\n        val native = bindings ?: return\n        val presenter = presenterRef?.get() ?: run {\n            Log.w(TAG, "BRIDGE SMART CHOOSER | no active SmartEditorPresenter")\n            return\n        }\n        runCatching {\n            native.presenterLinkSmartPlaylist.invoke(presenter, false)\n        }.onFailure {\n            Log.e(TAG, "BRIDGE SMART CHOOSER | original ds4.g2(false) failed", it)\n        }\n    }\n''',
    '''    private fun openNativeSmartPlaylistChooser() {\n        val native = bindings ?: return\n        val presenter = presenterRef?.get() ?: run {\n            Log.w(TAG, "BRIDGE SMART CHOOSER | no active SmartEditorPresenter")\n            return\n        }\n        runCatching {\n            nativeSmartChooserBypass.set(true)\n            try {\n                native.presenterLinkSmartPlaylist.invoke(presenter, false)\n            } finally {\n                nativeSmartChooserBypass.set(false)\n            }\n        }.onFailure {\n            Log.e(\n                TAG,\n                "BRIDGE SMART CHOOSER | original native linker failed",\n                it\n            )\n        }\n    }\n\n    fun shouldBypassNativeLinkHook(): Boolean =\n        nativeSmartChooserBypass.get()\n''',
    'native chooser bypass'
)
controller = replace_once(
    controller,
    '''    fun interceptNativeLinkedEditor(presenter: Any?, edit: Boolean): Boolean {\n        if (!enabled || !edit || presenter == null) return false\n        val rule = selectedRule(presenter) ?: return false\n        if (!isBridgeRule(rule)) return false\n        presenterRef = WeakReference(presenter)\n        Log.i(TAG, "BRIDGE EDIT | intercepted native linked-playlist editor")\n        openChooser(edit = true, explicitPresenter = presenter)\n        return true\n    }\n''',
    '''    fun interceptNativeLinkAction(presenter: Any?, edit: Boolean): Boolean {\n        if (presenter == null) return false\n        val native = bindings ?: return false\n        if (!native.presenterClass.isInstance(presenter)) return false\n        presenterRef = WeakReference(presenter)\n\n        val selectedIsBridge = if (edit) {\n            selectedRule(presenter)?.let(::isBridgeRule) == true\n        } else {\n            false\n        }\n        return when (\n            PlaylistBridgeLinkDispatchPolicy.action(\n                enabled = enabled,\n                edit = edit,\n                selectedIsBridge = selectedIsBridge\n            )\n        ) {\n            PlaylistBridgeLinkAction.PASS_THROUGH -> false\n\n            PlaylistBridgeLinkAction.EDIT_BRIDGE -> {\n                Log.i(TAG, "BRIDGE EDIT | intercepted native linked-playlist editor")\n                openChooser(edit = true, explicitPresenter = presenter)\n                true\n            }\n\n            PlaylistBridgeLinkAction.SHOW_TYPE_MENU -> {\n                val context = contextRef?.get() ?: run {\n                    Log.w(TAG, "BRIDGE LINK DISPATCH | editor Context unavailable")\n                    return false\n                }\n                val nativeLinkId = context.resources.getIdentifier(\n                    NATIVE_LINK_ITEM,\n                    "id",\n                    GMMP_PACKAGE\n                )\n                if (nativeLinkId == 0) {\n                    Log.w(TAG, "BRIDGE LINK DISPATCH | menuLink resource unavailable")\n                    return false\n                }\n                val shown = showLinkTypeMenu(context, nativeLinkId)\n                Log.i(\n                    TAG,\n                    "BRIDGE LINK DISPATCH | native presenter action" +\n                        " | typeMenuShown=$shown"\n                )\n                shown\n            }\n        }\n    }\n''',
    'native link action interception'
)
controller_path.write_text(controller, encoding='utf-8')

module_path = ROOT / 'app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt'
module = module_path.read_text(encoding='utf-8')
module = replace_once(
    module,
    '''        runCatching {\n            hook(targets.presenterLinkSmartPlaylist).intercept { chain ->\n                val edit = chain.getArg(0) as? Boolean == true\n                if (\n                    playlistBridgeController.interceptNativeLinkedEditor(\n                        chain.getThisObject(),\n                        edit\n                    )\n                ) {\n                    null\n                } else {\n                    chain.proceed()\n                }\n            }\n            installed++\n        }.onFailure {\n            playlistBridgeWarn("Smart link chooser hook unavailable", it)\n        }\n''',
    '''        runCatching {\n            hook(targets.presenterLinkSmartPlaylist).intercept { chain ->\n                if (playlistBridgeController.shouldBypassNativeLinkHook()) {\n                    return@intercept chain.proceed()\n                }\n                val edit = chain.getArg(0) as? Boolean == true\n                if (\n                    playlistBridgeController.interceptNativeLinkAction(\n                        chain.getThisObject(),\n                        edit\n                    )\n                ) {\n                    null\n                } else {\n                    chain.proceed()\n                }\n            }\n            installed++\n        }.onFailure {\n            playlistBridgeWarn("Smart link chooser hook unavailable", it)\n        }\n''',
    'module native-link hook'
)
module_path.write_text(module, encoding='utf-8')

playbook_path = ROOT / 'docs/GMMP_COMPATIBILITY_PLAYBOOK.md'
playbook = playbook_path.read_text(encoding='utf-8')
playbook = replace_once(
    playbook,
    '''Resolvers prefer semantic adapter/model/writer ownership. Historical class names remain fast paths only where useful and safe.\n''',
    '''Resolvers prefer semantic adapter/model/writer ownership. Historical class names remain fast paths only where useful and safe.\n\nFor Playlist Link specifically, do not rely on a post-inflate `MenuItem` listener as the sole dispatch boundary. GMMP 4.2.1 can replace that listener later in the Smart editor lifecycle. The validated native presenter link action is therefore the authoritative fallback: add-link requests may be rerouted to GoneSmart's type chooser, while the Smart-Playlist option re-enters GMMP's original native linker through a bounded reentrancy bypass.\n''',
    'playbook link-dispatch rule'
)
playbook_path.write_text(playbook, encoding='utf-8')

print('Applied Playlist Link native dispatch repair')
