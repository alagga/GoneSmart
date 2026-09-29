package io.github.alagga.gonesmart

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Proxy

/**
 * The supplied GMMP 4.2.0 APK already ships MaterialDialogs'
 * DialogFileChooserExtKt.showNewFolderCreator(MaterialDialog, File,
 * Integer, uq1). Reuse the ACTUAL native prompt, validation and mkdir
 * callback rather than imitating its visuals or writing a second dialog.
 */
internal class NativeGmmpFolderCreator(
    private val classLoader: ClassLoader
) {
    fun show(
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
            Context::class.java, behaviorType
        ).apply { isAccessible = true }.newInstance(context, behavior)
        val unit = kotlinUnitType.getDeclaredField("a")
            .apply { isAccessible = true }.get(null)
        val callback = Proxy.newProxyInstance(
            classLoader, arrayOf(callbackType)
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
        creator.invoke(null, parentDialog, directory, null, callback)
        (parentDialog as? android.app.Dialog)?.let {
            NativeGmmpCreationDialogLocalizer.localizeWhenReady(it)
        }
        true
    }.onFailure {
        Log.e(
            "GoneSmartPlaylist",
            "FOLDER CREATE | original GMMP dialog unavailable",
            it
        )
    }.getOrDefault(false)
}
