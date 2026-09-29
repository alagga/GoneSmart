package io.github.alagga.gonesmart

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Localizes only genuinely English MaterialDialogs creation chrome that GMMP's
 * current app locale did not localize itself.
 *
 * The floating "New Folder Name" / "New Playlist Name" label is owned by
 * Material TextInputLayout rather than the EditText text/hint on the tested
 * runtime, so it has a dedicated exact-label path. This avoids rewriting the
 * already-correct dialog title/buttons just to reach that small input label.
 *
 * Input focus chrome also follows GMMP/Aesthetic's live !mainColorAccent.
 */
internal object NativeGmmpCreationDialogLocalizer {
    private const val TAG = "GoneSmartPlaylist"
    private val nativeMaps = ConcurrentHashMap<String, Map<String, String>>()
    private val accentSubscriptions =
        WeakHashMap<Dialog, NativeGmmpAccent.Subscription>()
    private val accentColors = WeakHashMap<Dialog, Int>()
    private val exactInputLabelViews = WeakHashMap<TextView, Boolean>()

    fun localizeWhenReady(dialog: Dialog) {
        localize(dialog)
        ensureInputAccent(dialog)
        val decor = dialog.window?.decorView ?: return
        decor.post { localize(dialog) }
        decor.postDelayed({ localize(dialog) }, 60L)
        decor.postDelayed({ localize(dialog) }, 180L)
    }

