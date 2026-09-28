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
        val baseRuleClass: Class<*>,
        val smartRuleClass: Class<*>,
        val smartRuleConstructor: Constructor<*>,
        val presenterAddRule: Method,
        val presenterState: Method,
        val presenterRefresh: Method,
        val stateRules: Method,
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
        val playlistEntityUri: Field,
        val playlistEntityName: Field,
        val dialogEventConstructor: Constructor<*>,
        val dialogCallbackClass: Class<*>,
        val eventBusGet: Method,
        val eventBusPost: Method,
        val unitValue: Any?,
        val presenterLinkSmartPlaylist: Method,
        val popupMenuConstructor: Constructor<*>,
        val popupMenuGetMenu: Method,
        val popupMenuSetListener: Method,
        val popupMenuShow: Method,
        val popupMenuListenerClass: Class<*>
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoneSmartPlaylistBridge").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, Membership>()

    @Volatile private var bindings: Bindings? = null
    @Volatile private var presenterRef: WeakReference<Any>? = null
    @Volatile private var contextRef: WeakReference<Context>? = null

    fun configure(loader: ClassLoader): Boolean {
        val loaded = runCatching { createBindings(loader) }
            .onFailure {
                Log.e(TAG, "POC BINDINGS FAILED | native GMMP untouched", it)
            }
            .getOrNull()
        bindings = loaded
        Log.i(TAG, "POC BINDINGS | ready=${loaded != null}")
        return loaded != null
    }

    fun capturePresenter(presenter: Any?, context: Context?) {
        val native = bindings ?: return
        if (presenter == null || !native.presenterClass.isInstance(presenter)) return
        presenterRef = WeakReference(presenter)
        context?.let { contextRef = WeakReference(it) }
        Log.i(TAG, "POC PRESENTER | captured=${presenter.javaClass.name}")
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
            Log.w(TAG, "POC MENU SKIP | original menuLink missing")
            return
        }

        // Keep GMMP's one ORIGINAL toolbar action/icon. Its click now opens
        // the host AppCompat popup menu below that exact action item and
        // dispatches either back into ds4.g2(false) or Playlist Bridge.
        original.setOnMenuItemClickListener {
            if (!showLinkTypeMenu(context, nativeLinkId)) {
                Log.w(
                    TAG,
                    "POC TYPE MENU FALLBACK | anchor/popup unavailable; " +
                        "opening original Smart Playlist linker"
                )
                openNativeSmartPlaylistChooser()
            }
            true
        }
        Log.i(
            TAG,
            "POC MENU | original menuLink reused as Smart Playlist / Playlist chooser"
        )
    }

    fun currentContext(): Context? = contextRef?.get()

    fun nativeSmartPlaylistLinkTitle(): String? =
        contextRef?.get()?.let(NativeGmmpUiText::linkSmartPlaylist)

    fun rewriteNativeSmartPlaylistRuleLabel(
        rule: Any?,
        original: String?
    ): String? {
        val native = bindings ?: return original
        if (rule == null || !native.smartRuleClass.isInstance(rule)) {
            return original
        }
        val value = runCatching {
            findField(rule.javaClass, "q")
                .apply { isAccessible = true }
                .get(rule) as? String
        }.getOrNull()
        if (
            !PlaylistBridgeDiagnosticPolicy
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
            ?: return false
        val anchor = activity.window?.decorView
            ?.findViewById<View>(nativeLinkId)
            ?: return false

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
                        when (selected?.order) {
                            0 -> openNativeSmartPlaylistChooser()
                            1 -> openChooser(edit = false)
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
                "POC TYPE MENU | host PopupMenu shown | options=smart,playlist"
            )
            true
        }.onFailure {
            Log.e(TAG, "POC TYPE MENU FAILED", it)
        }.getOrDefault(false)
    }

    private fun openNativeSmartPlaylistChooser() {
        val native = bindings ?: return
        val presenter = presenterRef?.get() ?: run {
            Log.w(TAG, "POC SMART CHOOSER | no active SmartEditorPresenter")
            return
        }
        runCatching {
            native.presenterLinkSmartPlaylist.invoke(presenter, false)
        }.onFailure {
            Log.e(TAG, "POC SMART CHOOSER | original ds4.g2(false) failed", it)
        }
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

    fun interceptNativeLinkedEditor(presenter: Any?, edit: Boolean): Boolean {
        if (!edit || presenter == null) return false
        val rule = selectedRule(presenter) ?: return false
        if (!isBridgeRule(rule)) return false
        presenterRef = WeakReference(presenter)
        Log.i(TAG, "POC EDIT | intercepted native linked-playlist editor")
        openChooser(edit = true, explicitPresenter = presenter)
        return true
    }

    fun isBridgeRule(rule: Any?): Boolean {
        val native = bindings ?: return false
        if (rule == null || !native.smartRuleClass.isInstance(rule)) return false
        return runCatching {
            val field = findField(rule.javaClass, "o").apply { isAccessible = true }
                .getInt(rule)
            val value = findField(rule.javaClass, "q").apply { isAccessible = true }
                .get(rule) as? String
            field == -1 && PlaylistBridgeReference.decode(value) != null
        }.getOrDefault(false)
    }

    fun failClosedPredicate(): Any? = bindings?.let(::falsePredicate)

    fun compile(rule: Any): Any {
        val native = bindings
            ?: throw IllegalStateException("Playlist Bridge bindings missing")
        val value = findField(rule.javaClass, "q").apply { isAccessible = true }
            .get(rule) as? String
        val reference = PlaylistBridgeReference.decode(value)
            ?: return falsePredicate(native)
        val started = System.nanoTime()
        val membership = runCatching { membership(reference.path, native) }
            .onFailure {
                Log.e(
                    TAG,
                    "POC COMPILE FAILED | " +
                        PlaylistBridgeDiagnosticPolicy.safePath(reference.path),
                    it
                )
            }
            .getOrNull()
            ?: return falsePredicate(native)

        if (membership.paths.isEmpty()) {
            Log.i(
                TAG,
                "POC COMPILE | empty source -> false predicate | " +
                    PlaylistBridgeDiagnosticPolicy.safePath(reference.path)
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
            "POC COMPILE | entries=${membership.paths.size}" +
                " | chunks=${clauses.size}" +
                " | elapsedMs=${(System.nanoTime() - started) / 1_000_000L}" +
                " | " + PlaylistBridgeDiagnosticPolicy.safePath(reference.path)
        )
        return result
    }

    private fun openChooser(
        edit: Boolean,
        explicitPresenter: Any? = null
    ) {
        val native = bindings ?: return
        val presenter = explicitPresenter ?: presenterRef?.get() ?: run {
            Log.w(TAG, "POC CHOOSER | no active SmartEditorPresenter")
            return
        }
        if (!native.presenterClass.isInstance(presenter)) return
        val context = contextRef?.get() ?: return
        val title = NativeGmmpUiText.string(context, "link_playlist") ?: return

        worker.execute {
            val choices = runCatching { loadPlaylistChoices(native) }
                .onFailure {
                    Log.e(TAG, "POC CHOOSER | PlaylistDao load failed", it)
                }
                .getOrNull()
            main.post {
                if (choices.isNullOrEmpty()) {
                    showError(context, title)
                    return@post
                }
                if (presenterRef?.get() !== presenter && explicitPresenter == null) {
                    Log.w(TAG, "POC CHOOSER | presenter changed before dialog")
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
            "POC CHOOSER | native zn4 dialog posted | " +
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
        worker.execute {
            val validation = runCatching { membership(choice.path, native) }
            if (validation.isFailure) {
                Log.e(
                    TAG,
                    "POC SELECT | native playlist parse failed",
                    validation.exceptionOrNull()
                )
                main.post { showError(context, title) }
                return@execute
            }
            main.post {
                runCatching {
                    if (edit) {
                        replaceRule(native, presenter, choice)
                    } else {
                        addRule(native, presenter, choice)
                    }
                }.onFailure {
                    Log.e(TAG, "POC SELECT | editor update failed", it)
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
        val paths = findField(state.javaClass, "x")
            .apply { isAccessible = true }
            .get(state) as? MutableMap<Int, String>
        paths?.put(rules.size, choice.path)
        val rule = createRule(native, choice)
        native.presenterAddRule.invoke(presenter, rule)
        Log.i(
            TAG,
            "POC ADD | persisted native ft4 bridge rule | " +
                PlaylistBridgeDiagnosticPolicy.safePath(choice.path)
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
        val index = findField(state.javaClass, "y")
            .apply { isAccessible = true }
            .getInt(state)
        val previous = rules.getOrNull(index)
            ?: throw IndexOutOfBoundsException("Smart rule index $index")
        val replacement = createRule(native, choice)
        runCatching {
            val id = previous.javaClass.getMethod("d").invoke(previous) as Number
            replacement.javaClass
                .getMethod("w", java.lang.Long.TYPE)
                .invoke(replacement, id.toLong())
        }
        rules[index] = replacement
        findField(state.javaClass, "v")
            .apply { isAccessible = true }
            .setBoolean(state, true)
        @Suppress("UNCHECKED_CAST")
        val paths = findField(state.javaClass, "x")
            .apply { isAccessible = true }
            .get(state) as? MutableMap<Int, String>
        paths?.put(index, choice.path)
        val view = findField(presenter.javaClass, "r")
            .apply { isAccessible = true }
            .get(presenter)
        if (view != null) {
            native.presenterRefresh.invoke(presenter, view)
        }
        Log.i(
            TAG,
            "POC EDIT | replaced native ft4 bridge rule | index=$index | " +
                PlaylistBridgeDiagnosticPolicy.safePath(choice.path)
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
        val index = findField(state.javaClass, "y")
            .apply { isAccessible = true }
            .getInt(state)
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
            val uri = native.playlistEntityUri.get(row) as? String
                ?: return@mapNotNull null
            val file = playlistFile(uri) ?: return@mapNotNull null
            if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) {
                return@mapNotNull null
            }
            val display = (native.playlistEntityName.get(row) as? String)
                ?.takeUnless(String::isBlank)
                ?: file.nameWithoutExtension
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
                "POC DAO | content URI skipped; native file parser requires File"
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
                    "POC SOURCE | cache hit | entries=${cached.paths.size} | " +
                        PlaylistBridgeDiagnosticPolicy.safePath(canonical)
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
            "POC SOURCE | native hp3 parsed | entries=${paths.size} | " +
                PlaylistBridgeDiagnosticPolicy.safePath(canonical)
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
        val presenterClass = loader.loadClass("ds4")
        val baseRuleClass = loader.loadClass("gt4")
        val smartRuleClass = loader.loadClass("ft4")
        val stateClass = loader.loadClass("hs4")
        val playlistFileClass = loader.loadClass("hp3")
        val fileModelClass = loader.loadClass("th1")
        val queryFieldClass = loader.loadClass("qw3")
        val searchHelperClass = loader.loadClass("ot0")
        val trackFieldClass = loader.loadClass("z75")
        val whereGroupClass = loader.loadClass("zw3")
        val dbClass =
            loader.loadClass("gonemad.gmmp.data.database.GMDatabase")
        val playlistDaoClass = loader.loadClass("ko3")
        val playlistEntityClass = loader.loadClass("gp3")
        val dialogEventClass = loader.loadClass("zn4")
        val dialogCallbackClass = loader.loadClass("mr1")
        val eventBusClass = loader.loadClass("gc1")
        val unitClass = loader.loadClass("uf5")
        val viewClass = loader.loadClass("fo2")
        val popupMenuClass =
            loader.loadClass("androidx.appcompat.widget.PopupMenu")
        val popupMenuListenerClass =
            loader.loadClass(
                "androidx.appcompat.widget.PopupMenu\$OnMenuItemClickListener"
            )

        return Bindings(
            loader = loader,
            presenterClass = presenterClass,
            baseRuleClass = baseRuleClass,
            smartRuleClass = smartRuleClass,
            smartRuleConstructor = smartRuleClass.getDeclaredConstructor(
                Integer.TYPE,
                Integer.TYPE,
                String::class.java,
                Integer.TYPE
            ).apply { isAccessible = true },
            presenterAddRule = presenterClass
                .getDeclaredMethod("P1", baseRuleClass)
                .apply { isAccessible = true },
            presenterState = presenterClass
                .getDeclaredMethod("V1")
                .apply { isAccessible = true },
            presenterRefresh = presenterClass
                .getDeclaredMethod("Z1", viewClass)
                .apply { isAccessible = true },
            stateRules = stateClass
                .getDeclaredMethod("b")
                .apply { isAccessible = true },
            playlistFileConstructor = playlistFileClass
                .getDeclaredConstructor(fileModelClass)
                .apply { isAccessible = true },
            fileModelConstructor = fileModelClass
                .getDeclaredConstructor(
                    File::class.java,
                    java.lang.Long::class.java
                )
                .apply { isAccessible = true },
            playlistRead = playlistFileClass
                .getDeclaredMethod(
                    "c",
                    playlistFileClass,
                    String::class.java,
                    Integer.TYPE
                )
                .apply { isAccessible = true },
            playlistEntries = findField(playlistFileClass, "r")
                .apply { isAccessible = true },
            fileModelFile = findField(fileModelClass, "a")
                .apply { isAccessible = true },
            nativeIn = searchHelperClass
                .getDeclaredMethod(
                    "t",
                    queryFieldClass,
                    java.util.List::class.java
                )
                .apply { isAccessible = true },
            nativeEquals = searchHelperClass
                .getDeclaredMethod(
                    "p",
                    queryFieldClass,
                    Any::class.java
                )
                .apply { isAccessible = true },
            uriField = trackFieldClass
                .getDeclaredField("URI")
                .apply { isAccessible = true }
                .get(null),
            idField = trackFieldClass
                .getDeclaredField("ID")
                .apply { isAccessible = true }
                .get(null),
            whereGroupConstructor = whereGroupClass
                .getDeclaredConstructor(
                    java.util.List::class.java,
                    String::class.java
                )
                .apply { isAccessible = true },
            databaseSingleton = dbClass
                .getDeclaredField("l")
                .apply { isAccessible = true },
            playlistDaoGetter = dbClass
                .getDeclaredMethod("E")
                .apply { isAccessible = true },
            playlistDaoAll = playlistDaoClass
                .getDeclaredMethod("G1")
                .apply { isAccessible = true },
            playlistEntityUri = playlistEntityClass
                .getDeclaredField("a")
                .apply { isAccessible = true },
            playlistEntityName = playlistEntityClass
                .getDeclaredField("b")
                .apply { isAccessible = true },
            dialogEventConstructor = dialogEventClass
                .getDeclaredConstructor(
                    String::class.java,
                    java.util.List::class.java,
                    dialogCallbackClass
                )
                .apply { isAccessible = true },
            dialogCallbackClass = dialogCallbackClass,
            eventBusGet = eventBusClass
                .getDeclaredMethod("b")
                .apply { isAccessible = true },
            eventBusPost = eventBusClass
                .getDeclaredMethod("f", Any::class.java)
                .apply { isAccessible = true },
            unitValue = unitClass
                .getDeclaredField("a")
                .apply { isAccessible = true }
                .get(null),
            presenterLinkSmartPlaylist = presenterClass
                .getDeclaredMethod("g2", java.lang.Boolean.TYPE)
                .apply { isAccessible = true },
            popupMenuConstructor = popupMenuClass
                .getDeclaredConstructor(
                    Context::class.java,
                    View::class.java
                )
                .apply { isAccessible = true },
            popupMenuGetMenu = popupMenuClass
                .getDeclaredMethod("getMenu")
                .apply { isAccessible = true },
            popupMenuSetListener = popupMenuClass
                .getDeclaredMethod(
                    "setOnMenuItemClickListener",
                    popupMenuListenerClass
                )
                .apply { isAccessible = true },
            popupMenuShow = popupMenuClass
                .getDeclaredMethod("show")
                .apply { isAccessible = true },
            popupMenuListenerClass = popupMenuListenerClass
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
