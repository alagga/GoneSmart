package io.github.alagga.gonesmart

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * Folder creation UI for the Playlist and Smart-Playlist folder surfaces.
 *
 * Primary GMMP 4.2.0 path:
 * - invoke the ORIGINAL tp3.onAddNewPlaylist() dialog;
 * - keep its native Aesthetic/Material input implementation untouched;
 * - relabel only its visible playlist wording to folder wording;
 * - intercept the verified sp3 input callback once and create exactly one
 *   canonical direct child directory instead of a .m3u.
 *
 * This deliberately reuses the same dialog that the maintainer verified has
 * correct GMMP colors from its first frame. The old MaterialDialogs
 * showNewFolderCreator path remains a fail-open compatibility fallback only.
 */
internal class NativeGmmpFolderCreator(
    private val classLoader: ClassLoader
) {
    internal enum class DialogKind {
        NONE,
        PLAYLIST_SHELL,
        LEGACY_FOLDER
    }

    companion object {
        private const val TAG = "GoneSmartPlaylist"
        private const val SHELL_TIMEOUT_MS = 600L
        private val lock = Any()
        private val main = Handler(Looper.getMainLooper())
        private var presenterRef = WeakReference<Any>(null)
        private var pending: PendingShell? = null
        private val shellDialogs = WeakHashMap<Dialog, Boolean>()
        private val legacyDialogs = WeakHashMap<Dialog, Boolean>()
        private val legacyShowDepth = ThreadLocal<Int>()

        private data class PendingShell(
            val token: Any,
            val presenter: Any,
            val context: Context,
            val directory: File,
            val onCreated: () -> Unit,
            var dialog: WeakReference<Dialog>? = null
        )

        fun observeMainPlaylistPresenter(presenter: Any?) {
            if (presenter?.javaClass?.name != "tp3") return
            val changed = synchronized(lock) {
                val previous = presenterRef.get()
                presenterRef = WeakReference(presenter)
                previous !== presenter
            }
            if (changed) {
                Log.i(
                    TAG,
                    "FOLDER CREATE SHELL | live tp3 presenter captured"
                )
            }
        }

        /**
         * Called by the global MaterialDialog.show() hook before native draw.
         * Only the one dialog synchronously/causally opened by our pending
         * tp3.onAddNewPlaylist() request is claimed as the folder shell.
         */
        fun classifyBeforeShow(dialog: Dialog): DialogKind {
            synchronized(lock) {
                if ((legacyShowDepth.get() ?: 0) > 0) {
                    legacyDialogs[dialog] = true
                    Log.i(
                        TAG,
                        "FOLDER CREATE FALLBACK | visible child dialog claimed"
                    )
                    return DialogKind.LEGACY_FOLDER
                }
                if (legacyDialogs.containsKey(dialog)) {
                    return DialogKind.LEGACY_FOLDER
                }
                if (shellDialogs.containsKey(dialog)) {
                    return DialogKind.PLAYLIST_SHELL
                }
                val request = pending ?: return DialogKind.NONE
                if (request.dialog?.get() != null) return DialogKind.NONE
                if (!containsInputField(dialog)) return DialogKind.NONE
                request.dialog = WeakReference(dialog)
                shellDialogs[dialog] = true
                Log.i(
                    TAG,
                    "FOLDER CREATE SHELL | native New Playlist dialog claimed"
                )
                return DialogKind.PLAYLIST_SHELL
            }
        }

        fun onPlaylistShellShown(dialog: Dialog) {
            if (!shellDialogs.containsKey(dialog)) return
            NativeGmmpCreationDialogLocalizer
                .localizeFolderShellTextWhenReady(dialog)
            val decor = dialog.window?.decorView ?: return
            decor.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) {
                        synchronized(lock) {
                            val request = pending
                            if (request?.dialog?.get() === dialog) {
                                pending = null
                            }
                            shellDialogs.remove(dialog)
                        }
                        v.removeOnAttachStateChangeListener(this)
                    }
                }
            )
        }

        /**
         * Called at the top of the verified sp3.invoke(Object,Object) hook.
         * Returns true only for the exact tp3 instance that opened our shell.
         */
        fun interceptNativeMainPlaylistCreate(
            callback: Any?,
            arg0: Any?,
            arg1: Any?
        ): Boolean {
            val request = synchronized(lock) { pending } ?: return false
            val owner = callback?.let {
                mainPresenterOwner(it, request.presenter)
            } ?: return false
            if (owner !== request.presenter) return false

            val input = listOf(arg0, arg1)
                .filterIsInstance<CharSequence>()
                .firstOrNull()
            val success = input?.let { createDirectChild(request, it) } == true
            synchronized(lock) {
                if (pending?.token === request.token) pending = null
            }
            if (success) {
                request.onCreated()
                Log.i(
                    TAG,
                    "FOLDER CREATE SHELL | original sp3 input consumed; " +
                        "direct child created"
                )
            } else {
                Toast.makeText(
                    request.context,
                    NativeGmmpUiText.string(request.context, "error") ?: "Error",
                    Toast.LENGTH_SHORT
                ).show()
                Log.w(
                    TAG,
                    "FOLDER CREATE SHELL | folder creation rejected"
                )
            }
            // Consume regardless of success: never create an accidental .m3u
            // when this exact native dialog was opened for a folder request.
            return true
        }

        private fun mainPresenterOwner(
            callback: Any,
            expectedPresenter: Any
        ): Any? = runCatching {
            generateSequence<Class<*>>(callback.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) }
                .firstNotNullOfOrNull { field ->
                    field.isAccessible = true
                    field.get(callback)?.takeIf { it === expectedPresenter }
                }
        }.getOrNull()

        private fun menuAddId(
            context: Context,
            menu: android.view.Menu?
        ): Int? {
            menu ?: return null
            for (index in 0 until menu.size()) {
                val item = menu.getItem(index)
                val name = runCatching {
                    context.resources.getResourceEntryName(item.itemId)
                }.getOrNull()
                if (name == "menuAdd") return item.itemId
            }
            return null
        }

        private fun resolveCallbackUnit(method: Method): Any? {
            val returnType = method.returnType
            if (returnType == java.lang.Void.TYPE) return null
            return returnType.declaredFields
                .filter {
                    Modifier.isStatic(it.modifiers) &&
                        returnType.isAssignableFrom(it.type)
                }
                .singleOrNull()
                ?.apply { isAccessible = true }
                ?.get(null)
        }

        private fun createDirectChild(
            request: PendingShell,
            input: CharSequence
        ): Boolean = runCatching {
            val name = input.toString().trim()
            if (name.isBlank() || name == "." || name == "..") return false
            val parent = request.directory.canonicalFile
            if (!parent.isDirectory || !parent.canWrite()) return false
            val target = File(parent, name).canonicalFile
            if (target.parentFile?.canonicalPath != parent.canonicalPath) {
                return false
            }
            if (target.exists()) return false
            target.mkdir()
        }.getOrDefault(false)

        private fun containsInputField(dialog: Dialog): Boolean {
            val root = dialog.window?.decorView ?: return false
            val id = dialog.context.resources.getIdentifier(
                "md_input_message",
                "id",
                dialog.context.packageName
            )
            if (id != 0 && root.findViewById<View>(id) is EditText) {
                return true
            }
            fun walk(view: View): Boolean {
                if (view is EditText) return true
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        if (walk(view.getChildAt(index))) return true
                    }
                }
                return false
            }
            return walk(root)
        }

        private inline fun <T> withLegacyShowScope(block: () -> T): T {
            val previous = legacyShowDepth.get() ?: 0
            legacyShowDepth.set(previous + 1)
            return try {
                block()
            } finally {
                if (previous == 0) legacyShowDepth.remove()
                else legacyShowDepth.set(previous)
            }
        }
    }

    fun show(
        context: Context,
        directory: File,
        nativePlaylistMenu: android.view.Menu? = null,
        onCreationCallback: () -> Unit
    ): Boolean {
        if (showNativePlaylistShell(
                context,
                directory,
                onCreationCallback,
                nativePlaylistMenu
            )
        ) return true
        return showLegacyFolderCreator(
            context,
            directory,
            onCreationCallback
        )
    }

    private fun showNativePlaylistShell(
        context: Context,
        directory: File,
        onCreationCallback: () -> Unit,
        nativePlaylistMenu: android.view.Menu?
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val presenter = synchronized(lock) { presenterRef.get() } ?: return false
        val canonical = runCatching { directory.canonicalFile }.getOrNull()
            ?: return false
        if (!canonical.isDirectory || !canonical.canWrite()) return false

        val token = Any()
        synchronized(lock) {
            if (pending != null) return false
            pending = PendingShell(
                token = token,
                presenter = presenter,
                context = context,
                directory = canonical,
                onCreated = onCreationCallback
            )
        }

        val nativeMenuAddId = menuAddId(context, nativePlaylistMenu)
        val invoked = if (nativeMenuAddId != null) {
            runCatching {
                val handled = nativePlaylistMenu
                    ?.performIdentifierAction(nativeMenuAddId, 0) == true
                require(handled) {
                    "GMMP native menuAdd action was not handled"
                }
                Log.i(
                    TAG,
                    "FOLDER CREATE SHELL | original menuAdd action dispatched"
                )
                true
            }.onFailure {
                Log.w(
                    TAG,
                    "FOLDER CREATE SHELL | original menuAdd dispatch unavailable",
                    it
                )
            }.getOrDefault(false)
        } else {
            // Accepted 4.2.0 fast path. Future builds should normally use the
            // original live menu action above instead of guessing a renamed
            // presenter method.
            val method = presenter.javaClass.declaredMethods.firstOrNull {
                it.name == "onAddNewPlaylist" && it.parameterCount == 0
            }?.apply { isAccessible = true }
            if (method == null) {
                false
            } else {
                runCatching {
                    method.invoke(presenter)
                    true
                }.onFailure {
                    Log.w(
                        TAG,
                        "FOLDER CREATE SHELL | native presenter action unavailable",
                        it
                    )
                }.getOrDefault(false)
            }
        }

        if (!invoked) {
            synchronized(lock) {
                if (pending?.token === token) pending = null
            }
            return false
        }

        // Native GMMP opens this synchronously on the tested 4.2.0 build.
        // If a future build posts the dialog or no longer does so, give it a
        // bounded chance and then fall back to the old native folder prompt.
        main.postDelayed({
            val needsFallback = synchronized(lock) {
                val current = pending
                if (current?.token !== token ||
                    current.dialog?.get() != null
                ) {
                    false
                } else {
                    pending = null
                    true
                }
            }
            if (needsFallback) {
                Log.w(
                    TAG,
                    "FOLDER CREATE SHELL | native dialog not observed; " +
                        "using legacy GMMP folder prompt"
                )
                showLegacyFolderCreator(
                    context,
                    canonical,
                    onCreationCallback
                )
            }
        }, SHELL_TIMEOUT_MS)

        Log.i(
            TAG,
            "FOLDER CREATE SHELL | original New Playlist shell invoked"
        )
        return true
    }

    private fun showLegacyFolderCreator(
        context: Context,
        directory: File,
        onCreationCallback: () -> Unit
    ): Boolean = runCatching {
        val dialogType = classLoader.loadClass(
            "com.afollestad.materialdialogs.MaterialDialog"
        )
        val behaviorType = classLoader.loadClass(
            "com.afollestad.materialdialogs.DialogBehavior"
        )
        val creatorType = classLoader.loadClass(
            "com.afollestad.materialdialogs.files.DialogFileChooserExtKt"
        )
        val behavior = dialogType.getDeclaredField("DEFAULT_BEHAVIOR")
            .apply { isAccessible = true }.get(null)
        val parentDialog = dialogType.getDeclaredConstructor(
            Context::class.java,
            behaviorType
        ).apply { isAccessible = true }.newInstance(context, behavior)

        fun compatible(method: Method): Boolean {
            val p = method.parameterTypes
            if (!Modifier.isStatic(method.modifiers) || p.size != 4) return false
            val firstOk =
                p[0].isAssignableFrom(dialogType) ||
                    dialogType.isAssignableFrom(p[0])
            val secondOk = File::class.java.isAssignableFrom(p[1])
            val thirdOk =
                p[2] == Integer::class.java ||
                    p[2] == Integer.TYPE
            val fourthOk = p[3].isInterface
            return firstOk && secondOk && thirdOk && fourthOk
        }

        val named = creatorType.declaredMethods.filter {
            it.name == "showNewFolderCreator" && compatible(it)
        }
        val creator = (
            named.singleOrNull()
                ?: creatorType.declaredMethods.filter(::compatible)
                    .singleOrNull()
            )?.apply { isAccessible = true }
            ?: error(
                "Native folder creator method is not structurally unique"
            )

        val callbackType = creator.parameterTypes[3]
        val callbackMethod = callbackType.methods
            .filter {
                Modifier.isAbstract(it.modifiers) &&
                    it.declaringClass != Any::class.java
            }
            .distinctBy {
                it.name + "|" +
                    it.parameterTypes.joinToString(",") { p -> p.name } +
                    "|" + it.returnType.name
            }
            .singleOrNull()
            ?: error(
                "Native folder callback SAM is not structurally unique"
            )
        val callbackResult = resolveCallbackUnit(callbackMethod)
        val callbackFallback = when (callbackMethod.returnType) {
            java.lang.Void.TYPE -> null
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }

        val callback = Proxy.newProxyInstance(
            callbackType.classLoader ?: classLoader,
            arrayOf(callbackType)
        ) { _, method, _ ->
            when (method.name) {
                callbackMethod.name -> {
                    onCreationCallback()
                    callbackResult ?: callbackFallback
                }
                "toString" -> "GoneSmart folder refresh"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        }

        val optionalIcon =
            if (creator.parameterTypes[2] == Integer.TYPE) 0 else null

        Log.i(
            TAG,
            "FOLDER CREATE MAPPING | creator=" +
                creator.declaringClass.name + "." + creator.name +
                " | callback=" + callbackType.name +
                " | callbackMethod=" + callbackMethod.name
        )
        withLegacyShowScope {
            creator.invoke(
                null,
                parentDialog,
                directory,
                optionalIcon,
                callback
            )
        }
        true
    }.onFailure {
        Log.e(
            TAG,
            "FOLDER CREATE | legacy GMMP dialog unavailable",
            it
        )
    }.getOrDefault(false)
}
