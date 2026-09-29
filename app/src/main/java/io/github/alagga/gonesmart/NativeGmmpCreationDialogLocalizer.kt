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
    private val inputDiagnostics = WeakHashMap<Dialog, Boolean>()
    private val pendingRevealAlpha = WeakHashMap<Dialog, Float>()
    private val pendingWindowAlpha = WeakHashMap<Dialog, Float>()
    private val postShowRevealGuards =
        WeakHashMap<Dialog, android.view.ViewTreeObserver.OnPreDrawListener>()

    /**
     * Called from the intercepted native MaterialDialog.show() BEFORE GMMP
     * attaches/draws the dialog window. Only creation dialogs are touched.
     * This closes the last first-frame gap where Material could render its
     * static red focus tint before !mainColorAccent arrived.
     */
    fun prepareBeforeShow(dialog: Dialog): Boolean {
        if (!isCreationDialog(dialog)) return false
        val window = dialog.window ?: return false
        val originalWindowAlpha = window.attributes.alpha
        pendingWindowAlpha[dialog] = originalWindowAlpha
        setWindowAlpha(dialog, 0f)
        ensureInputAccent(dialog)
        localize(dialog)
        Log.i(
            TAG,
            "CREATION DIALOG PRE-SHOW | prepared=true" +
                " | accentReady=" + accentColors.containsKey(dialog) +
                " | holdWindow=true"
        )
        return true
    }

    fun finishAfterShow(dialog: Dialog) {
        val originalAlpha = pendingWindowAlpha[dialog] ?: return
        val decor = dialog.window?.decorView ?: return
        setWindowAlpha(dialog, 0f)
        postShowRevealGuards.remove(dialog)?.let { old ->
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnPreDrawListener(old)
            }
        }

        // Device evidence shows the EditText can already report focus while
        // the dialog window itself still has windowFocus=false. Aesthetic
        // performs one more focus-state tint update when window focus arrives.
        // Keep the whole window hidden until that transition has happened,
        // then require one additional focused traversal before revealing.
        var focusedPasses = 0
        var postRevealPasses = 0
        var revealed = false
        lateinit var guard: android.view.ViewTreeObserver.OnPreDrawListener
        guard = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!dialog.isShowing) {
                    postShowRevealGuards.remove(dialog)
                    pendingWindowAlpha.remove(dialog)
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    return true
                }

                accentColors[dialog]?.let {
                    applyInputAccent(decor, it)
                } ?: applyPendingInputAccent(decor)
                localize(dialog)

                if (!revealed) {
                    if (decor.hasWindowFocus()) {
                        focusedPasses++
                    } else {
                        focusedPasses = 0
                    }

                    if (focusedPasses < 2) {
                        // Allow hidden frames to commit. Blocking pre-draw can
                        // itself delay the window-focus handoff we are waiting
                        // for.
                        decor.postInvalidateOnAnimation()
                        return true
                    }

                    revealed = true
                    pendingWindowAlpha.remove(dialog)
                    setWindowAlpha(dialog, originalAlpha)
                    Log.i(
                        TAG,
                        "CREATION DIALOG REVEAL | windowFocus=true" +
                            " | focusedPasses=" + focusedPasses +
                            " | accentReady=" +
                            accentColors.containsKey(dialog) +
                            " | " + inputAccentState(decor)
                    )
                    // Keep correcting a handful of visible traversals too:
                    // IME/window-focus callbacks can still trigger a late
                    // Aesthetic state write immediately after reveal.
                    decor.postInvalidateOnAnimation()
                    return true
                }

                postRevealPasses++
                if (postRevealPasses < 6) {
                    decor.postInvalidateOnAnimation()
                    return true
                }

                if (decor.viewTreeObserver.isAlive) {
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                }
                postShowRevealGuards.remove(dialog)
                Log.i(
                    TAG,
                    "CREATION DIALOG ACCENT LOCK | released" +
                        " | passes=" + postRevealPasses +
                        " | " + inputAccentState(decor)
                )
                return true
            }
        }

        postShowRevealGuards[dialog] = guard
        if (decor.viewTreeObserver.isAlive) {
            decor.viewTreeObserver.addOnPreDrawListener(guard)
            decor.postInvalidateOnAnimation()

            // Accessibility/multi-window edge-case fail-open: creation dialogs
            // should normally gain window focus quickly, but never leave one
            // permanently invisible if the platform withholds it.
            decor.postDelayed({
                if (postShowRevealGuards[dialog] === guard &&
                    !revealed &&
                    dialog.isShowing
                ) {
                    accentColors[dialog]?.let {
                        applyInputAccent(decor, it)
                    } ?: applyPendingInputAccent(decor)
                    revealed = true
                    pendingWindowAlpha.remove(dialog)
                    setWindowAlpha(dialog, originalAlpha)
                    Log.w(
                        TAG,
                        "CREATION DIALOG REVEAL | focus timeout fail-open" +
                            " | windowFocus=" + decor.hasWindowFocus() +
                            " | " + inputAccentState(decor)
                    )
                    decor.postInvalidateOnAnimation()
                }
            }, 750L)
        } else {
            postShowRevealGuards.remove(dialog)
            pendingWindowAlpha.remove(dialog)
            setWindowAlpha(dialog, originalAlpha)
        }
    }

    /**
     * Folder shell uses GMMP's ORIGINAL New Playlist dialog and therefore
     * MUST keep its native Aesthetic colors/focus behavior untouched.
     * Change text only: title -> folder wording, remove the redundant floating
     * label, and keep one folder-name hint in the real EditText.
     */
    fun localizeFolderShellTextWhenReady(dialog: Dialog) {
        localizeFolderShellTextOnly(dialog)
        val decor = dialog.window?.decorView ?: return
        decor.post { localizeFolderShellTextOnly(dialog) }
        decor.postDelayed({ localizeFolderShellTextOnly(dialog) }, 60L)
        decor.postDelayed({ localizeFolderShellTextOnly(dialog) }, 180L)
    }

    fun localizeFolderShellTextOnly(dialog: Dialog): Boolean {
        val root = dialog.window?.decorView ?: return false
        val locale = dialog.context.resources.configuration.locales[0]
        val playlistTitle =
            GoneSmartGmmpStrings.creationDialog(locale, "New Playlist")
        val playlistName =
            GoneSmartGmmpStrings.creationDialog(locale, "Playlist name")
        val folderTitle =
            GoneSmartGmmpStrings.creationDialog(locale, "New Folder")
                ?: "New folder"
        val folderName =
            GoneSmartGmmpStrings.creationDialog(locale, "Folder name")
                ?: "Folder name"

        val playlistTitleKeys = setOfNotNull(
            playlistTitle?.let(::key),
            key("New Playlist")
        )
        val playlistNameKeys = setOfNotNull(
            playlistName?.let(::key),
            key("Playlist name"),
            key("New Playlist Name")
        )

        var changed = 0
        val textViews = arrayListOf<TextView>()
        collectTextViews(root, textViews)
        textViews.forEach { view ->
            val text = view.text?.toString()?.takeUnless(String::isBlank)
            if (text != null && key(text) in playlistTitleKeys) {
                view.text = folderTitle
                changed++
            } else if (
                text != null &&
                key(text) in playlistNameKeys &&
                view !is EditText
            ) {
                view.visibility = View.GONE
                changed++
            }
            val hint = view.hint?.toString()?.takeUnless(String::isBlank)
            if (hint != null && key(hint) in playlistNameKeys) {
                view.hint = folderName
                changed++
            }
        }

        val inputs = arrayListOf<View>()
        collectInputViews(root, inputs)
        val field = inputs.filterIsInstance<EditText>().firstOrNull()
        if (field != null && field.hint?.toString() != folderName) {
            field.hint = folderName
            changed++
        }
        collectInputAncestors(inputs).forEach { view ->
            val hint = reflectiveHint(view)?.takeUnless(String::isBlank)
            if (hint != null && key(hint) in playlistNameKeys) {
                val disabled = invokeBoolean(view, "setHintEnabled", false)
                val cleared = setReflectiveHint(view, null)
                if (disabled || cleared) changed++
            }
        }

        if (changed > 0) {
            Log.i(
                TAG,
                "FOLDER CREATE SHELL | native playlist dialog relabeled" +
                    " | locale=" + locale.toLanguageTag()
            )
        }
        return changed > 0
    }

    fun localizeWhenReady(dialog: Dialog) {
        ensureInputAccent(dialog)
        localize(dialog)
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

        val textViews = arrayListOf<TextView>()
        collectTextViews(root, textViews)
        val inputViews = arrayListOf<View>()
        collectInputViews(root, inputViews)
        if (textViews.isEmpty() && inputViews.isEmpty()) return false
        val inputAncestors = collectInputAncestors(inputViews)

        val visibleStrings = buildList {
            textViews.forEach { view ->
                view.text?.toString()?.takeUnless(String::isBlank)?.let(::add)
                view.hint?.toString()?.takeUnless(String::isBlank)?.let(::add)
            }
            inputViews.forEach { view ->
                inputHint(view)?.takeUnless(String::isBlank)?.let(::add)
            }
            inputAncestors.forEach { view ->
                reflectiveHint(view)?.takeUnless(String::isBlank)?.let(::add)
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

        // The maintainer explicitly does not want MaterialDialogs' extra
        // floating "New Folder Name" / "New Playlist Name" caption. Keep one
        // useful localized placeholder inside the EditText and suppress only
        // the redundant floating-label owner.
        textViews.forEach { view ->
            val text = view.text?.toString()
            if (!text.isNullOrBlank() &&
                GoneSmartGmmpStrings.creationInputLabel(locale, text) != null &&
                view !is EditText
            ) {
                view.visibility = View.GONE
                inputCount++
                changed++
            }
        }
        inputAncestors.forEach { view ->
            val source = reflectiveHint(view)?.takeUnless(String::isBlank)
                ?: return@forEach
            val replacement =
                GoneSmartGmmpStrings.creationInputLabel(locale, source)
                    ?: return@forEach
            val disabled = invokeBoolean(view, "setHintEnabled", false)
            val cleared = setReflectiveHint(view, null)
            if (disabled || cleared) {
                fallbackCount++
                inputCount++
                changed++
            }
            // The parent used to own the hint; keep a single useful hint in
            // the actual field after disabling that parent caption.
            inputViews.filterIsInstance<EditText>().firstOrNull()?.let { field ->
                val current = field.hint?.toString()
                if (current.isNullOrBlank() ||
                    GoneSmartGmmpStrings.creationInputLabel(
                        locale, current
                    ) != null
                ) {
                    field.hint = replacement
                }
            }
        }
        inputViews.filterIsInstance<EditText>().forEach { field ->
            val source = field.hint?.toString()?.takeUnless(String::isBlank)
                ?: return@forEach
            val replacement =
                GoneSmartGmmpStrings.creationInputLabel(locale, source)
                    ?: return@forEach
            if (replacement != source) {
                field.hint = replacement
                fallbackCount++
                inputCount++
                changed++
            }
        }

        // Until Aesthetic's live color arrives, explicitly suppress only the
        // focused line/cursor state. This prevents Android's unrelated red
        // static accent from drawing for one frame without hiding the field.
        if (!accentColors.containsKey(dialog)) {
            applyPendingInputAccent(root)
        }

        if (locale.language.equals("en", ignoreCase = true)) {
            return changed > 0
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

        // Aesthetic/Material can restyle the input after show/focus. Apply
        // the already-resolved live GMMP accent once more AFTER discovering
        // the real floating-label owner, so the newly found target and cursor
        // are corrected in the same pass.
        accentColors[dialog]?.let { applyInputAccent(root, it) }

        if (inputDiagnostics.put(dialog, true) == null) {
            val field = inputViews.filterIsInstance<EditText>().firstOrNull()
            val parents = inputAncestors.take(5)
                .joinToString(">") { it.javaClass.simpleName }
            Log.i(
                TAG,
                "CREATION DIALOG INPUT | field=" +
                    (field?.javaClass?.simpleName ?: "none") +
                    " | parentChain=" + parents
            )
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
        val initial = NativeGmmpAccent.lastObserved()
        if (initial != null) {
            accentColors[dialog] = initial
            applyInputAccent(decor, initial)
            Log.i(
                TAG,
                "CREATION DIALOG ACCENT | cached !mainColorAccent=#" +
                    Integer.toHexString(initial)
            )
        } else {
            pendingRevealAlpha[dialog] = decor.alpha
            decor.alpha = 0f
            decor.postDelayed({
                if (!accentColors.containsKey(dialog)) {
                    applyPendingInputAccent(decor)
                    pendingRevealAlpha.remove(dialog)?.let { alpha ->
                        if (dialog.isShowing) decor.alpha = alpha
                    }
                }
            }, 180L)
        }
        if (decor.viewTreeObserver.isAlive) {
            val firstDrawGuard =
                object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (decor.viewTreeObserver.isAlive) {
                            decor.viewTreeObserver.removeOnPreDrawListener(this)
                        }
                        accentColors[dialog]?.let {
                            applyInputAccent(decor, it)
                        } ?: applyPendingInputAccent(decor)
                        return true
                    }
                }
            decor.viewTreeObserver.addOnPreDrawListener(firstDrawGuard)
        }
        val subscription = NativeGmmpAccent.observe(
            decor,
            onColor = { color ->
                val previous = accentColors.put(dialog, color)
                applyInputAccent(decor, color)
                pendingRevealAlpha.remove(dialog)?.let { alpha ->
                    decor.alpha = alpha
                }
                if (previous != color) {
                    Log.i(
                        TAG,
                        "CREATION DIALOG ACCENT | !mainColorAccent=#" +
                            Integer.toHexString(color)
                    )
                }
            },
            onError = {
                pendingRevealAlpha.remove(dialog)?.let { alpha ->
                    decor.alpha = alpha
                }
                Log.w(
                    TAG,
                    "CREATION DIALOG ACCENT | live GMMP accent unavailable",
                    it
                )
            }
        ) ?: run {
            pendingRevealAlpha.remove(dialog)?.let { alpha ->
                decor.alpha = alpha
            }
            return
        }
        accentSubscriptions[dialog] = subscription
        decor.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    accentSubscriptions.remove(dialog)?.dispose()
                    accentColors.remove(dialog)
                    pendingRevealAlpha.remove(dialog)
                    pendingWindowAlpha.remove(dialog)
                    postShowRevealGuards.remove(dialog)?.let { guard ->
                        if (v.viewTreeObserver.isAlive) {
                            v.viewTreeObserver.removeOnPreDrawListener(guard)
                        }
                    }
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
                val normal = inputNormalColor(view.context, accent)
                view.backgroundTintList = focusedColors(accent, normal)
                tintCursor(view, accent)
                return@forEach
            }
            if (!isTextInputLayout(view)) return@forEach
            val normal = inputNormalColor(view.context, accent)
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
        // Floating input captions are intentionally disabled. Aesthetic's
        // TextInputLayout box APIs still receive the native accent through the
        // input-view loop above.
    }

    private fun applyPendingInputAccent(root: View) {
        val inputs = arrayListOf<View>()
        collectInputViews(root, inputs)
        inputs.forEach { view ->
            if (view is EditText) {
                val normal = inputNormalColor(
                    view.context,
                    android.graphics.Color.TRANSPARENT
                )
                view.backgroundTintList = focusedColors(
                    android.graphics.Color.TRANSPARENT,
                    normal
                )
                tintCursor(view, android.graphics.Color.TRANSPARENT)
                return@forEach
            }
            if (!isTextInputLayout(view)) return@forEach
            val normal = inputNormalColor(
                view.context,
                android.graphics.Color.TRANSPARENT
            )
            invokeInt(
                view,
                "setBoxStrokeColor",
                android.graphics.Color.TRANSPARENT
            )
            invokeColorStateList(
                view,
                "setBoxStrokeColorStateList",
                focusedColors(
                    android.graphics.Color.TRANSPARENT,
                    normal
                )
            )
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

    private fun inputNormalColor(context: Context, fallback: Int): Int =
        resolveThemeColor(
            context,
            android.R.attr.colorControlNormal,
            resolveThemeColor(
                context,
                android.R.attr.textColorSecondary,
                fallback
            )
        )

    private fun inputAccentState(root: View): String {
        val inputs = arrayListOf<View>()
        collectInputViews(root, inputs)
        val field = inputs.filterIsInstance<EditText>().firstOrNull()
            ?: return "field=none"
        val tint = field.backgroundTintList
        val active = tint?.getColorForState(
            field.drawableState,
            tint.defaultColor
        )
        return "fieldFocused=" + field.isFocused +
            " | windowFocused=" + field.hasWindowFocus() +
            " | tint=" +
            (active?.let { "#" + Integer.toHexString(it) } ?: "none")
    }

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

    private fun invokeBoolean(
        view: View,
        methodName: String,
        value: Boolean
    ): Boolean = runCatching {
        val method = view.javaClass.methods.firstOrNull {
            it.name == methodName &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return@runCatching false
        method.invoke(view, value)
        true
    }.getOrDefault(false)

    private fun isCreationDialog(dialog: Dialog): Boolean {
        val root = dialog.window?.decorView ?: return false
        val locale = dialog.context.resources.configuration.locales[0]
        val textViews = arrayListOf<TextView>()
        collectTextViews(root, textViews)
        val inputs = arrayListOf<View>()
        collectInputViews(root, inputs)
        if (inputs.none { it is EditText }) return false

        val strings = buildList {
            textViews.forEach { view ->
                view.text?.toString()?.takeUnless(String::isBlank)?.let(::add)
                view.hint?.toString()?.takeUnless(String::isBlank)?.let(::add)
            }
            inputs.forEach { view ->
                inputHint(view)?.takeUnless(String::isBlank)?.let(::add)
            }
            collectInputAncestors(inputs).forEach { view ->
                reflectiveHint(view)?.takeUnless(String::isBlank)?.let(::add)
            }
        }
        return strings.any {
            GoneSmartGmmpStrings.creationDialog(locale, it) != null ||
                GoneSmartGmmpStrings.creationInputLabel(locale, it) != null
        }
    }

    private fun setWindowAlpha(dialog: Dialog, alpha: Float) {
        val window = dialog.window ?: return
        val attrs = window.attributes
        if (kotlin.math.abs(attrs.alpha - alpha) < 0.001f) return
        attrs.alpha = alpha
        window.attributes = attrs
    }

    private fun collectInputViews(view: View, out: MutableList<View>) {
        if (view is EditText || isTextInputLayout(view)) out += view
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectInputViews(group.getChildAt(index), out)
        }
    }

    private fun collectInputAncestors(inputs: List<View>): List<View> {
        val result = linkedSetOf<View>()
        inputs.filterIsInstance<EditText>().forEach { field ->
            var parent = field.parent
            var depth = 0
            while (parent is View && depth < 6) {
                result += parent
                parent = parent.parent
                depth++
            }
        }
        return result.toList()
    }

    private fun reflectiveHint(view: View): String? =
        runCatching {
            view.javaClass.methods.firstOrNull {
                it.name == "getHint" && it.parameterCount == 0
            }?.invoke(view)?.toString()
        }.getOrNull()

    private fun setReflectiveHint(view: View, value: CharSequence?): Boolean =
        runCatching {
            val method = view.javaClass.methods.firstOrNull {
                it.name == "setHint" &&
                    it.parameterCount == 1 &&
                    CharSequence::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    )
            } ?: return@runCatching false
            method.invoke(view, value)
            true
        }.getOrDefault(false)

    private fun tintCursor(view: EditText, accent: Int) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        runCatching {
            val cursor = view.textCursorDrawable?.mutate()
                ?: return@runCatching
            cursor.setTint(accent)
            view.textCursorDrawable = cursor
        }.onFailure {
            Log.w(TAG, "CREATION DIALOG ACCENT | cursor tint unavailable", it)
        }
    }

    private fun isTextInputLayout(view: View): Boolean =
        view.javaClass.simpleName.endsWith("TextInputLayout")

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
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.ENGLISH)
                }
            val englishContext =
                context.createConfigurationContext(configuration)
            val result = linkedMapOf<String, String>()
            // R8 does not retain a loadable gonemad.gmmp.R$string class in
            // this installed GMMP build. Native-first lookup therefore uses
            // Android Resources directly by the audited host resource names,
            // exactly as AGENTS.md requires elsewhere in GoneSmart.
            val names = listOf(
                "files_new_folder",
                "folder",
                "folders",
                "playlist",
                "playlists",
                "new_playlist",
                "create",
                "cancel",
                "ok",
                "add"
            )
            names.forEach { name ->
                val id = context.resources.getIdentifier(
                    name, "string", context.packageName
                )
                if (id == 0) return@forEach
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
