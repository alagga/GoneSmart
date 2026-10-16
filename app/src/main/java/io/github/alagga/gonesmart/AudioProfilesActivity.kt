package io.github.alagga.gonesmart

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class AudioProfilesActivity : AppCompatActivity() {

    companion object {
        private const val STATE_ROUTE = "route"
        private const val STATE_SECTION = "section"

        private const val COLOR_BG = 0xFF151419.toInt()
        private const val COLOR_SURFACE = 0xFF1E1D23.toInt()
        private const val COLOR_SURFACE_2 = 0xFF25242B.toInt()
        private const val COLOR_TEXT = 0xFFF4F2F7.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFFBEBAC5.toInt()
        private const val COLOR_MUTED = 0xFF77737E.toInt()
        private const val COLOR_ACCENT = 0xFFA39AFF.toInt()
        private const val COLOR_ACCENT_DARK = 0xFF34305F.toInt()
        private const val COLOR_RED = 0xFFFF5C68.toInt()
    }

    private lateinit var repository: GoneSmartAudioProfileRepository
    private lateinit var contentHost: FrameLayout
    private lateinit var routeButton: MaterialButton
    private lateinit var equalizerTab: MaterialButton
    private lateinit var effectsTab: MaterialButton

    private var equalizerPresetButton: MaterialButton? = null
    private var routeObserver: AudioRouteObserver? = null
    private var selectedRouteId = GoneSmartAudioProfiles.LOCAL_ROUTE_ID
    private var section = AudioProfileSection.EQUALIZER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG

        repository = GoneSmartAudioProfileRepository(this)
        repository.synchronizeWithFramework()

        selectedRouteId = savedInstanceState?.getString(STATE_ROUTE)
            ?: repository.activeRouteId()
        section = savedInstanceState?.getString(STATE_SECTION)
            ?.let { runCatching { AudioProfileSection.valueOf(it) }.getOrNull() }
            ?: AudioProfileSection.EQUALIZER

        setContentView(buildRoot())
        refreshAll()
        startCompanionRouteObserver()
    }

    override fun onResume() {
        super.onResume()
        repository.synchronizeWithFramework()
        if (repository.routes().none { it.id == selectedRouteId }) {
            selectedRouteId = repository.activeRouteId()
        }
        refreshAll()
    }

    override fun onDestroy() {
        routeObserver?.stop()
        routeObserver = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ROUTE, selectedRouteId)
        outState.putString(STATE_SECTION, section.name)
        super.onSaveInstanceState(outState)
    }

    private fun startCompanionRouteObserver() {
        if (routeObserver != null) return
        routeObserver = AudioRouteObserver(this) { observed ->
            repository.recordObservedRoute(observed)
            selectedRouteId = if (observed.bluetooth) {
                GoneSmartAudioProfiles.bluetoothRouteId(0, null, observed.label)
            } else {
                GoneSmartAudioProfiles.LOCAL_ROUTE_ID
            }
            runOnUiThread { refreshAll() }
        }.also { it.start() }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(22), dp(10))
        }
        val back = MaterialButton(this).apply {
            text = "‹"
            textSize = 34f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(COLOR_TEXT)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(54), dp(54)))
        header.addView(textView("Audio profiles", 28f, COLOR_TEXT, true).apply {
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val selectorArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), dp(12))
        }
        selectorArea.addView(textView("OUTPUT", 13f, COLOR_ACCENT, true).apply {
            letterSpacing = 0.08f
            setPadding(dp(4), 0, 0, dp(8))
        })
        routeButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 16f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(COLOR_TEXT)
            strokeColor = ColorStateList.valueOf(0xFF5A5662.toInt())
            strokeWidth = dp(1)
            cornerRadius = dp(20)
            backgroundTintList = ColorStateList.valueOf(COLOR_SURFACE)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showRouteChooser() }
        }
        selectorArea.addView(routeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        selectorArea.addView(textView(
            "Equalizer is overridden as one complete profile. Effects are overridden per native GMMP card, so an on/off switch and its slider(s) stay together.",
            13f,
            COLOR_TEXT_SECONDARY
        ).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        root.addView(selectorArea)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(4), dp(24), dp(12))
        }
        equalizerTab = tabButton("Equalizer") {
            if (section != AudioProfileSection.EQUALIZER) {
                section = AudioProfileSection.EQUALIZER
                refreshAll()
            }
        }
        effectsTab = tabButton("Effects") {
            if (section != AudioProfileSection.EFFECTS) {
                section = AudioProfileSection.EFFECTS
                refreshAll()
            }
        }
        tabs.addView(equalizerTab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        tabs.addView(effectsTab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(tabs)

        contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(contentHost)
        return root
    }

    private fun refreshAll() {
        if (!::contentHost.isInitialized) return
        repository.synchronizeWithFramework()
        val routes = repository.routes()
        val selected = routes.firstOrNull { it.id == selectedRouteId }
            ?: routes.firstOrNull { it.active }
            ?: routes.first()
        selectedRouteId = selected.id

        routeButton.text = buildString {
            append(if (selected.bluetooth) "Bluetooth • " else "Device • ")
            append(selected.label)
            if (selected.active) append("  •  Active")
            append("  ▾")
        }
        styleTab(equalizerTab, section == AudioProfileSection.EQUALIZER)
        styleTab(effectsTab, section == AudioProfileSection.EFFECTS)
        renderContent(selected)
    }

    private fun renderContent(route: AudioRouteProfile) {
        equalizerPresetButton = null
        contentHost.removeAllViews()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(2), dp(24), dp(36))
        }

        val overrideCount = repository.overrideCount(route.id)
        body.addView(infoCard(
            if (overrideCount == 0) "Using current GMMP settings" else "$overrideCount custom ${if (overrideCount == 1) "section" else "sections"}",
            if (overrideCount == 0) {
                "Inherited controls show the GMMP values GoneSmart has actually observed. Stored device-specific values stay hidden while their Override is off."
            } else {
                "Everything without an active Override follows GMMP's current settings. Dormant custom values never replace the inherited preview."
            }
        ))
        body.addView(verticalGap(14))

        when (section) {
            AudioProfileSection.EQUALIZER -> {
                body.addView(groupCard(route.id, GoneSmartAudioProfiles.equalizerGroup))
                body.addView(verticalGap(10))
                body.addView(infoCard(
                    "Preset behavior",
                    "Choosing a native GMMP preset loads its observed PRE and all ten band values. Moving PRE or any band immediately changes the label to Custom."
                ))
            }

            AudioProfileSection.EFFECTS -> {
                GoneSmartAudioProfiles.effectGroups.forEachIndexed { index, group ->
                    body.addView(groupCard(route.id, group))
                    if (index != GoneSmartAudioProfiles.effectGroups.lastIndex) body.addView(verticalGap(10))
                }
            }
        }

        body.addView(verticalGap(18))
        body.addView(resetButton(route))
        body.addView(verticalGap(12))
        body.addView(textView(
            "The connected Bluetooth output is detected both inside GMMP and directly from this screen. Previously seen outputs remain selectable when disconnected.",
            12f,
            COLOR_MUTED
        ).apply { setPadding(dp(4), 0, dp(4), 0) })

        contentHost.addView(ScrollView(this).apply {
            clipToPadding = false
            addView(body)
        })
    }

    private fun groupCard(routeId: String, group: AudioProfileGroup): View {
        val overridden = repository.isGroupOverridden(routeId, group.id)
        val observedCount = repository.nativeObservedCount(group)
        val card = card()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(textView(group.title, 17f, COLOR_TEXT, true))
        labels.addView(textView(group.subtitle, 12.5f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(3), dp(8), 0)
        })
        titleRow.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val overrideSwitch = SwitchMaterial(this).apply {
            text = "Override"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            isChecked = overridden
            trackTintList = switchTrackTint()
            thumbTintList = switchThumbTint()
            setOnCheckedChangeListener { button, enabled ->
                val saved = repository.setGroupOverride(routeId, group.id, enabled)
                if (!saved && enabled) {
                    button.setOnCheckedChangeListener(null)
                    button.isChecked = false
                    refreshAll()
                    showNativeValuesMissingDialog(group)
                } else {
                    refreshAll()
                }
            }
        }
        titleRow.addView(overrideSwitch)
        content.addView(titleRow)

        val inheritedStatus = if (observedCount == group.fields.size) {
            "GMMP current • inherited"
        } else {
            "GMMP current • $observedCount/${group.fields.size} values observed"
        }
        content.addView(textView(
            if (overridden) "Custom for this output" else inheritedStatus,
            12f,
            if (overridden) COLOR_ACCENT else COLOR_MUTED
        ).apply { setPadding(0, dp(10), 0, dp(8)) })

        group.fields.forEachIndexed { index, field ->
            if (index > 0) content.addView(divider())
            content.addView(fieldControl(routeId, group, field, overridden))
        }

        card.addView(content)
        return card
    }

    private fun fieldControl(
        routeId: String,
        group: AudioProfileGroup,
        field: AudioProfileField,
        enabled: Boolean
    ): View {
        val native = repository.nativeValue(field.id)
        val custom = if (enabled) repository.value(routeId, field.id) else null
        val initial = custom ?: native ?: field.suggestedDefault
        val previewOnly = !enabled && native == null

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = if (enabled) 1f else 0.62f
            setPadding(0, dp(10), 0, dp(10))

            when (field.valueType) {
                AudioProfileValueType.BOOLEAN -> {
                    val row = LinearLayout(this@AudioProfilesActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val text = LinearLayout(this@AudioProfilesActivity).apply { orientation = LinearLayout.VERTICAL }
                    text.addView(textView(field.title, 15f, COLOR_TEXT, true))
                    text.addView(textView(field.subtitle, 12f, COLOR_TEXT_SECONDARY).apply {
                        setPadding(0, dp(2), dp(8), 0)
                    })
                    row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(SwitchMaterial(this@AudioProfilesActivity).apply {
                        isChecked = initial == "true"
                        isEnabled = enabled
                        trackTintList = switchTrackTint()
                        thumbTintList = switchThumbTint()
                        setOnCheckedChangeListener { _, checked ->
                            if (isEnabled) repository.setValue(routeId, field.id, checked.toString())
                        }
                    })
                    addView(row)
                }

                AudioProfileValueType.NUMBER -> {
                    val valueText = textView(displayValue(field, initial), 13f, COLOR_ACCENT, true)
                    val heading = LinearLayout(this@AudioProfilesActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val left = LinearLayout(this@AudioProfilesActivity).apply { orientation = LinearLayout.VERTICAL }
                    left.addView(textView(field.title, 15f, COLOR_TEXT, true))
                    left.addView(textView(field.subtitle, 12f, COLOR_TEXT_SECONDARY).apply {
                        setPadding(0, dp(2), dp(8), 0)
                    })
                    heading.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    heading.addView(valueText)
                    addView(heading)

                    val minimum = field.minimum
                    val maximum = field.maximum
                    if (minimum != null && maximum != null) {
                        addView(Slider(this@AudioProfilesActivity).apply {
                            valueFrom = minimum.toFloat()
                            valueTo = maximum.toFloat()
                            field.step?.takeIf { it > 0.0 }?.let { stepSize = it.toFloat() }
                            value = initial.toFloatOrNull()?.coerceIn(valueFrom, valueTo)
                                ?: field.suggestedDefault.toFloatOrNull()?.coerceIn(valueFrom, valueTo)
                                ?: valueFrom
                            isEnabled = enabled
                            trackActiveTintList = ColorStateList.valueOf(COLOR_ACCENT)
                            trackInactiveTintList = ColorStateList.valueOf(0xFF494650.toInt())
                            thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
                            haloTintList = ColorStateList.valueOf(withAlpha(COLOR_ACCENT, 0.20f))
                            addOnChangeListener { _, sliderValue, fromUser ->
                                if (!fromUser || !isEnabled) return@addOnChangeListener
                                val normalized = field.normalize(sliderValue.toString()) ?: return@addOnChangeListener
                                repository.setValue(routeId, field.id, normalized)
                                if (group.id == GoneSmartAudioProfiles.equalizerGroup.id &&
                                    GoneSmartAudioProfiles.isEqualizerShapeField(field.id)
                                ) {
                                    markEqualizerCustom(routeId)
                                }
                                valueText.text = displayValue(field, normalized)
                            }
                        })
                    } else {
                        addView(EditText(this@AudioProfilesActivity).apply {
                            setText(initial)
                            setTextColor(COLOR_TEXT)
                            setHintTextColor(COLOR_MUTED)
                            textSize = 15f
                            inputType = InputType.TYPE_CLASS_NUMBER or
                                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                InputType.TYPE_NUMBER_FLAG_SIGNED
                            backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
                            isEnabled = enabled
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            setOnFocusChangeListener { _, hasFocus ->
                                if (!hasFocus && isEnabled) {
                                    val normalized = field.normalize(text.toString())
                                    if (normalized != null) {
                                        setText(normalized)
                                        repository.setValue(routeId, field.id, normalized)
                                        if (group.id == GoneSmartAudioProfiles.equalizerGroup.id &&
                                            GoneSmartAudioProfiles.isEqualizerShapeField(field.id)
                                        ) {
                                            markEqualizerCustom(routeId)
                                        }
                                        valueText.text = displayValue(field, normalized)
                                    }
                                }
                            }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
                    }
                }

                AudioProfileValueType.CHOICE -> {
                    addView(textView(field.title, 15f, COLOR_TEXT, true))
                    addView(textView(field.subtitle, 12f, COLOR_TEXT_SECONDARY).apply {
                        setPadding(0, dp(2), 0, dp(8))
                    })
                    val button = MaterialButton(
                        this@AudioProfilesActivity,
                        null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = "$initial  ▾"
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setTextColor(COLOR_TEXT)
                        backgroundTintList = ColorStateList.valueOf(COLOR_SURFACE_2)
                        strokeColor = ColorStateList.valueOf(0xFF5A5662.toInt())
                        strokeWidth = dp(1)
                        cornerRadius = dp(16)
                        insetTop = 0
                        insetBottom = 0
                        isEnabled = enabled
                        setOnClickListener { showChoiceDialog(routeId, field) }
                    }
                    if (field.id == "eq.preset") equalizerPresetButton = button
                    addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
                }
            }

            if (previewOnly) {
                addView(textView(
                    "GMMP value not observed yet • preview only",
                    11.5f,
                    COLOR_MUTED
                ).apply { setPadding(0, dp(4), 0, 0) })
            }
        }
    }

    private fun markEqualizerCustom(routeId: String) {
        val current = repository.value(routeId, "eq.preset")
        if (current != GoneSmartAudioProfiles.CUSTOM_PRESET) {
            repository.setValue(routeId, "eq.preset", GoneSmartAudioProfiles.CUSTOM_PRESET)
        }
        equalizerPresetButton?.text = "${GoneSmartAudioProfiles.CUSTOM_PRESET}  ▾"
    }

    private fun showChoiceDialog(routeId: String, field: AudioProfileField) {
        val current = repository.value(routeId, field.id)
            ?: repository.nativeValue(field.id)
            ?: field.suggestedDefault
        val items = (field.choices + listOf(current))
            .filter { it.isNotBlank() && it != GoneSmartAudioProfiles.CUSTOM_PRESET }
            .distinct()
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(field.title)
            .setItems(items) { _, which ->
                val selected = items[which]
                if (field.id == "eq.preset") {
                    if (repository.applyEqualizerPreset(routeId, selected)) {
                        refreshAll()
                    } else {
                        showPresetNotObservedDialog(selected)
                    }
                } else {
                    repository.setValue(routeId, field.id, selected)
                    refreshAll()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPresetNotObservedDialog(preset: String) {
        AlertDialog.Builder(this)
            .setTitle("Preset values not observed yet")
            .setMessage(
                "GoneSmart knows the preset name '$preset', but it has not yet seen GMMP load that preset's PRE and band values. " +
                    "The current sliders are left unchanged instead of guessing the curve."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showNativeValuesMissingDialog(group: AudioProfileGroup) {
        AlertDialog.Builder(this)
            .setTitle("GMMP values not ready yet")
            .setMessage(
                "GoneSmart has not yet observed every current GMMP value needed for ${group.title}. " +
                    "The override was not enabled, so no guessed defaults can replace your real GMMP settings."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun displayValue(field: AudioProfileField, raw: String): String = when (field.valueType) {
        AudioProfileValueType.BOOLEAN -> if (raw == "true") "On" else "Off"
        AudioProfileValueType.NUMBER -> raw + (field.unit?.let { " $it" } ?: "")
        AudioProfileValueType.CHOICE -> raw
    }

    private fun showRouteChooser() {
        repository.synchronizeWithFramework()
        val routes = repository.routes()
        val labels = routes.map { route ->
            buildString {
                append(if (route.bluetooth) "Bluetooth • " else "Device • ")
                append(route.label)
                if (route.active) append("  •  Active")
                val count = repository.overrideCount(route.id)
                if (count > 0) append("  •  $count custom")
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Audio output")
            .setItems(labels) { _, which ->
                selectedRouteId = routes[which].id
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetButton(route: AudioRouteProfile): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Reset this profile to current GMMP settings"
            textSize = 14f
            setTextColor(COLOR_RED)
            strokeColor = ColorStateList.valueOf(withAlpha(COLOR_RED, 0.65f))
            strokeWidth = dp(1)
            cornerRadius = dp(20)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            isEnabled = repository.overrideCount(route.id) > 0
            alpha = if (isEnabled) 1f else 0.45f
            setOnClickListener {
                AlertDialog.Builder(this@AudioProfilesActivity)
                    .setTitle("Reset ${route.label}?")
                    .setMessage("All device-specific audio overrides for this output will be removed. GMMP's own settings are not changed.")
                    .setPositiveButton("Reset") { _, _ ->
                        repository.clearRouteOverrides(route.id)
                        refreshAll()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        }

    private fun tabButton(label: String, onClick: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        textSize = 15f
        cornerRadius = dp(18)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { onClick() }
    }

    private fun styleTab(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (selected) COLOR_ACCENT_DARK else COLOR_SURFACE)
        button.setTextColor(if (selected) COLOR_TEXT else COLOR_TEXT_SECONDARY)
        button.strokeWidth = if (selected) 0 else dp(1)
        button.strokeColor = ColorStateList.valueOf(0xFF4A4751.toInt())
    }

    private fun infoCard(title: String, body: String): View {
        val card = card()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        content.addView(textView(title, 16f, COLOR_TEXT, true))
        content.addView(textView(body, 13f, COLOR_TEXT_SECONDARY).apply { setPadding(0, dp(5), 0, 0) })
        card.addView(content)
        return card
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(COLOR_SURFACE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFF34313A.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    private fun textView(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun switchTrackTint(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(COLOR_ACCENT_DARK, 0xFF46434D.toInt())
    )

    private fun switchThumbTint(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(COLOR_ACCENT, 0xFFC8C5CE.toInt())
    )

    private fun verticalGap(heightDp: Int): View = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun withAlpha(color: Int, fraction: Float): Int = Color.argb(
        (255 * fraction.coerceIn(0f, 1f)).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
