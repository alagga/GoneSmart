package io.github.alagga.gonesmart

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * MaterialDialogs' file/playlist creation helpers can expose library-default
 * English labels even while GMMP itself runs in another app-specific locale.
 *
 * Native-first order:
 * 1. Match the visible English phrase against the installed GMMP R.string
 *    table under an English Configuration and reuse that exact resource ID
 *    under the current GMMP locale.
 * 2. Only when the installed APK has no localized equivalent, use the
 *    fully-audited GoneSmartGmmpStrings creation-dialog fallback.
 *
 * This touches only a dialog that contains a verified creation cue. Delete,
 * move and unrelated MaterialDialogs are left unchanged.
 */
internal object NativeGmmpCreationDialogLocalizer {
    private const val TAG = "GoneSmartPlaylist"
    private val nativeMaps = ConcurrentHashMap<String, Map<String, String>>()

    fun localize(dialog: Dialog): Boolean {
        val context = dialog.context
        val locale = context.resources.configuration.locales[0]
        if (locale.language.equals("en", ignoreCase = true)) return false
        val root = dialog.window?.decorView ?: return false
        val views = arrayListOf<TextView>()
        collectTextViews(root, views)
        if (views.isEmpty()) return false

        val visibleStrings = buildList {
            views.forEach { view ->
                view.text?.toString()?.takeUnless(String::isBlank)?.let(::add)
                view.hint?.toString()?.takeUnless(String::isBlank)?.let(::add)
            }
        }
        val creationCue = visibleStrings.any {
            val key = key(it)
            key == "new folder" ||
                key == "create new folder" ||
                key == "create folder" ||
                key == "folder name" ||
                key == "enter folder name" ||
                key == "new playlist" ||
                key == "create new playlist" ||
                key == "create playlist" ||
                key == "playlist name" ||
                key == "enter playlist name"
        }
        if (!creationCue) return false

        val native = nativeExactTranslations(context, locale)
        var changed = 0
        var nativeCount = 0
        var fallbackCount = 0
        views.forEach { view ->
            val text = view.text?.toString()
            if (!text.isNullOrBlank()) {
                val replacement = native[key(text)]
                    ?: GoneSmartGmmpStrings.creationDialog(locale, text)
                if (replacement != null && replacement != text) {
                    if (native.containsKey(key(text))) nativeCount++
                    else fallbackCount++
                    view.text = replacement
                    changed++
                }
            }
            val hint = view.hint?.toString()
            if (!hint.isNullOrBlank()) {
                val replacement = native[key(hint)]
                    ?: GoneSmartGmmpStrings.creationDialog(locale, hint)
                if (replacement != null && replacement != hint) {
                    if (native.containsKey(key(hint))) nativeCount++
                    else fallbackCount++
                    view.hint = replacement
                    changed++
                }
            }
        }
        if (changed > 0) {
            Log.i(
                TAG,
                "CREATION DIALOG I18N | localized=" + changed +
                    " | native=" + nativeCount +
                    " | fallback=" + fallbackCount +
                    " | locale=" + locale.toLanguageTag()
            )
        }
        return changed > 0
    }

    private fun nativeExactTranslations(
        context: Context,
        locale: Locale
    ): Map<String, String> {
        val cacheKey = context.packageName + "|" + locale.toLanguageTag()
        return nativeMaps.getOrPut(cacheKey) {
            runCatching {
                val configuration = Configuration(context.resources.configuration)
                configuration.setLocale(Locale.ENGLISH)
                val englishContext =
                    context.createConfigurationContext(configuration)
                val type = Class.forName(
                    context.packageName + ".R\$string",
                    false,
                    context.classLoader
                )
                val result = linkedMapOf<String, String>()
                type.declaredFields.forEach { field ->
                    if (field.type != Integer.TYPE ||
                        !Modifier.isStatic(field.modifiers)
                    ) return@forEach
                    val id = runCatching {
                        field.isAccessible = true
                        field.getInt(null)
                    }.getOrNull() ?: return@forEach
                    val english = runCatching {
                        englishContext.getString(id)
                    }.getOrNull()?.takeUnless(String::isBlank)
                        ?: return@forEach
                    val current = runCatching {
                        context.getString(id)
                    }.getOrNull()?.takeUnless(String::isBlank)
                        ?: return@forEach
                    if (english.contains('%') || current.contains('%') ||
                        english == current
                    ) return@forEach
                    result.putIfAbsent(key(english), current)
                }
                result
            }.onFailure {
                Log.w(
                    TAG,
                    "CREATION DIALOG I18N | native resource scan unavailable",
                    it
                )
            }.getOrDefault(emptyMap())
        }
    }

    private fun collectTextViews(view: View, out: MutableList<TextView>) {
        if (view is TextView) out += view
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectTextViews(group.getChildAt(index), out)
        }
    }

    private fun key(value: String): String =
        value.trim()
            .replace('…', '.')
            .trimEnd('.', ':')
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
