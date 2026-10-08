package io.github.alagga.gonesmart

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.app.Activity
import android.content.ContextWrapper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal class PlaylistBridgeController {
    companion object {
        private const val TAG = "GoneSmartPlaylistBridge"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val SMART_EDITOR_MENU = "menu_gm_smart_editor"
        private const val NATIVE_LINK_ITEM = "menuLink"
        private const val MAX_IN_VALUES = 800
        private const val LILAC = 0xFFA39AFF.toInt()
        private val SUPPORTED_EXTENSIONS = setOf("m3u", "m3u8", "pls", "wpl")
    }

    private data class PlaylistChoice(
        val path: String,
        val displayName: String,
        val label: String
    )

    private data class Membership(
        val lastModified: Long,
        val length: Long,
        val paths: List<String>
    )

    private data class Bindings(
        val loader: ClassLoader,
        val presenterClass: Class<*>,
        val presenterConstructor: Constructor<*>,
        val baseRuleClass: Class<*>,
        val smartRuleClass: Class<*>,
        val smartRuleConstructor: Constructor<*>,
        val ruleSentinel: Field,
        val ruleValue: Field,
        val presenterAddRule: Method,
        val presenterState: Method,
        val presenterRefresh: Method?,
        val presenterView: Field?,
        val stateRules: Method,
        val statePaths: Field?,
        val stateSelectedIndex: Field?,
        val stateDirty: Field?,
        val ruleIdGetter: Method?,
        val ruleIdSetter: Method?,
        val playlistFileConstructor: Constructor<*>,
        val fileModelConstructor: Constructor<*>,
        val playlistRead: Method,
        val playlistEntries: Field,
        val fileModelFile: Field,
        val nativeIn: Method,
        val nativeEquals: Method,
        val uriField: Any,
        val idField: Any,
        val whereGroupConstructor: Constructor<*>,
        val databaseSingleton: Field,
        val playlistDaoGetter: Method,
        val playlistDaoAll: Method,
        val dialogEventConstructor: Constructor<*>,
        val dialogCallbackClass: Class<*>,
        val eventBusGet: Method,
        val eventBusPost: Method,
        val unitValue: Any?,
        val presenterLinkSmartPlaylist: Method?,
        val popupMenuConstructor: Constructor<*>,
        val popupMenuGetMenu: Method,
        val popupMenuSetListener: Method,
        val popupMenuShow: Method,
        val popupMenuListenerClass: Class<*>,
        val smartPlaylistConstructor: Constructor<*>,
        val smartPlaylistConstructorArgs: Array<Any?>,
        val smartPlaylistSave: Method,
        val smartPlaylistName: Field,
        val smartPlaylistRules: Field,
        val smartPlaylistMatchAll: Field,
        val groupRuleClass: Class<*>,
        val groupRules: Field,
        val groupMatchAll: Field,
        val chooserConsumerAccept: Method?,
        val ruleLabelFormatter: Method?,
        val evaluationMethod: Method
    )
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoneSmartPlaylistBridge").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, Membership>()

    @Volatile private var bindings: Bindings? = null
    @Volatile private var enabled: Boolean = true
    @Volatile private var presenterRef: WeakReference<Any>? = null
    @Volatile private var contextRef: WeakReference<Context>? = null
    private val nativeSmartChooserBypass = ThreadLocal.withInitial { false }

    private data class NativeSmartChooserContinuation(
        val callback: Any,
        val payload: Any?
    )

    @Volatile
    private var nativeSmartChooserContinuation: NativeSmartChooserContinuation? = null

    internal data class PortableSaveToken(
        val originals: List<Pair<Any, String?>>
    )

    internal data class HookTargets(
        val presenterConstructor: Constructor<*>,
        val presenterLinkSmartPlaylist: Method?,
        val chooserConsumerAccept: Method?,
        val ruleLabelFormatter: Method?,
        val evaluationMethod: Method
    )

    internal fun hookTargets(): HookTargets? = bindings?.let { native ->
        HookTargets(
            presenterConstructor = native.presenterConstructor,
            presenterLinkSmartPlaylist = native.presenterLinkSmartPlaylist,
            chooserConsumerAccept = native.chooserConsumerAccept,
            ruleLabelFormatter = native.ruleLabelFormatter,
            evaluationMethod = native.evaluationMethod
        )
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            cache.clear()
            nativeSmartChooserContinuation = null
        }
        Log.i(TAG, "BRIDGE SETTINGS | enabled=$value")
    }

    fun isEnabled(): Boolean = enabled

    fun configure(loader: ClassLoader): Boolean {
        val loaded = runCatching { createBindings(loader) }
            .onFailure {
                Log.e(TAG, "BRIDGE BINDINGS FAILED | native GMMP untouched", it)
                diagnoseCompatibilityBindings(loader)
            }
            .getOrNull()
        bindings = loaded
        Log.i(TAG, "BRIDGE BINDINGS | ready=${loaded != null}")
        return loaded != null
    }

    private fun diagnoseCompatibilityBindings(
        loader: ClassLoader
    ) {
        listOf("ct4", "ft4", "as4", "ds4", "ls2", "os2").forEach { name ->
            runCatching { loader.loadClass(name) }.getOrNull()?.let { type ->
                Log.w(
                    TAG,
                    "BRIDGE MAPPING | candidate=$name" +
                        " | constructors=" + GmmpReflectionDiagnostics.constructors(type) +
                        " | methods=" + GmmpReflectionDiagnostics.methods(
                            type = type,
                            limit = 24
                        ) { true }
                )
            }
        }
    }

    fun capturePresenter(presenter: Any?, context: Context?) {
        val native = bindings ?: return
        if (presenter == null || !native.presenterClass.isInstance(presenter)) return
        presenterRef = WeakReference(presenter)
        context?.let { contextRef = WeakReference(it) }
        Log.i(
            TAG,
            "BRIDGE PRESENTER | captured=${presenter.javaClass.name}" +
                " | enabled=$enabled" +
                " | git=${BuildConfig.GIT_REVISION}"
        )
    }

    fun onMenuInflated(menuResId: Int, menu: Menu?, inflater: Any?) {
        bindings ?: return
        if (menu == null) return
        val context = menuContext(menu, inflater) ?: contextRef?.get() ?: return
        if (context.packageName != GMMP_PACKAGE) return
        val menuName = runCatching {
            context.resources.getResourceEntryName(menuResId)
        }.getOrNull() ?: return
        if (menuName != SMART_EDITOR_MENU) return

        val nativeLinkId = context.resources.getIdentifier(
            NATIVE_LINK_ITEM, "id", GMMP_PACKAGE
        )
        val original = if (nativeLinkId != 0) menu.findItem(nativeLinkId) else null
        if (original == null) {
            Log.w(TAG, "BRIDGE MENU SKIP | original menuLink missing")
            return
        }

        // Keep GMMP's one ORIGINAL toolbar action/icon. Its click now opens
        // the host AppCompat popup menu below that exact action item and
        // dispatches either back into the proven native continuation or
        // Playlist Bridge.
        original.setOnMenuItemClickListener {
            if (!enabled) {
                return@setOnMenuItemClickListener false
            }
            if (!showLinkTypeMenu(context, nativeLinkId)) {
                Log.w(
                    TAG,
                    "BRIDGE TYPE MENU FALLBACK | anchor/popup unavailable; " +
                        "opening original Smart Playlist linker"
                )
                openNativeSmartPlaylistChooser()
            }
            true
        }
        if (enabled) {
            Log.i(
                TAG,
                "BRIDGE MENU | original menuLink reused as Smart Playlist / Playlist chooser"
            )
        }
    }

    fun currentContext(): Context? = contextRef?.get()

    fun nativeSmartPlaylistLinkTitle(): String? {
        if (!enabled) return null
        return contextRef?.get()?.let(NativeGmmpUiText::linkSmartPlaylist)
    }

    fun rewriteNativeSmartPlaylistRuleLabel(
        rule: Any?,
        original: String?
    ): String? {
        if (!enabled) return original
        val native = bindings ?: return original
        if (rule == null || !native.smartRuleClass.isInstance(rule)) {
            return original
        }
        val value = runCatching {
            native.ruleValue.get(rule) as? String
        }.getOrNull()
        if (PlaylistBridgeReference.isBridgeValue(value)) return original
        if (
            !PlaylistBridgePolicy
                .isNativeSmartPlaylistReference(value)
        ) {
            return original
        }
        val display = value
            ?.substringAfter('|', "")
            ?.takeUnless(String::isBlank)
            ?: return original
        val context = contextRef?.get() ?: return original
        return NativeGmmpUiText.smartPlaylist(context) + ": " + display
    }

    private fun showLinkTypeMenu(
        context: Context,
        nativeLinkId: Int
    ): Boolean {
        val native = bindings ?: return false
        val activity = findActivity(context)
            ?: contextRef?.get()?.let(::findActivity)
            ?: run {
                Log.w(TAG, "BRIDGE TYPE MENU | active Activity unavailable")
                return false
            }
        val anchor = activity.window?.decorView
            ?.findViewById<View>(nativeLinkId)
            ?: run {
                Log.w(
                    TAG,
                    "BRIDGE TYPE MENU | native menuLink anchor unavailable | id=$nativeLinkId"
                )
                return false
            }

        return runCatching {
            val popup = native.popupMenuConstructor.newInstance(context, anchor)
            val popupMenu = native.popupMenuGetMenu.invoke(popup) as? Menu
                ?: return@runCatching false
            popupMenu.clear()

            val smartTitle = NativeGmmpUiText.smartPlaylist(context)
            val playlistTitle = decoratedPlaylistTitle(context)
            popupMenu.add(
                Menu.NONE,
                Menu.NONE,
                0,
                smartTitle
            )
            popupMenu.add(
                Menu.NONE,
                Menu.NONE,
                1,
                playlistTitle
            )

            val listener = Proxy.newProxyInstance(
                native.loader,
                arrayOf(native.popupMenuListenerClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "onMenuItemClick" -> {
                        val selected = args?.getOrNull(0) as? MenuItem
                        if (!enabled) {
                            if (selected?.order == 0) {
                                openNativeSmartPlaylistChooser()
                            }
                            return@newProxyInstance true
                        }
                        when (selected?.order) {
                            0 -> openNativeSmartPlaylistChooser()
                            1 -> {
                                nativeSmartChooserContinuation = null
                                openChooser(edit = false)
                            }
                        }
                        true
                    }
                    "toString" -> "PlaylistBridgeTypeMenuCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> null
                }
            }
            native.popupMenuSetListener.invoke(popup, listener)
            native.popupMenuShow.invoke(popup)
            Log.i(
                TAG,
                "BRIDGE TYPE MENU | host PopupMenu shown | options=smart,playlist"
            )
            true
        }.onFailure {
            Log.e(TAG, "BRIDGE TYPE MENU FAILED", it)
        }.getOrDefault(false)
    }

    private fun openNativeSmartPlaylistChooser() {
        val native = bindings ?: return

        // GMMP 4.2.1: resume the exact native callback instance/payload that
        // was naturally reached by the real toolbar action. This keeps the
        // original async result/event chain intact instead of guessing an
        // upstream obfuscated presenter method.
        val continuation = nativeSmartChooserContinuation
        val accept = native.chooserConsumerAccept
        if (
            continuation != null &&
            accept != null &&
            accept.declaringClass.isInstance(continuation.callback)
        ) {
            nativeSmartChooserContinuation = null
            runCatching {
                nativeSmartChooserBypass.set(true)
                try {
                    accept.invoke(continuation.callback, continuation.payload)
                } finally {
                    nativeSmartChooserBypass.set(false)
                }
            }.onFailure {
                Log.e(
                    TAG,
                    "BRIDGE SMART CHOOSER | captured native callback failed",
                    it
                )
            }
            return
        }
        nativeSmartChooserContinuation = null

        // Accepted GMMP 4.2.0 fallback. Do not structurally substitute another
        // boolean method on remapped versions: only ds4.g2(boolean) is proven.
        val legacyAction = native.presenterLinkSmartPlaylist ?: run {
            Log.w(
                TAG,
                "BRIDGE SMART CHOOSER | no proven native continuation available"
            )
            return
        }
        val presenter = presenterRef?.get() ?: run {
            Log.w(TAG, "BRIDGE SMART CHOOSER | no active SmartEditorPresenter")
            return
        }
        runCatching {
            nativeSmartChooserBypass.set(true)
            try {
                legacyAction.invoke(presenter, false)
            } finally {
                nativeSmartChooserBypass.set(false)
            }
        }.onFailure {
            Log.e(
                TAG,
                "BRIDGE SMART CHOOSER | original native linker failed",
                it
            )
        }
    }

    fun shouldBypassNativeLinkHook(): Boolean =
        nativeSmartChooserBypass.get()

    /**
     * GMMP 4.2.1 dispatch boundary proven from the naturally opened native
     * MaterialDialog stack: as4$g.accept(...). Store that exact callback and
     * payload only while GoneSmart owns an add-link choice. Native edit paths
     * and disabled mode pass through unchanged.
     */
    fun interceptNativeChooserCallback(
        callback: Any?,
        payload: Any?
    ): Boolean {
        if (!enabled || callback == null || nativeSmartChooserBypass.get()) {
            return false
        }
        val native = bindings ?: return false
        if (native.presenterLinkSmartPlaylist != null) {
            // Accepted 4.2.0 keeps its exact ds4.g2(boolean) boundary.
            return false
        }
        val accept = native.chooserConsumerAccept ?: return false
        if (!accept.declaringClass.isInstance(callback)) return false
        val presenter = presenterRef?.get() ?: return false
        if (!native.presenterClass.isInstance(presenter)) return false

        // The editor's native selected-rule state is the semantic distinction
        // between add and edit. Do not infer the old boolean argument from an
        // unrelated remapped method signature.
        val edit = selectedRule(presenter) != null
        if (!edit) {
            nativeSmartChooserContinuation =
                NativeSmartChooserContinuation(callback, payload)
        }

        val intercepted = interceptNativeLinkAction(presenter, edit)
        if (edit || !intercepted) {
            nativeSmartChooserContinuation = null
        }
        Log.i(
            TAG,
            "BRIDGE LINK DISPATCH | proven chooser callback" +
                " | edit=$edit | intercepted=$intercepted"
        )
        return intercepted
    }

    private fun decoratedPlaylistTitle(context: Context): CharSequence {
        val playlist = NativeGmmpUiText.string(context, "playlist")
            ?: "Playlist"
        val label = SpannableStringBuilder(playlist)
            .append("  \uFFFC")
        val marker = label.lastIndexOf('\uFFFC')
        label.setSpan(
            BaselineCenteredSparkleSpan(
                context,
                PlayerAutoDjBadgeController.SparkleBadgeDrawable(
                    LILAC,
                    scale = 1.85f
                )
            ),
            marker,
            marker + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return label
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        val visited = java.util.HashSet<Context>()
        while (current != null && visited.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    fun interceptNativeLinkAction(presenter: Any?, edit: Boolean): Boolean {
        if (presenter == null) return false
        val native = bindings ?: return false
        if (!native.presenterClass.isInstance(presenter)) return false
        presenterRef = WeakReference(presenter)

        val selectedIsBridge = if (edit) {
            selectedRule(presenter)?.let(::isBridgeRule) == true
        } else {
            false
        }
        return when (
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = enabled,
                edit = edit,
                selectedIsBridge = selectedIsBridge
            )
        ) {
            PlaylistBridgeLinkAction.PASS_THROUGH -> false

            PlaylistBridgeLinkAction.EDIT_BRIDGE -> {
                Log.i(TAG, "BRIDGE EDIT | intercepted native linked-playlist editor")
                openChooser(edit = true, explicitPresenter = presenter)
                true
            }

            PlaylistBridgeLinkAction.SHOW_TYPE_MENU -> {
                val context = contextRef?.get() ?: run {
                    Log.w(TAG, "BRIDGE LINK DISPATCH | editor Context unavailable")
                    return false
                }
                val nativeLinkId = context.resources.getIdentifier(
                    NATIVE_LINK_ITEM,
                    "id",
                    GMMP_PACKAGE
                )
                if (nativeLinkId == 0) {
                    Log.w(TAG, "BRIDGE LINK DISPATCH | menuLink resource unavailable")
                    return false
                }
                val shown = showLinkTypeMenu(context, nativeLinkId)
                Log.i(
                    TAG,
                    "BRIDGE LINK DISPATCH | native presenter action" +
                        " | typeMenuShown=$shown"
                )
                shown
            }
        }
    }

    fun isBridgeRule(rule: Any?): Boolean {
        val native = bindings ?: return false
        if (rule == null || !native.smartRuleClass.isInstance(rule)) return false
        return runCatching {
            val sentinel = native.ruleSentinel.getInt(rule)
            val value = native.ruleValue.get(rule) as? String
            sentinel == -1 && PlaylistBridgeReference.decode(value) != null
        }.getOrDefault(false)
    }

    fun failClosedPredicate(): Any? = bindings?.let(::falsePredicate)

    fun compile(rule: Any): Any {
        val native = bindings
            ?: throw IllegalStateException("Playlist Bridge bindings missing")
        val value = native.ruleValue.get(rule) as? String
        val reference = PlaylistBridgeReference.decode(value)
            ?: return falsePredicate(native)
        val started = System.nanoTime()
        val membership = runCatching { membership(reference.path, native) }
            .onFailure {
                Log.e(
                    TAG,
                    "BRIDGE COMPILE FAILED | " +
                        PlaylistBridgePolicy.safePath(reference.path),
                    it
                )
            }
            .getOrNull()
            ?: return falsePredicate(native)

        if (membership.paths.isEmpty()) {
            Log.i(
                TAG,
                "BRIDGE COMPILE | empty source -> false predicate | " +
                    PlaylistBridgePolicy.safePath(reference.path)
            )
            return falsePredicate(native)
        }

        val clauses = membership.paths
            .distinct()
            .chunked(MAX_IN_VALUES)
            .map { values -> native.nativeIn.invoke(null, native.uriField, values) }
        val result = if (clauses.size == 1) {
            clauses.single()
        } else {
            native.whereGroupConstructor.newInstance(clauses, "OR")
        }
        Log.i(
            TAG,
            "BRIDGE COMPILE | entries=${membership.paths.size}" +
                " | chunks=${clauses.size}" +
                " | elapsedMs=${(System.nanoTime() - started) / 1_000_000L}" +
                " | " + PlaylistBridgePolicy.safePath(reference.path)
        )
        return result
    }

    fun preparePortableSave(
        smartPlaylist: Any?,
        destination: File?
    ): PortableSaveToken? {
        val native = bindings ?: return null
        if (smartPlaylist == null || destination == null) return null
        val context = contextRef?.get() ?: return null

        @Suppress("UNCHECKED_CAST")
        val rules = native.smartPlaylistRules.get(smartPlaylist)
            as? List<Any?> ?: return null

        // Most native ws4 writes do not contain a Playlist Bridge rule.
        // Exit before creating any compatibility file. The neutral files
        // are themselves written through GMMP's ORIGINAL ws4.t(File), so
        // this guard also prevents writer recursion.
        if (rules.none { containsBridge(native, it) }) {
            return PortableSaveToken(emptyList())
        }

        val matchAll = native.smartPlaylistMatchAll.getBoolean(smartPlaylist)
        val originals = mutableListOf<Pair<Any, String?>>()
        val destinationKey = canonicalPath(destination)

        rewritePortableRuleList(
            native = native,
            rules = rules,
            matchAll = matchAll,
            targetIfBridgeOnly = true,
            context = context,
            destinationKey = destinationKey,
            treePath = "root",
            originals = originals
        )

        if (originals.isNotEmpty()) {
            Log.i(
                TAG,
                "PORTABLE SAVE PREPARED | bridgeRules=${originals.size}" +
                    " | rootMatchAll=$matchAll | target=" +
                    PlaylistBridgePolicy.safePath(destinationKey)
            )
        }
        return PortableSaveToken(originals)
    }

    fun restorePortableSave(token: PortableSaveToken?) {
        val native = bindings ?: return
        token?.originals?.asReversed()?.forEach { (rule, value) ->
            runCatching { native.ruleValue.set(rule, value) }
        }
    }

    private fun rewritePortableRuleList(
        native: Bindings,
        rules: List<Any?>,
        matchAll: Boolean,
        targetIfBridgeOnly: Boolean,
        context: Context,
        destinationKey: String,
        treePath: String,
        originals: MutableList<Pair<Any, String?>>
    ) {
        if (rules.isEmpty()) return
        val bridgeOnly = rules.map { isBridgeOnlySubtree(native, it) }
        if (bridgeOnly.all { it }) {
            assignBridgeOnlyList(
                native,
                rules,
                matchAll,
                targetIfBridgeOnly,
                context,
                destinationKey,
                treePath,
                originals
            )
            return
        }

        // Neutral element for the CURRENT boolean operator:
        // AND -> true, OR -> false.
        val identity = matchAll
        rules.forEachIndexed { index, rule ->
            if (rule == null) return@forEachIndexed
            val childPath = "$treePath/$index"
            if (bridgeOnly[index]) {
                assignBridgeOnlyRule(
                    native,
                    rule,
                    identity,
                    context,
                    destinationKey,
                    childPath,
                    originals
                )
            } else if (
                native.groupRuleClass.isInstance(rule) &&
                containsBridge(native, rule)
            ) {
                @Suppress("UNCHECKED_CAST")
                val children = native.groupRules.get(rule) as? List<Any?>
                    ?: return@forEachIndexed
                rewritePortableRuleList(
                    native,
                    children,
                    native.groupMatchAll.getBoolean(rule),
                    targetIfBridgeOnly = identity,
                    context = context,
                    destinationKey = destinationKey,
                    treePath = childPath,
                    originals = originals
                )
            }
        }
    }

    private fun assignBridgeOnlyList(
        native: Bindings,
        rules: List<Any?>,
        matchAll: Boolean,
        target: Boolean,
        context: Context,
        destinationKey: String,
        treePath: String,
        originals: MutableList<Pair<Any, String?>>
    ) {
        if (rules.isEmpty()) return
        rules.forEachIndexed { index, rule ->
            if (rule == null) return@forEachIndexed
            val childTarget = when {
                matchAll && target -> true
                matchAll && !target -> index != 0
                !matchAll && !target -> false
                else -> index == 0
            }
            assignBridgeOnlyRule(
                native,
                rule,
                childTarget,
                context,
                destinationKey,
                "$treePath/$index",
                originals
            )
        }
    }

    private fun assignBridgeOnlyRule(
        native: Bindings,
        rule: Any,
        target: Boolean,
        context: Context,
        destinationKey: String,
        treePath: String,
        originals: MutableList<Pair<Any, String?>>
    ) {
        if (native.smartRuleClass.isInstance(rule) && isBridgeRule(rule)) {
            val field = native.ruleValue
            val original = field.get(rule) as? String
            val reference = PlaylistBridgeReference.decode(original) ?: return
            val compatibilityPath = compatibilitySmartPlaylistPath(
                native = native,
                context = context,
                destinationKey = destinationKey,
                treePath = treePath,
                shouldMatch = target
            )
            originals += rule to original
            field.set(
                rule,
                PlaylistBridgeReference.encodePortable(
                    reference.path,
                    reference.displayName,
                    compatibilityPath
                )
            )
            return
        }

        if (native.groupRuleClass.isInstance(rule)) {
            @Suppress("UNCHECKED_CAST")
            val children = native.groupRules.get(rule) as? List<Any?>
                ?: return
            assignBridgeOnlyList(
                native,
                children,
                native.groupMatchAll.getBoolean(rule),
                target,
                context,
                destinationKey,
                treePath,
                originals
            )
        }
    }

    private fun isBridgeOnlySubtree(native: Bindings, rule: Any?): Boolean {
        if (rule == null) return false
        if (native.smartRuleClass.isInstance(rule)) {
            return isBridgeRule(rule)
        }
        if (!native.groupRuleClass.isInstance(rule)) return false
        @Suppress("UNCHECKED_CAST")
        val children = native.groupRules.get(rule) as? List<Any?>
            ?: return false
        return children.isNotEmpty() && children.all {
            isBridgeOnlySubtree(native, it)
        }
    }

    private fun containsBridge(native: Bindings, rule: Any?): Boolean {
        if (rule == null) return false
        if (native.smartRuleClass.isInstance(rule)) {
            return isBridgeRule(rule)
        }
        if (!native.groupRuleClass.isInstance(rule)) return false
        @Suppress("UNCHECKED_CAST")
        val children = native.groupRules.get(rule) as? List<Any?>
            ?: return false
        return children.any { containsBridge(native, it) }
    }

    private fun compatibilitySmartPlaylistPath(
        native: Bindings,
        context: Context,
        destinationKey: String,
        treePath: String,
        shouldMatch: Boolean
    ): String {
        val directory = File(
            context.filesDir,
            "gonesmart/playlist-bridge"
        )
        check(directory.exists() || directory.mkdirs()) {
            "Could not create Playlist Bridge compatibility directory"
        }

        // GMMP's linked-Smart recursion detector keeps every visited path
        // for the full compilation. Therefore each Bridge occurrence needs
        // its own deterministic compatibility path; shared true/false files
        // would make a second Bridge look recursive.
        val key = destinationKey + "|" + treePath + "|" + shouldMatch
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        val file = File(
            directory,
            "bridge-" + (if (shouldMatch) "true-" else "false-") +
                digest + ".spl"
        )
        if (!file.isFile || file.length() <= 0L) {
            writeCompatibilitySmartPlaylist(native, file, shouldMatch)
        }
        return canonicalPath(file)
    }

    private fun writeCompatibilitySmartPlaylist(
        native: Bindings,
        file: File,
        shouldMatch: Boolean
    ) {
        val smart = native.smartPlaylistConstructor.newInstance(
            *native.smartPlaylistConstructorArgs
        )
        native.smartPlaylistName.set(
            smart,
            if (shouldMatch) {
                "GoneSmart Playlist Bridge true"
            } else {
                "GoneSmart Playlist Bridge false"
            }
        )
        @Suppress("UNCHECKED_CAST")
        val rules = native.smartPlaylistRules.get(smart)
            as MutableList<Any?>
        rules.clear()
        rules += native.smartRuleConstructor.newInstance(
            100, // GMMP 4.2.0 cg.A(100) -> z75.ID
            if (shouldMatch) 1 else 0, // != for true, = for false
            Long.MIN_VALUE.toString(),
            0
        )
        check(native.smartPlaylistSave.invoke(smart, file) == true) {
            "GMMP native Smart Playlist writer returned false"
        }
        check(file.isFile && file.length() > 0L) {
            "GMMP native Smart Playlist writer did not create file"
        }
        Log.i(
            TAG,
            "PORTABLE COMPATIBILITY WRITTEN | match=$shouldMatch | " +
                PlaylistBridgePolicy.safePath(
                    canonicalPath(file)
                )
        )
    }

    private fun openChooser(
        edit: Boolean,
        explicitPresenter: Any? = null
    ) {
        if (!enabled) return
        val native = bindings ?: return
        val presenter = explicitPresenter ?: presenterRef?.get() ?: run {
            Log.w(TAG, "BRIDGE CHOOSER | no active SmartEditorPresenter")
            return
        }
        if (!native.presenterClass.isInstance(presenter)) return
        val context = contextRef?.get() ?: return
        val title = NativeGmmpUiText.string(context, "link_playlist") ?: return

        worker.execute {
            val choices = runCatching { loadPlaylistChoices(native) }
                .onFailure {
                    Log.e(TAG, "BRIDGE CHOOSER | PlaylistDao load failed", it)
                }
                .getOrNull()
            main.post {
                if (!enabled) return@post
                if (choices.isNullOrEmpty()) {
                    showError(context, title)
                    return@post
                }
                if (presenterRef?.get() !== presenter && explicitPresenter == null) {
                    Log.w(TAG, "BRIDGE CHOOSER | presenter changed before dialog")
                    return@post
                }
                publishNativeChooser(
                    native,
                    presenter,
                    context,
                    title,
                    choices,
                    edit
                )
            }
        }
    }

    private fun publishNativeChooser(
        native: Bindings,
        presenter: Any,
        context: Context,
        title: String,
        choices: List<PlaylistChoice>,
        edit: Boolean
    ) {
        val labels: List<CharSequence> = choices.map { it.label }
        val callback = Proxy.newProxyInstance(
            native.loader,
            arrayOf(native.dialogCallbackClass)
        ) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    val index = (args?.getOrNull(1) as? Number)?.toInt() ?: -1
                    val choice = choices.getOrNull(index)
                    if (choice != null) {
                        validateAndApply(
                            native,
                            presenter,
                            context,
                            choice,
                            edit,
                            title
                        )
                    }
                    native.unitValue
                }
                "toString" -> "PlaylistBridgeDialogCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
        val event = native.dialogEventConstructor.newInstance(
            title,
            labels,
            callback
        )
        val bus = native.eventBusGet.invoke(null)
        native.eventBusPost.invoke(bus, event)
        Log.i(
            TAG,
            "BRIDGE CHOOSER | native zn4 dialog posted | " +
                "count=${choices.size} | edit=$edit"
        )
    }

    private fun validateAndApply(
        native: Bindings,
        presenter: Any,
        context: Context,
        choice: PlaylistChoice,
        edit: Boolean,
        title: String
    ) {
        if (!enabled) return
        worker.execute {
            val validation = runCatching { membership(choice.path, native) }
            if (validation.isFailure) {
                Log.e(
                    TAG,
                    "BRIDGE SELECT | native playlist parse failed",
                    validation.exceptionOrNull()
                )
                main.post { showError(context, title) }
                return@execute
            }
            main.post {
                if (!enabled) return@post
                runCatching {
                    if (edit) {
                        replaceRule(native, presenter, choice)
                    } else {
                        addRule(native, presenter, choice)
                    }
                }.onFailure {
                    Log.e(TAG, "BRIDGE SELECT | editor update failed", it)
                    showError(context, title)
                }
            }
        }
    }

    private fun addRule(
        native: Bindings,
        presenter: Any,
        choice: PlaylistChoice
    ) {
        val state = native.presenterState.invoke(presenter)
        @Suppress("UNCHECKED_CAST")
        val rules = native.stateRules.invoke(state) as MutableList<Any?>
        @Suppress("UNCHECKED_CAST")
        val paths = native.statePaths?.get(state) as? MutableMap<Int, String>
        paths?.put(rules.size, choice.path)
        val rule = createRule(native, choice)
        native.presenterAddRule.invoke(presenter, rule)
        Log.i(
            TAG,
            "BRIDGE ADD | persisted native ft4 bridge rule | " +
                PlaylistBridgePolicy.safePath(choice.path)
        )
    }

    private fun replaceRule(
        native: Bindings,
        presenter: Any,
        choice: PlaylistChoice
    ) {
        val state = native.presenterState.invoke(presenter)
        @Suppress("UNCHECKED_CAST")
        val rules = native.stateRules.invoke(state) as MutableList<Any?>
        val selectedIndex = native.stateSelectedIndex
            ?: throw IllegalStateException("Smart editor selected-index mapping unavailable")
        val index = selectedIndex.getInt(state)
        val previous = rules.getOrNull(index)
            ?: throw IndexOutOfBoundsException("Smart rule index $index")
        val replacement = createRule(native, choice)
        runCatching {
            val getter = native.ruleIdGetter ?: return@runCatching
            val setter = native.ruleIdSetter ?: return@runCatching
            val id = getter.invoke(previous) as? Number ?: return@runCatching
            setter.invoke(replacement, id.toLong())
        }
        rules[index] = replacement
        native.stateDirty?.setBoolean(state, true)
        @Suppress("UNCHECKED_CAST")
        val paths = native.statePaths?.get(state) as? MutableMap<Int, String>
        paths?.put(index, choice.path)
        val view = native.presenterView?.get(presenter)
        if (view != null && native.presenterRefresh != null) {
            native.presenterRefresh.invoke(presenter, view)
        }
        Log.i(
            TAG,
            "BRIDGE EDIT | replaced native ft4 bridge rule | index=$index | " +
                PlaylistBridgePolicy.safePath(choice.path)
        )
    }

    private fun createRule(
        native: Bindings,
        choice: PlaylistChoice
    ): Any =
        native.smartRuleConstructor.newInstance(
            -1,
            0,
            PlaylistBridgeReference.encode(choice.path, choice.displayName),
            0
        )

    private fun selectedRule(presenter: Any): Any? = runCatching {
        val native = bindings ?: return@runCatching null
        val state = native.presenterState.invoke(presenter)
        val indexField = native.stateSelectedIndex ?: return@runCatching null
        val index = indexField.getInt(state)
        val rules = native.stateRules.invoke(state) as? List<*>
        rules?.getOrNull(index)
    }.getOrNull()

    private fun loadPlaylistChoices(native: Bindings): List<PlaylistChoice> {
        val db = native.databaseSingleton.get(null)
            ?: throw IllegalStateException("GMDatabase singleton is null")
        val dao = native.playlistDaoGetter.invoke(db)
            ?: throw IllegalStateException("PlaylistDao is null")
        val rows = native.playlistDaoAll.invoke(dao) as? List<*>
            ?: emptyList<Any>()
        val raw = rows.mapNotNull { row ->
            if (row == null) return@mapNotNull null
            val strings = PlaylistBridgeReflectionResolver.fields(row.javaClass)
                .asSequence()
                .filter { it.type == String::class.java }
                .mapNotNull { field ->
                    field.isAccessible = true
                    runCatching { field.get(row) as? String }.getOrNull()
                }
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            val source = strings.firstNotNullOfOrNull { raw ->
                playlistFile(raw)?.takeIf {
                    it.extension.lowercase() in SUPPORTED_EXTENSIONS
                }?.let { raw to it }
            } ?: return@mapNotNull null
            val uri = source.first
            val file = source.second
            val display = strings.firstOrNull { value ->
                value != uri &&
                    !value.startsWith("file://", ignoreCase = true) &&
                    !value.startsWith("content://", ignoreCase = true) &&
                    !value.contains(File.separatorChar)
            } ?: file.nameWithoutExtension
            PlaylistChoice(
                path = canonicalPath(file),
                displayName = display,
                label = display
            )
        }.distinctBy { it.path }

        val duplicateNames = raw
            .groupingBy { it.displayName.lowercase() }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        return raw.map { choice ->
            if (choice.displayName.lowercase() in duplicateNames) {
                val parent = File(choice.path).parentFile?.name.orEmpty()
                choice.copy(
                    label = if (parent.isBlank()) {
                        choice.displayName
                    } else {
                        choice.displayName + " — " + parent
                    }
                )
            } else {
                choice
            }
        }.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
    }

    private fun playlistFile(raw: String): File? {
        if (raw.startsWith("content://", ignoreCase = true)) {
            Log.w(
                TAG,
                "BRIDGE DAO | content URI skipped; native file parser requires File"
            )
            return null
        }
        val path = if (raw.startsWith("file://", ignoreCase = true)) {
            runCatching { android.net.Uri.parse(raw).path }.getOrNull()
        } else {
            raw
        }
        return path?.takeUnless(String::isBlank)?.let(::File)
    }

    private fun membership(path: String, native: Bindings): Membership {
        val file = File(path)
        if (!file.isFile) {
            throw IllegalStateException("Linked playlist is missing")
        }
        if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) {
            throw IllegalArgumentException("Unsupported playlist extension")
        }
        val canonical = canonicalPath(file)
        val modified = file.lastModified()
        val length = file.length()
        cache[canonical]?.let { cached ->
            if (
                cached.lastModified == modified &&
                cached.length == length
            ) {
                Log.i(
                    TAG,
                    "BRIDGE SOURCE | cache hit | entries=${cached.paths.size} | " +
                        PlaylistBridgePolicy.safePath(canonical)
                )
                return cached
            }
        }

        val sourceModel = native.fileModelConstructor.newInstance(file, null)
        val playlist = native.playlistFileConstructor.newInstance(sourceModel)
        native.playlistRead.invoke(null, playlist, "", 1)
        val entries = native.playlistEntries.get(playlist) as? Collection<*>
            ?: emptyList<Any>()
        val paths = entries.mapNotNull { entry ->
            if (entry == null) {
                null
            } else {
                (native.fileModelFile.get(entry) as? File)
                    ?.let(::canonicalPath)
            }
        }.distinct()

        val loaded = Membership(modified, length, paths)
        cache[canonical] = loaded
        Log.i(
            TAG,
            "BRIDGE SOURCE | native hp3 parsed | entries=${paths.size} | " +
                PlaylistBridgePolicy.safePath(canonical)
        )
        return loaded
    }

    private fun falsePredicate(native: Bindings): Any =
        native.nativeEquals.invoke(null, native.idField, Long.MIN_VALUE)

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun showError(context: Context, action: String) {
        Toast.makeText(
            context,
            NativeGmmpUiText.error(context, action),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun createBindings(loader: ClassLoader): Bindings {
        val r = PlaylistBridgeReflectionResolver

        // GMMP 4.2.1 shifted this family of R8 names by three letters on the
        // tested build (for example ws4 -> ts4). Names are fast paths only;
        // every class/member is shape-validated below.
        val smartRuleClass = r.loadClass(
            loader,
            listOf("ct4", "ft4"),
            "Playlist Link leaf Smart rule"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterTypes.contentEquals(
                    arrayOf(
                        Integer.TYPE,
                        Integer.TYPE,
                        String::class.java,
                        Integer.TYPE
                    )
                )
            }
        }
        val smartRuleConstructor = r.constructor(
            smartRuleClass,
            "Playlist Link leaf-rule constructor"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(
                    Integer.TYPE,
                    Integer.TYPE,
                    String::class.java,
                    Integer.TYPE
                )
            )
        }
        val baseRuleClass = smartRuleClass.superclass
            ?: throw IllegalStateException("Playlist Link leaf rule has no base class")
        val probeValue = "gonesmart-playlist-v2:binding-probe"
        val probeRule = smartRuleConstructor.newInstance(-1, 0, probeValue, 0)
        val ruleValue = r.field(
            smartRuleClass,
            listOf("q"),
            "Playlist Link rule value"
        ) { field ->
            field.type == String::class.java &&
                runCatching { field.isAccessible = true; field.get(probeRule) == probeValue }
                    .getOrDefault(false)
        }
        val ruleSentinel = r.field(
            smartRuleClass,
            listOf("o"),
            "Playlist Link rule sentinel"
        ) { field ->
            field.type == Integer.TYPE &&
                runCatching { field.isAccessible = true; field.getInt(probeRule) == -1 }
                    .getOrDefault(false)
        }

        val evaluationMethod = r.method(
            smartRuleClass,
            listOf("z"),
            "Playlist Link rule evaluator"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 2 &&
                java.util.LinkedHashSet::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                (method.parameterTypes[1] == java.lang.Integer::class.java ||
                    method.parameterTypes[1] == Integer.TYPE) &&
                method.returnType != java.lang.Void.TYPE
        }
        val predicateClass = evaluationMethod.returnType

        val presenterClass = r.loadClass(
            loader,
            listOf("as4", "ds4"),
            "Smart editor presenter"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterTypes.contentEquals(
                    arrayOf(Context::class.java, android.os.Bundle::class.java)
                )
            }
        }
        val presenterConstructor = r.constructor(
            presenterClass,
            "Smart editor presenter constructor"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(Context::class.java, android.os.Bundle::class.java)
            )
        }
        val presenterAddRule = r.method(
            presenterClass,
            listOf("P1"),
            "Smart editor add-rule action"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(smartRuleClass)
        }
        val presenterState = r.method(
            presenterClass,
            listOf("V1"),
            "Smart editor state accessor"
        ) { method ->
            method.parameterCount == 0 &&
                method.returnType != java.lang.Void.TYPE &&
                r.methods(method.returnType).any { candidate ->
                    candidate.parameterCount == 0 &&
                        java.util.List::class.java.isAssignableFrom(candidate.returnType)
                }
        }
        val stateClass = presenterState.returnType
        val stateRules = r.method(
            stateClass,
            listOf("b"),
            "Smart editor rule-list accessor"
        ) { method ->
            method.parameterCount == 0 &&
                java.util.List::class.java.isAssignableFrom(method.returnType)
        }
        val statePaths = r.optionalField(stateClass, listOf("x")) { field ->
            java.util.Map::class.java.isAssignableFrom(field.type)
        }
        val stateSelectedIndex = r.optionalField(stateClass, listOf("y")) {
            it.type == Integer.TYPE
        }
        val stateDirty = r.optionalField(stateClass, listOf("v")) {
            it.type == java.lang.Boolean.TYPE
        }
        val presenterView = r.optionalField(presenterClass, listOf("r")) { field ->
            !field.type.isPrimitive &&
                !Context::class.java.isAssignableFrom(field.type)
        }
        val presenterRefresh = r.optionalMethod(
            presenterClass,
            listOf("Z1")
        ) { method ->
            method.parameterCount == 1 &&
                method.returnType == java.lang.Void.TYPE &&
                (presenterView == null ||
                    method.parameterTypes[0].isAssignableFrom(presenterView.type) ||
                    presenterView.type.isAssignableFrom(method.parameterTypes[0]))
        }
        // Only the accepted GMMP 4.2.0 boundary is authoritative here.
        // On 4.2.1 as4 exposes h2(boolean), but the real user action was
        // passively correlated downstream at as4$g.accept(...); never promote
        // h2 solely because it has the old boolean shape.
        val presenterLinkSmartPlaylist = if (presenterClass.name == "ds4") {
            presenterClass.declaredMethods.singleOrNull { method ->
                method.name == "g2" &&
                    !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.contentEquals(
                        arrayOf(java.lang.Boolean.TYPE)
                    ) &&
                    method.returnType == java.lang.Void.TYPE
            }?.apply { isAccessible = true }
                ?: throw IllegalStateException(
                    "Accepted ds4.g2(boolean) link action is unavailable"
                )
        } else {
            null
        }
        val ruleIdGetter = r.optionalMethod(smartRuleClass, listOf("d")) { method ->
            method.parameterCount == 0 &&
                (Number::class.java.isAssignableFrom(method.returnType) ||
                    method.returnType == java.lang.Long.TYPE)
        }
        val ruleIdSetter = r.optionalMethod(smartRuleClass, listOf("w")) { method ->
            method.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))
        }

        val parserClass = r.loadClass(
            loader,
            listOf("ep3", "hp3"),
            "native playlist parser"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterCount == 1 &&
                    ctor.parameterTypes[0].declaredConstructors.any { modelCtor ->
                        modelCtor.parameterTypes.contentEquals(
                            arrayOf(File::class.java, java.lang.Long::class.java)
                        )
                    }
            }
        }
        val playlistFileConstructor = r.constructor(
            parserClass,
            "native playlist parser constructor"
        ) { ctor ->
            ctor.parameterCount == 1 &&
                ctor.parameterTypes[0].declaredConstructors.any { modelCtor ->
                    modelCtor.parameterTypes.contentEquals(
                        arrayOf(File::class.java, java.lang.Long::class.java)
                    )
                }
        }
        val fileModelClass = playlistFileConstructor.parameterTypes[0]
        val fileModelConstructor = r.constructor(
            fileModelClass,
            "native playlist entry/file model constructor"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(File::class.java, java.lang.Long::class.java)
            )
        }
        val playlistRead = r.method(
            parserClass,
            listOf("c"),
            "native playlist read action"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(
                    arrayOf(parserClass, String::class.java, Integer.TYPE)
                )
        }
        val playlistEntries = r.field(
            parserClass,
            listOf("r"),
            "native parsed playlist entries"
        ) { field -> java.util.Collection::class.java.isAssignableFrom(field.type) }
        val fileModelFile = r.field(
            fileModelClass,
            listOf("a"),
            "native playlist entry file"
        ) { field -> File::class.java.isAssignableFrom(field.type) }

        val searchHelperClass = r.loadClass(
            loader,
            listOf("lt0", "ot0"),
            "native Smart query helper"
        ) { type ->
            r.methods(type).any { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 2 &&
                    java.util.List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                    (method.returnType == predicateClass ||
                        predicateClass.isAssignableFrom(method.returnType))
            }
        }
        val nativeIn = r.method(
            searchHelperClass,
            listOf("t"),
            "native IN predicate builder"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 2 &&
                java.util.List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                (method.returnType == predicateClass ||
                    predicateClass.isAssignableFrom(method.returnType))
        }
        val queryFieldClass = nativeIn.parameterTypes[0]
        val nativeEquals = r.method(
            searchHelperClass,
            listOf("p"),
            "native equality predicate builder"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == queryFieldClass &&
                !java.util.List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                (method.returnType == predicateClass ||
                    predicateClass.isAssignableFrom(method.returnType))
        }
        val trackFieldClass = r.loadClass(
            loader,
            listOf("w75", "z75"),
            "native track query fields"
        ) { type ->
            runCatching {
                val uri = type.getDeclaredField("URI")
                val id = type.getDeclaredField("ID")
                java.lang.reflect.Modifier.isStatic(uri.modifiers) &&
                    java.lang.reflect.Modifier.isStatic(id.modifiers) &&
                    queryFieldClass.isAssignableFrom(uri.type) &&
                    queryFieldClass.isAssignableFrom(id.type)
            }.getOrDefault(false)
        }
        val uriField = trackFieldClass.getDeclaredField("URI").apply {
            isAccessible = true
        }.get(null)
        val idField = trackFieldClass.getDeclaredField("ID").apply {
            isAccessible = true
        }.get(null)

        val whereGroupClass = sequenceOf(
            predicateClass,
            r.optionalClass(loader, listOf("ww3", "zw3")) { candidate ->
                predicateClass.isAssignableFrom(candidate) ||
                    candidate.isAssignableFrom(predicateClass)
            }
        ).filterNotNull().firstOrNull { candidate ->
            candidate.declaredConstructors.any { ctor ->
                ctor.parameterTypes.contentEquals(
                    arrayOf(java.util.List::class.java, String::class.java)
                )
            }
        } ?: throw IllegalStateException("Native OR predicate group is unavailable")
        val whereGroupConstructor = r.constructor(
            whereGroupClass,
            "native OR predicate group"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(java.util.List::class.java, String::class.java)
            )
        }

        val dbClass = loader.loadClass("gonemad.gmmp.data.database.GMDatabase")
        val playlistDaoClass = r.loadClass(
            loader,
            listOf("ho3", "ko3"),
            "native Playlist DAO"
        ) { type ->
            r.methods(type).any { method ->
                method.parameterCount == 0 &&
                    java.util.List::class.java.isAssignableFrom(method.returnType)
            }
        }
        val databaseSingleton = r.field(
            dbClass,
            listOf("l"),
            "GMDatabase singleton"
        ) { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                dbClass.isAssignableFrom(field.type)
        }
        val playlistDaoGetter = r.method(
            dbClass,
            listOf("E"),
            "GMDatabase Playlist DAO accessor"
        ) { method ->
            method.parameterCount == 0 &&
                playlistDaoClass.isAssignableFrom(method.returnType)
        }
        val playlistDaoAll = r.method(
            playlistDaoClass,
            listOf("G1"),
            "native Playlist DAO list reader"
        ) { method ->
            method.parameterCount == 0 &&
                java.util.List::class.java.isAssignableFrom(method.returnType)
        }

        val dialogEventClass = r.loadClass(
            loader,
            listOf("wn4", "zn4"),
            "native list-dialog event"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterCount == 3 &&
                    ctor.parameterTypes[0] == String::class.java &&
                    java.util.List::class.java.isAssignableFrom(ctor.parameterTypes[1]) &&
                    ctor.parameterTypes[2].isInterface
            }
        }
        val dialogEventConstructor = r.constructor(
            dialogEventClass,
            "native list-dialog event constructor"
        ) { ctor ->
            ctor.parameterCount == 3 &&
                ctor.parameterTypes[0] == String::class.java &&
                java.util.List::class.java.isAssignableFrom(ctor.parameterTypes[1]) &&
                ctor.parameterTypes[2].isInterface
        }
        val dialogCallbackClass = dialogEventConstructor.parameterTypes[2]
        val eventBusClass = r.loadClass(
            loader,
            listOf("dc1", "gc1"),
            "native event bus"
        ) { type ->
            r.methods(type).any { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    type.isAssignableFrom(method.returnType)
            }
        }
        val eventBusGet = r.method(
            eventBusClass,
            listOf("b"),
            "native event-bus singleton accessor"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                eventBusClass.isAssignableFrom(method.returnType)
        }
        val eventBusPost = r.method(
            eventBusClass,
            listOf("f"),
            "native event-bus post"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(dialogEventClass)
        }
        val unitClass = r.loadClass(
            loader,
            listOf("rf5", "uf5"),
            "Kotlin Unit runtime value"
        ) { type ->
            r.fields(type).any { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    type.isAssignableFrom(field.type)
            }
        }
        val unitField = r.field(
            unitClass,
            listOf("a"),
            "Kotlin Unit singleton"
        ) { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                unitClass.isAssignableFrom(field.type)
        }

        val popupMenuClass = loader.loadClass("androidx.appcompat.widget.PopupMenu")
        val popupMenuListenerClass = loader.loadClass(
            "androidx.appcompat.widget.PopupMenu\$OnMenuItemClickListener"
        )
        val popupMenuConstructor = popupMenuClass.getDeclaredConstructor(
            Context::class.java,
            View::class.java
        ).apply { isAccessible = true }
        val popupMenuGetMenu = popupMenuClass.getDeclaredMethod("getMenu").apply {
            isAccessible = true
        }
        val popupMenuSetListener = popupMenuClass.getDeclaredMethod(
            "setOnMenuItemClickListener",
            popupMenuListenerClass
        ).apply { isAccessible = true }
        val popupMenuShow = popupMenuClass.getDeclaredMethod("show").apply {
            isAccessible = true
        }

        val smartPlaylistClass = r.loadClass(
            loader,
            listOf("ts4", "ws4"),
            "native Smart-Playlist model"
        ) { type ->
            r.methods(type).any { method ->
                method.parameterTypes.contentEquals(arrayOf(File::class.java)) &&
                    (method.returnType == java.lang.Boolean.TYPE ||
                        method.returnType == java.lang.Boolean::class.java)
            } &&
                r.fields(type).any { java.util.List::class.java.isAssignableFrom(it.type) } &&
                r.fields(type).any { it.type == java.lang.Boolean.TYPE }
        }
        val smartPlaylistConstructor = smartPlaylistClass.declaredConstructors
            .firstOrNull { it.parameterCount == 0 }
            ?: smartPlaylistClass.declaredConstructors.singleOrNull { ctor ->
                ctor.parameterCount == 6 &&
                    ctor.parameterTypes[1] == Integer.TYPE &&
                    ctor.parameterTypes[2] == Integer.TYPE &&
                    ctor.parameterTypes[3] == Integer.TYPE &&
                    ctor.parameterTypes[5] == Integer.TYPE
            }
            ?: throw IllegalStateException(
                "Native Smart-Playlist constructor shape is ambiguous"
            )
        smartPlaylistConstructor.isAccessible = true
        val smartPlaylistConstructorArgs: Array<Any?> =
            if (smartPlaylistConstructor.parameterCount == 0) {
                emptyArray()
            } else {
                arrayOf(null, 0, 0, 0, null, 255)
            }
        val smartPlaylistSave = r.method(
            smartPlaylistClass,
            listOf("t"),
            "native Smart-Playlist writer"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(File::class.java)) &&
                (method.returnType == java.lang.Boolean.TYPE ||
                    method.returnType == java.lang.Boolean::class.java)
        }
        val smartPlaylistName = r.field(
            smartPlaylistClass,
            listOf("o"),
            "Smart-Playlist name"
        ) { it.type == String::class.java }
        val smartPlaylistRules = r.field(
            smartPlaylistClass,
            listOf("u"),
            "Smart-Playlist rule list"
        ) { java.util.List::class.java.isAssignableFrom(it.type) }
        val smartPlaylistMatchAll = r.field(
            smartPlaylistClass,
            listOf("s"),
            "Smart-Playlist match-all flag"
        ) { it.type == java.lang.Boolean.TYPE }

        val groupRuleClass = r.loadClass(
            loader,
            listOf("gt4", "jt4"),
            "Smart-rule group"
        ) { type ->
            type != baseRuleClass &&
                baseRuleClass.isAssignableFrom(type) &&
                r.fields(type).any { java.util.List::class.java.isAssignableFrom(it.type) } &&
                r.fields(type).any { it.type == java.lang.Boolean.TYPE }
        }
        val groupRules = r.field(
            groupRuleClass,
            listOf("o"),
            "Smart-rule group children"
        ) { java.util.List::class.java.isAssignableFrom(it.type) }
        val groupMatchAll = r.field(
            groupRuleClass,
            listOf("p"),
            "Smart-rule group match-all flag"
        ) { it.type == java.lang.Boolean.TYPE }

        val chooserConsumerAccept = r.optionalClass(
            loader,
            listOf(presenterClass.name + "\$g")
        )?.let { consumerClass ->
            r.optionalMethod(consumerClass, listOf("accept")) { method ->
                method.parameterCount == 1 && method.name == "accept"
            }
        }
        val ruleLabelFormatter = r.optionalClass(
            loader,
            listOf("ls2", "os2")
        )?.let { formatterClass ->
            r.optionalMethod(formatterClass, listOf("U")) { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0].isAssignableFrom(smartRuleClass) &&
                    method.returnType == String::class.java
            }
        }

        Log.i(
            TAG,
            "BRIDGE MAPPING | presenter=${presenterClass.name}" +
                " | leaf=${smartRuleClass.name}" +
                " | smart=${smartPlaylistClass.name}" +
                " | parser=${parserClass.name}" +
                " | dao=${playlistDaoClass.name}" +
                " | predicate=${predicateClass.name}" +
                " | linkBoundary=" +
                (presenterLinkSmartPlaylist?.let {
                    it.declaringClass.name + "." + it.name
                } ?: chooserConsumerAccept?.let {
                    it.declaringClass.name + "." + it.name
                } ?: "none")
        )

        return Bindings(
            loader = loader,
            presenterClass = presenterClass,
            presenterConstructor = presenterConstructor,
            baseRuleClass = baseRuleClass,
            smartRuleClass = smartRuleClass,
            smartRuleConstructor = smartRuleConstructor,
            ruleSentinel = ruleSentinel,
            ruleValue = ruleValue,
            presenterAddRule = presenterAddRule,
            presenterState = presenterState,
            presenterRefresh = presenterRefresh,
            presenterView = presenterView,
            stateRules = stateRules,
            statePaths = statePaths,
            stateSelectedIndex = stateSelectedIndex,
            stateDirty = stateDirty,
            ruleIdGetter = ruleIdGetter,
            ruleIdSetter = ruleIdSetter,
            playlistFileConstructor = playlistFileConstructor,
            fileModelConstructor = fileModelConstructor,
            playlistRead = playlistRead,
            playlistEntries = playlistEntries,
            fileModelFile = fileModelFile,
            nativeIn = nativeIn,
            nativeEquals = nativeEquals,
            uriField = uriField,
            idField = idField,
            whereGroupConstructor = whereGroupConstructor,
            databaseSingleton = databaseSingleton,
            playlistDaoGetter = playlistDaoGetter,
            playlistDaoAll = playlistDaoAll,
            dialogEventConstructor = dialogEventConstructor,
            dialogCallbackClass = dialogCallbackClass,
            eventBusGet = eventBusGet,
            eventBusPost = eventBusPost,
            unitValue = unitField.get(null),
            presenterLinkSmartPlaylist = presenterLinkSmartPlaylist,
            popupMenuConstructor = popupMenuConstructor,
            popupMenuGetMenu = popupMenuGetMenu,
            popupMenuSetListener = popupMenuSetListener,
            popupMenuShow = popupMenuShow,
            popupMenuListenerClass = popupMenuListenerClass,
            smartPlaylistConstructor = smartPlaylistConstructor,
            smartPlaylistConstructorArgs = smartPlaylistConstructorArgs,
            smartPlaylistSave = smartPlaylistSave,
            smartPlaylistName = smartPlaylistName,
            smartPlaylistRules = smartPlaylistRules,
            smartPlaylistMatchAll = smartPlaylistMatchAll,
            groupRuleClass = groupRuleClass,
            groupRules = groupRules,
            groupMatchAll = groupMatchAll,
            chooserConsumerAccept = chooserConsumerAccept,
            ruleLabelFormatter = ruleLabelFormatter,
            evaluationMethod = evaluationMethod
        )
    }

    private fun menuContext(menu: Menu, inflater: Any?): Context? {
        val viaMenu = runCatching {
            menu.javaClass.methods.firstOrNull {
                it.name == "getContext" && it.parameterCount == 0
            }?.invoke(menu) as? Context
        }.getOrNull()
        if (viaMenu != null) return viaMenu

        return if (inflater is MenuInflater) {
            runCatching {
                findField(inflater.javaClass, "mContext")
                    .apply { isAccessible = true }
                    .get(inflater) as? Context
            }.getOrNull()
        } else {
            null
        }
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(type.name + "." + name)
    }


}
