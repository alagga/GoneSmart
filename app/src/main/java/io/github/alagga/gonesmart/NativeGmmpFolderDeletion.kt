package io.github.alagga.gonesmart

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import java.io.File

/**
 * Original GMMP 4.2.0 playlist AND Files bulk delete: py0.b(Context,List).
 * This adapter never deletes files or changes the native confirmation's
 * handlers. FolderDeletePolicy validates every submitted native model.
 */
internal class NativeGmmpFolderDeletion(
    private val hostClassLoader: ClassLoader
) {
    private data class PendingDialog(
        val canonicalFolderPath: String,
        val nativeGenericFilesLabel: String?
    )
    private val pendingDialog = ThreadLocal<PendingDialog?>()

    fun confirmNativeDeletion(
        context: Context,
        nativeFilePaths: List<File>,
        folder: File
    ): Boolean = runCatching {
        require(nativeFilePaths.isNotEmpty())
        require(nativeFilePaths.all { it.exists() })
        val verifiedFolder = folder.canonicalFile
        require(verifiedFolder.isDirectory)
        val itemClass = hostClassLoader.loadClass("th1")
        val itemCtor = itemClass.getDeclaredConstructor(
            File::class.java, java.lang.Long::class.java
        ).apply { isAccessible = true }
        val nativeFiles = ArrayList<Any>(nativeFilePaths.size)
        nativeFilePaths.forEach { file ->
            nativeFiles += itemCtor.newInstance(file, null)
        }
        val nativeFilesId = context.resources.getIdentifier(
            "files", "string", context.packageName
        )
        val genericFilesLabel = if (nativeFilesId != 0) {
            runCatching { context.getString(nativeFilesId) }.getOrNull()
        } else null

        val original = hostClassLoader.loadClass("py0")
            .getDeclaredMethod(
                "b", Context::class.java, java.util.List::class.java
            ).apply { isAccessible = true }
        pendingDialog.set(
            PendingDialog(verifiedFolder.path, genericFilesLabel)
        )
        try {
            original.invoke(null, context, nativeFiles)
        } finally {
            pendingDialog.remove()
        }
        Log.i(
            "GoneSmartPlaylist",
            "FOLDER DELETE | original GMMP confirmation opened" +
                " | items=" + nativeFiles.size
        )
        true
    }.onFailure {
        Log.e(
            "GoneSmartPlaylist",
            "FOLDER DELETE | native confirmation unavailable; no files changed",
            it
        )
    }.getOrDefault(false)

    /**
     * Called only after native MaterialDialog.show() has finished, during
     * the same synchronous py0.b call. Read the ACTUAL native dialog layout
     * instead of constructing a second approximate confirmation. A generic
     * native files label is replaced with the folder path. If the original
     * message contains additional warnings, preserve those and append the
     * path. Never touch native buttons, listeners or the deletion list.
     */
    fun onNativeDialogShown(dialog: Dialog) {
        val pending = pendingDialog.get() ?: return
        runCatching {
            val materialDialog = hostClassLoader.loadClass(
                "com.afollestad.materialdialogs.MaterialDialog"
            )
            if (!materialDialog.isInstance(dialog)) return
            val decor = dialog.window?.decorView ?: return
            val textViews = ArrayList<Pair<TextView, String>>()
            val queue = ArrayDeque<View>()
            queue.add(decor)
            var visited = 0
            while (queue.isNotEmpty() && ++visited <= 128) {
                val view = queue.removeFirst()
                if (view is TextView && view !is Button &&
                    view.visibility == View.VISIBLE &&
                    !view.text.isNullOrBlank()
                ) {
                    val idName = if (view.id != View.NO_ID && view.id > 0) {
                        runCatching {
                            view.resources.getResourceEntryName(view.id)
                        }.getOrNull()
                    } else null
                    textViews += view to (idName ?: "")
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        queue.add(view.getChildAt(i))
                    }
                }
            }
            if (textViews.any {
                it.first.text?.contains(pending.canonicalFolderPath) == true
            }) return

            val generic = pending.nativeGenericFilesLabel
            val target = textViews.firstOrNull {
                !generic.isNullOrBlank() &&
                    it.first.text.toString().trim() == generic.trim()
            } ?: textViews.firstOrNull {
                it.second.contains("message", ignoreCase = true)
            } ?: textViews.firstOrNull {
                it.second.contains("title", ignoreCase = true)
            }
            if (target == null) {
                Log.w(
                    "GoneSmartPlaylist",
                    "FOLDER DELETE DIALOG | native text layout unknown;" +
                        " original confirmation preserved"
                )
                return
            }
            val before = target.first.text.toString()
            target.first.text = NativeFolderConfirmationText.withFolderPath(
                originalText = before,
                genericFilesLabel = generic,
                canonicalFolderPath = pending.canonicalFolderPath
            )
            Log.i(
                "GoneSmartPlaylist",
                "FOLDER DELETE DIALOG | native path displayed" +
                    " | target=" + target.second +
                    " | genericReplaced=" + (before == generic)
            )
        }.onFailure {
            Log.w(
                "GoneSmartPlaylist",
                "FOLDER DELETE DIALOG | native text unchanged; safe fallback",
                it
            )
        }
    }
}