    fun localize(dialog: Dialog): Boolean {
        val context = dialog.context
        val locale = context.resources.configuration.locales[0]
        val root = dialog.window?.decorView ?: return false
        accentColors[dialog]?.let { applyInputAccent(root, it) }
        if (locale.language.equals("en", ignoreCase = true)) return false

        val textViews = arrayListOf<TextView>()
        collectTextViews(root, textViews)
        val inputViews = arrayListOf<View>()
        collectInputViews(root, inputViews)
        if (textViews.isEmpty() && inputViews.isEmpty()) return false

        val visibleStrings = buildList {
            textViews.forEach { view ->
                view.text?.toString()?.takeUnless(String::isBlank)?.let(::add)
                view.hint?.toString()?.takeUnless(String::isBlank)?.let(::add)
            }
            inputViews.forEach { view ->
                inputHint(view)?.takeUnless(String::isBlank)?.let(::add)
            }
        }
        val creationCue = visibleStrings.any {
            GoneSmartGmmpStrings.creationDialog(locale, it) != null ||
                GoneSmartGmmpStrings.creationInputLabel(locale, it) != null
        }
        if (!creationCue) return false

        val native = nativeExactTranslations(context, locale)
        var changed = 0
        var nativeCount = 0
        var fallbackCount = 0
        var inputCount = 0

        // MaterialDialogs 3.x can expose its floating label either as
        // TextInputLayout.hint OR as a rendered TextView. Match the exact
        // library literal first, regardless of which widget implementation
        // produced it. This cannot touch the already-correct large
        // "New Folder"/"New Playlist" title because the literal differs.
        textViews.forEach { view ->
            val text = view.text?.toString()
            if (!text.isNullOrBlank()) {
                val inputReplacement =
                    GoneSmartGmmpStrings.creationInputLabel(locale, text)
                if (inputReplacement != null &&
                    inputReplacement != text
                ) {
                    view.text = inputReplacement
                    exactInputLabelViews[view] = true
                    fallbackCount++
                    inputCount++
                    changed++
                }
            }
            val hint = view.hint?.toString()
            if (!hint.isNullOrBlank()) {
                val inputReplacement =
                    GoneSmartGmmpStrings.creationInputLabel(locale, hint)
                if (inputReplacement != null &&
                    inputReplacement != hint
                ) {
                    view.hint = inputReplacement
                    exactInputLabelViews[view] = true
                    fallbackCount++
                    inputCount++
                    changed++
                }
            }
        }

        // Keep the pre-existing native-first translation path for ordinary
        // dialog text/hints. "New Folder Name" is intentionally NOT part of
        // GoneSmartGmmpStrings.creationDialog.
        textViews.forEach { view ->
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

        // Exact small floating input-label path. It also covers EditText as a
        // fallback in case a MaterialDialogs version stores the same literal
        // directly on the field rather than TextInputLayout.
        inputViews.forEach { view ->
            val source = inputHint(view)?.takeUnless(String::isBlank)
                ?: return@forEach
            val replacement = native[key(source)]
                ?: GoneSmartGmmpStrings.creationInputLabel(locale, source)
            if (replacement != null && replacement != source &&
                setInputHint(view, replacement)
            ) {
                if (native.containsKey(key(source))) nativeCount++
                else fallbackCount++
                inputCount++
                changed++
            }
        }

        if (changed > 0) {
            Log.i(
                TAG,
                "CREATION DIALOG I18N | localized=" + changed +
                    " | inputLabels=" + inputCount +
                    " | native=" + nativeCount +
                    " | fallback=" + fallbackCount +
                    " | locale=" + locale.toLanguageTag()
            )
        }
        return changed > 0
    }

    private fun ensureInputAccent(dialog: Dialog) {
        if (accentSubscriptions.containsKey(dialog)) return
        val decor = dialog.window?.decorView ?: return
        val subscription = NativeGmmpAccent.observe(
            decor,
            onColor = { color ->
                if (!dialog.isShowing) return@observe
                val previous = accentColors.put(dialog, color)
                applyInputAccent(decor, color)
                if (previous != color) {
                    Log.i(
                        TAG,
                        "CREATION DIALOG ACCENT | !mainColorAccent=#" +
                            Integer.toHexString(color)
                    )
                }
            },
            onError = {
                Log.w(
                    TAG,
                    "CREATION DIALOG ACCENT | live GMMP accent unavailable",
                    it
                )
            }
        ) ?: return
        accentSubscriptions[dialog] = subscription
        decor.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    accentSubscriptions.remove(dialog)?.dispose()
                    accentColors.remove(dialog)
                    v.removeOnAttachStateChangeListener(this)
                }
            }
        )
    }

    private fun applyInputAccent(root: View, accent: Int) {
        val inputs = arrayListOf<View>()
        collectInputViews(root, inputs)
        inputs.forEach { view ->
            if (view is EditText) {
                val normal = view.backgroundTintList?.defaultColor
                    ?: resolveThemeColor(
                        view.context,
                        android.R.attr.textColorSecondary,
                        accent
                    )
                view.backgroundTintList = focusedColors(accent, normal)
                return@forEach
            }
            if (!isTextInputLayout(view)) return@forEach
            val normal = resolveThemeColor(
                view.context,
                android.R.attr.textColorSecondary,
                accent
            )
            invokeColorStateList(
                view,
                "setHintTextColor",
                focusedColors(accent, normal)
            )
            invokeInt(view, "setBoxStrokeColor", accent)
            invokeColorStateList(
                view,
                "setBoxStrokeColorStateList",
                focusedColors(accent, normal)
            )
        }
        // If MaterialDialogs rendered the floating label as its own TextView
        // instead of exposing TextInputLayout.setHintTextColor, color the
        // exact label view that we just identified. No other dialog text is
        // recolored.
        val labels = arrayListOf<TextView>()
        collectTextViews(root, labels)
        labels.forEach { label ->
            if (exactInputLabelViews.containsKey(label)) {
                label.setTextColor(accent)
            }
        }
    }

    private fun focusedColors(accent: Int, normal: Int): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(accent, normal)
        )

    private fun resolveThemeColor(
        context: Context,
        attr: Int,
        fallback: Int
    ): Int {
        val typed = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            typed.getColorStateList(0)?.defaultColor
                ?: typed.getColor(0, fallback)
        } finally {
            typed.recycle()
        }
    }

    private fun invokeColorStateList(
        view: View,
        methodName: String,
        value: ColorStateList
    ) {
        runCatching {
            view.javaClass.methods.firstOrNull {
                it.name == methodName &&
                    it.parameterCount == 1 &&
                    ColorStateList::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    )
            }?.invoke(view, value)
        }
    }

    private fun invokeInt(view: View, methodName: String, value: Int) {
        runCatching {
            view.javaClass.methods.firstOrNull {
                it.name == methodName &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(view, value)
        }
    }

    private fun collectInputViews(view: View, out: MutableList<View>) {
        if (view is EditText || isTextInputLayout(view)) out += view
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectInputViews(group.getChildAt(index), out)
        }
    }

    private fun isTextInputLayout(view: View): Boolean =
        view.javaClass.name.endsWith(".TextInputLayout") ||
            view.javaClass.simpleName == "TextInputLayout"

    private fun inputHint(view: View): String? {
        if (view is EditText) return view.hint?.toString()
        if (!isTextInputLayout(view)) return null
        return runCatching {
            view.javaClass.methods.firstOrNull {
                it.name == "getHint" && it.parameterCount == 0
            }?.invoke(view)?.toString()
        }.getOrNull()
    }

    private fun setInputHint(view: View, value: CharSequence): Boolean {
        if (view is EditText) {
            view.hint = value
            return true
        }
        if (!isTextInputLayout(view)) return false
        return runCatching {
            val method = view.javaClass.methods.firstOrNull {
                it.name == "setHint" && it.parameterCount == 1 &&
                    CharSequence::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    )
            } ?: return@runCatching false
            method.invoke(view, value)
            true
        }.getOrDefault(false)
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
