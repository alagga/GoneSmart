package io.github.alagga.gonesmart

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MenuItem
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
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
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
        private const val COLOR_ACTIVE = 0xFF55D187.toInt()
        private const val COLOR_RED = 0xFFFF5C68.toInt()
    }

    private lateinit var repository: GoneSmartAudioProfileRepository
    private lateinit var contentHost: FrameLayout
    private lateinit var routeButton: MaterialButton
    private lateinit var equalizerTab: MaterialButton
    private lateinit var effectsTab: MaterialButton

    private var equalizerPresetButton: MaterialButton? = null
    private var routeObserver: AudioRouteObserver? = null
    private var contentScrollView: ScrollView? = null
    private var renderedContentKey: String? = null
    private var standardSetupHintShown = false
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

        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(COLOR_BG)
            title = "Audio profiles"
            setTitleTextColor(COLOR_TEXT)
            navigationIcon = AppCompatResources.getDrawable(this@AudioProfilesActivity, R.drawable.ic_arrow_back_24)
            navigationIcon?.setTint(COLOR_TEXT)
            navigationContentDescription = "Back"
            setNavigationOnClickListener { finish() }
            val help = menu.add("Help").apply {
                icon = AppCompatResources.getDrawable(this@AudioProfilesActivity, R.drawable.ic_info_outline_24)
                icon?.setTint(COLOR_TEXT)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            setOnMenuItemClickListener { item ->
                if (item === help) {
                    showHelpDialog()
                    true
                } else {
                    false
                }
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        val selectorArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(12))
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

        routeButton.text = routeLabel(selected, includeCustomCount = false, includeChevron = true)
        styleTab(equalizerTab, section == AudioProfileSection.EQUALIZER)
        styleTab(effectsTab, section == AudioProfileSection.EFFECTS)
        renderContent(selected)
        maybeShowStandardSetupHint(selected)
    }

    private fun renderContent(route: AudioRouteProfile) {
        val contentKey = "${route.id}:${section.name}"
        val restoreScrollY = if (renderedContentKey == contentKey) contentScrollView?.scrollY ?: 0 else 0
        equalizerPresetButton = null
        contentHost.removeAllViews()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(2), dp(24), dp(36))
        }

        val standard = AudioProfileRuntimePolicy.isStandardRoute(route.id)
        val overrideCount = repository.overrideCount(route.id)
        val status = when {
            standard -> "Standard GMMP profile"
            overrideCount == 0 -> "Using Standard"
            else -> "$overrideCount custom ${if (overrideCount == 1) "section" else "sections"}"
        }
        body.addView(textView(
            status,
            12f,
            if (!standard && overrideCount > 0) COLOR_ACCENT else COLOR_MUTED,
            true
        ).apply { setPadding(dp(4), dp(2), dp(4), dp(8)) })
        body.addView(verticalGap(4))

        when (section) {
            AudioProfileSection.EQUALIZER -> {
                body.addView(groupCard(route.id, GoneSmartAudioProfiles.equalizerGroup))
            }

            AudioProfileSection.EFFECTS -> {
                GoneSmartAudioProfiles.effectGroups.forEachIndexed { index, group ->
                    body.addView(groupCard(route.id, group))
                    if (index != GoneSmartAudioProfiles.effectGroups.lastIndex) body.addView(verticalGap(10))
                }
            }
        }

        if (!standard) {
            body.addView(verticalGap(18))
            body.addView(resetButton(route))
        }

        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            addView(body)
        }
        contentScrollView = scrollView
        renderedContentKey = contentKey
        contentHost.addView(scrollView)
        if (restoreScrollY > 0) {
            scrollView.post { scrollView.scrollTo(0, restoreScrollY) }
        }
    }

    private fun groupCard(routeId: String, group: AudioProfileGroup): View {
        val standard = AudioProfileRuntimePolicy.isStandardRoute(routeId)
        val overridden = !standard && repository.isGroupOverridden(routeId, group.id)
        val observedCount = repository.inheritedObservedCount(routeId, group)
        val masterFieldId = AudioProfileControlPolicy.masterEnabledFieldId(group)
        val masterEnabled = masterFieldId?.let { fieldId ->
            val raw = if (overridden) {
                repository.value(routeId, fieldId)
            } else {
                repository.inheritedValue(routeId, fieldId)
            }
            raw == "true"
        } ?: true
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
        if (standard) {
            titleRow.addView(textView("STANDARD", 11f, COLOR_ACCENT, true))
        } else {
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
        }
        content.addView(titleRow)

        val inheritedStatus = when {
            observedCount == group.fields.size && standard -> "Saved GMMP baseline"
            observedCount == group.fields.size -> "Standard • inherited"
            standard -> "Standard • $observedCount/${group.fields.size} values observed"
            else -> "Standard • $observedCount/${group.fields.size} values observed"
        }
        content.addView(textView(
            if (overridden) "Custom for this output" else inheritedStatus,
            12f,
            if (overridden) COLOR_ACCENT else COLOR_MUTED
        ).apply { setPadding(0, dp(10), 0, dp(8)) })

        group.fields.forEachIndexed { index, field ->
            if (index > 0) content.addView(divider())
            val controlEnabled = AudioProfileControlPolicy.isFieldInteractive(
                group = group,
                fieldId = field.id,
                overridden = overridden,
                masterEnabled = masterEnabled
            )
            content.addView(fieldControl(routeId, group, field, controlEnabled))
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
        val inherited = repository.inheritedValue(routeId, field.id)
        val groupOverridden = repository.isGroupOverridden(routeId, group.id)
        val custom = if (groupOverridden) repository.value(routeId, field.id) else null
        val initial = custom ?: inherited ?: field.suggestedDefault
        val previewOnly = !groupOverridden && inherited == null

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = if (enabled) 1f else 0.50f
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
                            if (!isEnabled) return@setOnCheckedChangeListener
                            repository.setValue(routeId, field.id, checked.toString())
                            if (field.id == AudioProfileControlPolicy.masterEnabledFieldId(group)) {
                                refreshAll()
                            }
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
                                saveProfileFieldValue(routeId, group, field, normalized)
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
                                        saveProfileFieldValue(routeId, group, field, normalized)
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
                    "Standard value not observed yet • preview only",
                    11.5f,
                    COLOR_MUTED
                ).apply { setPadding(0, dp(4), 0, 0) })
            }
        }
    }

    private fun saveProfileFieldValue(
        routeId: String,
        group: AudioProfileGroup,
        field: AudioProfileField,
        normalized: String
    ) {
        if (group.id == GoneSmartAudioProfiles.equalizerGroup.id &&
            GoneSmartAudioProfiles.isEqualizerShapeField(field.id)
        ) {
            if (repository.setEqualizerShapeValue(routeId, field.id, normalized)) {
                equalizerPresetButton?.text = "${GoneSmartAudioProfiles.CUSTOM_PRESET}  ▾"
            }
        } else {
            repository.setValue(routeId, field.id, normalized)
        }
    }

    private fun showChoiceDialog(routeId: String, field: AudioProfileField) {
        val items = if (field.id == "eq.preset") {
            repository.availableNativePresets().toTypedArray()
        } else {
            val current = repository.value(routeId, field.id)
                ?: repository.inheritedValue(routeId, field.id)
                ?: field.suggestedDefault
            (field.choices + listOf(current))
                .filter { it.isNotBlank() && it != GoneSmartAudioProfiles.CUSTOM_PRESET }
                .distinct()
                .toTypedArray()
        }

        if (items.isEmpty()) {
            showNoLearnedPresetsDialog()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(field.title)
            .setItems(items) { _, which ->
                val selected = items[which]
                if (field.id == "eq.preset") {
                    if (repository.applyEqualizerPreset(routeId, selected)) refreshAll()
                } else {
                    repository.setValue(routeId, field.id, selected)
                    refreshAll()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNoLearnedPresetsDialog() {
        AlertDialog.Builder(this)
            .setTitle("No native 10-band presets found")
            .setMessage(
                "GoneSmart reads complete 10-band preset curves directly from GMMP's own preset storage. " +
                    "No preset-by-preset setup in GMMP is required. If this list is empty, GMMP's preset storage was not readable or no complete 10-band curves were found."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showNativeValuesMissingDialog(group: AudioProfileGroup) {
        AlertDialog.Builder(this)
            .setTitle("Standard values not ready yet")
            .setMessage(
                "GoneSmart has not yet captured every Standard GMMP value needed for ${group.title}. " +
                    "Disconnect Bluetooth once and wait a moment so the normal GMMP settings can be captured, then reconnect and enable the override again."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun maybeShowStandardSetupHint(route: AudioRouteProfile) {
        if (standardSetupHintShown || !route.bluetooth || !route.active || isStandardReady()) return
        standardSetupHintShown = true
        Snackbar.make(
            contentHost,
            "Standard is not ready yet. Disconnect Bluetooth once, wait a moment, then reconnect.",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun isStandardReady(): Boolean = repository.isStandardReady()

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Audio profiles")
            .setMessage(
                "Standard is your normal GMMP Equalizer and Effects state with no Bluetooth output. " +
                    "Bluetooth profiles override only the sections you enable and inherit Standard everywhere else.\n\n" +
                    "If Standard is not ready yet, disconnect Bluetooth once, wait a moment, then reconnect. GoneSmart captures the normal GMMP audio settings while Bluetooth is disconnected.\n\n" +
                    "Cards with an Enabled switch keep their dependent controls disabled while the effect is off. " +
                    "Equalizer is one complete override; turning it off disables preset, PRE and all band controls. " +
                    "The preset list is read from GMMP's own complete 10-band preset curves; moving PRE or a band changes the profile to Custom."
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
            routeLabel(route, includeCustomCount = true, includeChevron = false)
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

    private fun routeLabel(
        route: AudioRouteProfile,
        includeCustomCount: Boolean,
        includeChevron: Boolean
    ): CharSequence {
        val text = SpannableStringBuilder()
        if (route.active) {
            val dotStart = text.length
            text.append("●")
            text.setSpan(
                ForegroundColorSpan(COLOR_ACTIVE),
                dotStart,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.append("  ")
        }
        val labelStart = text.length
        text.append(route.label)
        if (route.active) {
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                labelStart,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (includeCustomCount) {
            val count = repository.overrideCount(route.id)
            if (count > 0) text.append("  •  $count custom")
        }
        if (includeChevron) text.append("  ▾")
        return text
    }

    private fun resetButton(route: AudioRouteProfile): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Reset this profile to Standard"
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
                    .setMessage("All saved Bluetooth overrides for this output will be removed. Standard is not changed.")
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
