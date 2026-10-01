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
            val owner = callback?.let(::mainPresenterOwner) ?: return false
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

        private fun mainPresenterOwner(callback: Any): Any? = runCatching {
            callback.javaClass.declaredFields.firstOrNull {
                it.type.name == "tp3"
            }?.apply { isAccessible = true }?.get(callback)
        }.getOrNull()

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
        onCreationCallback: () -> Unit
    ): Boolean {
        if (showNativePlaylistShell(
                context,
                directory,
                onCreationCallback
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
        onCreationCallback: () -> Unit
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val presenter = synchronized(lock) { presenterRef.get() } ?: return false
        val method = presenter.javaClass.declaredMethods.firstOrNull {
            it.name == "onAddNewPlaylist" && it.parameterCount == 0
        }?.apply { isAccessible = true } ?: return false
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

        val invoked = runCatching {
            method.invoke(presenter)
            true
        }.onFailure {
            Log.w(
                TAG,
                "FOLDER CREATE SHELL | tp3.onAddNewPlaylist unavailable",
                it
            )
        }.getOrDefault(false)

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
            "FOLDER CREATE SHELL | tp3.onAddNewPlaylist invoked"
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
        val callbackType = classLoader.loadClass("uq1")
        val kotlinUnitType = classLoader.loadClass("uf5")
        if (!callbackType.isInterface) {
            error("Native GMMP folder callback is not an interface")
        }
        val behavior = dialogType.getDeclaredField("DEFAULT_BEHAVIOR")
            .apply { isAccessible = true }.get(null)
        val parentDialog = dialogType.getDeclaredConstructor(
            Context::class.java,
            behaviorType
        ).apply { isAccessible = true }.newInstance(context, behavior)
        val unitField = kotlinUnitType.declaredFields.singleOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                kotlinUnitType.isAssignableFrom(it.type)
        } ?: error(
            "Native Kotlin Unit singleton field is not structurally unique"
        )
        val unit = unitField.apply { isAccessible = true }.get(null)
            ?: error("Native Kotlin Unit singleton is null")
        val callback = Proxy.newProxyInstance(
            classLoader,
            arrayOf(callbackType)
        ) { _, method, _ ->
            when (method.name) {
                "invoke" -> {
                    onCreationCallback()
                    unit
                }
                "toString" -> "GoneSmart folder refresh"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        }
        val creator = creatorType.getDeclaredMethod(
            "showNewFolderCreator",
            dialogType,
            File::class.java,
            Integer::class.java,
            callbackType
        ).apply { isAccessible = true }
        withLegacyShowScope {
            creator.invoke(null, parentDialog, directory, null, callback)
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
