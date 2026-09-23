package io.github.alagga.gonesmart

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    companion object {
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val TESTED_GMMP_VERSION = "4.2.0"

        private const val COLOR_BG = 0xFF151419.toInt()
        private const val COLOR_SURFACE = 0xFF1E1D23.toInt()
        private const val COLOR_SURFACE_2 = 0xFF25242B.toInt()
        private const val COLOR_NAV = 0xFF202127.toInt()
        private const val COLOR_TEXT = 0xFFF4F2F7.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFFBEBAC5.toInt()
        private const val COLOR_MUTED = 0xFF77737E.toInt()
        private const val COLOR_ACCENT = 0xFFA39AFF.toInt()
        private const val COLOR_ACCENT_DARK = 0xFF34305F.toInt()
        private const val COLOR_GREEN = 0xFF4CC96A.toInt()
        private const val COLOR_RED = 0xFFFF5C68.toInt()
        private const val COLOR_AMBER = 0xFFFFC857.toInt()
    }

    private enum class Tab {
        HOME,
        SMART,
        UI,
        LOGS,
        HELP
    }

    private lateinit var settingsRepository: GoneSmartSettingsRepository
    private lateinit var contentHost: FrameLayout
    private lateinit var statusHeadline: TextView
    private lateinit var statusSubline: TextView
    private lateinit var statusCard: MaterialCardView
    private lateinit var gmmpStatusText: TextView
    private lateinit var frameworkStatusText: TextView
    private lateinit var runtimeStatusText: TextView
    private lateinit var compatibilityText: TextView
    private var updateStatusText: TextView? = null
    private var updateVersionText: TextView? = null
    private var updateState: GitHubReleaseChecker.State = GitHubReleaseChecker.State.Checking
    private var updateCheckRunning = false
    private lateinit var logTextView: TextView
    private lateinit var logCountText: TextView
    private var minimumRatingSlider: Slider? = null
    private var minimumRatingValueText: TextView? = null

    private val switches = linkedMapOf<String, SwitchMaterial>()
    private val settingRows = linkedMapOf<String, View>()
    private val tabButtons = linkedMapOf<Tab, LinearLayout>()
    private val tabIconBackgrounds = linkedMapOf<Tab, FrameLayout>()
    private val tabLabels = linkedMapOf<Tab, TextView>()

    private val handler = Handler(Looper.getMainLooper())
    private var activeTab = Tab.HOME
    private var lastServiceAvailable = false

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            if (activeTab == Tab.LOGS) {
                refreshLogs()
            }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_NAV
        window.decorView.systemUiVisibility = 0

        settingsRepository = GoneSmartSettingsRepository(this)
        settingsRepository.synchronizeWithFramework()

        setContentView(buildRoot())
        showTab(Tab.HOME)
        refreshSettingsSwitches()
        refreshStatus()
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        settingsRepository.synchronizeWithFramework()
        refreshSettingsSwitches()
        refreshStatus()
        handler.removeCallbacks(statusTicker)
        handler.post(statusTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTicker)
        super.onPause()
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

        root.addView(buildHeader())

        contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(contentHost)
        root.addView(buildBottomNavigation())

        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(22), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(92)
            )
        }

        row.addView(buildBrandIcon())

        row.addView(textView("GoneSmart", 29f, COLOR_TEXT, bold = true).apply {
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(textView("v${BuildConfig.VERSION_NAME}", 15f, COLOR_TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_SURFACE_2, 28f)
            setPadding(dp(17), dp(9), dp(17), dp(9))
        })

        return row
    }

    private fun buildBrandIcon(): View {
        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        }

        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(
                R.drawable.gonesmart_logo_round
            )
            contentDescription = "GoneSmart"
            layoutParams = FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.CENTER
            )
        }

        holder.addView(logo)
        return holder
    }

    private fun buildBottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_NAV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(82)
            )
        }

        addNavItem(bar, Tab.HOME, "Home", R.drawable.ic_gs_home)
        addNavItem(bar, Tab.SMART, "Smart DJ", R.drawable.ic_gs_auto_dj_headphones)
        addNavItem(bar, Tab.UI, "UI", R.drawable.ic_gs_ui)
        addNavItem(bar, Tab.LOGS, "Logs", R.drawable.ic_gs_terminal)
        addNavItem(bar, Tab.HELP, "Help", R.drawable.ic_gs_help)

        return bar
    }

    private fun addNavItem(
        parent: LinearLayout,
        tab: Tab,
        label: String,
        iconRes: Int
    ) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab(tab) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val iconHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(36))
        }
        val icon = ImageView(this).apply {
            setImageDrawable(AppCompatResources.getDrawable(this@MainActivity, iconRes))
            imageTintList = ColorStateList.valueOf(COLOR_TEXT_SECONDARY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER)
        }
        iconHolder.addView(icon)
        item.addView(iconHolder)

        val labelView = textView(label, 13f, COLOR_TEXT_SECONDARY, bold = false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        item.addView(labelView)

        parent.addView(item)
        tabButtons[tab] = item
        tabIconBackgrounds[tab] = iconHolder
        tabLabels[tab] = labelView
        item.tag = icon
    }

    private fun showTab(tab: Tab) {
        activeTab = tab
        // Switches and rating views belong to the outgoing page. Never
        // retain detached views when moving between Home, Smart DJ and UI.
        switches.clear()
        settingRows.clear()
        minimumRatingSlider = null
        minimumRatingValueText = null
        contentHost.removeAllViews()

        val view = when (tab) {
            Tab.HOME -> buildHomePage()
            Tab.SMART -> buildSmartPage()
            Tab.UI -> buildUiPage()
            Tab.LOGS -> buildLogsPage()
            Tab.HELP -> buildHelpPage()
        }
        contentHost.addView(view)

        tabButtons.forEach { (candidate, item) ->
            val selected = candidate == tab
            val icon = item.tag as ImageView
            val color = if (selected) COLOR_TEXT else COLOR_TEXT_SECONDARY
            icon.imageTintList = ColorStateList.valueOf(color)
            tabLabels[candidate]?.apply {
                setTextColor(color)
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
            tabIconBackgrounds[candidate]?.background =
                if (selected) rounded(COLOR_ACCENT_DARK, 24f) else null
        }

        if (tab == Tab.LOGS) {
            refreshLogs()
        }
        refreshUpdateStatus()
        refreshStatus()
    }

    private fun buildHomePage(): View {
        val container = pageContainer()
        container.addView(sectionTitle("STATUS"))

        statusCard = card().apply {
            setContentPadding(dp(22), dp(20), dp(22), dp(20))
        }
        val statusContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        statusHeadline = textView("Checking module…", 21f, COLOR_TEXT, bold = true)
        statusSubline = textView("Waiting for Xposed service", 15f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(5), 0, dp(14))
        }
        statusContent.addView(statusHeadline)
        statusContent.addView(statusSubline)
        statusContent.addView(divider())

        gmmpStatusText = statusRow("GoneMAD Music Player", "Checking…")
        frameworkStatusText = statusRow("Xposed framework", "Checking…")
        runtimeStatusText = statusRow("GoneSmart state", "Idle")
        compatibilityText = statusRow("Compatibility", "Tested with $TESTED_GMMP_VERSION")

        statusContent.addView(gmmpStatusText)
        statusContent.addView(frameworkStatusText)
        statusContent.addView(runtimeStatusText)
        statusContent.addView(compatibilityText)
        statusCard.addView(statusContent)
        container.addView(statusCard)

        container.addView(verticalGap(14))
        container.addView(actionButton("Open GoneMAD Music Player") {
            openGmmp()
        })

        container.addView(verticalGap(10))
        container.addView(outlineButton("Restart GMMP") {
            restartGmmp()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))

        container.addView(verticalGap(24))
        container.addView(sectionTitle("UPDATES"))
        val updateCard = card()
        val updateContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(18), dp(19), dp(18))
        }
        updateContent.addView(
            textView("Installed v${BuildConfig.VERSION_NAME}", 15f, COLOR_TEXT, bold = true)
        )
        updateStatusText = textView("Checking GitHub Releases…", 16f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(12), 0, dp(3))
        }
        updateVersionText = textView("", 13f, COLOR_MUTED)
        updateContent.addView(updateStatusText)
        updateContent.addView(updateVersionText)
        updateCard.addView(updateContent)
        container.addView(updateCard)
        container.addView(verticalGap(12))
        container.addView(actionButton("Add to Obtainium") {
            openObtainium()
        })
        container.addView(verticalGap(10))
        container.addView(outlineButton("Check for updates") {
            checkForUpdates()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))
        container.addView(verticalGap(10))
        container.addView(outlineButton("View GitHub releases") {
            openUrl("https://github.com/alagga/GoneSmart/releases")
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))
        refreshUpdateStatus()

        container.addView(verticalGap(24))
        container.addView(sectionTitle("FEATURES"))
        container.addView(infoCard(
            title = "Smart DJ",
            body = "Smarter recommendations for your local library, directly in GMMP's Auto-DJ. GMMP still controls playback and its queue; configure recommendation and fallback options in the Smart DJ tab."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "UI tweaks",
            body = "Optional enhancements to GMMP's interface, independent of Smart DJ. For example, select several playlists at once in Add to Playlist. Enable and configure available tweaks in the UI tab."
        ))

        container.addView(verticalGap(24))
        container.addView(sectionTitle("SETTINGS"))
        container.addView(infoCard(
            title = "Settings apply live",
            body = "Changes in Smart DJ and UI take effect while GMMP is running. Restart GMMP only after a GoneSmart update or if something isn't working."
        ))

        return scrollPage(container)
    }

    private fun buildSmartPage(): View {
        val container = pageContainer()
        container.addView(pageTitle("Smart DJ"))
        container.addView(sectionTitle("GENERAL"))

        container.addView(settingGroup(listOf(
            SettingSpec(
                key = GoneSmartSettingsKeys.KEY_ENABLED,
                glyph = "✦",
                title = "Enable Smart DJ",
                subtitle = "Let GoneSmart choose music when GMMP Auto-DJ needs more tracks.",
                accent = COLOR_ACCENT
            )
        )))

        container.addView(verticalGap(24))
        container.addView(sectionTitle("MATCHING"))
        container.addView(minimumRatingCard())
        container.addView(verticalGap(10))
        container.addView(settingGroup(listOf(
            SettingSpec(
                GoneSmartSettingsKeys.KEY_SMART_RATING,
                "M",
                "Smart rating",
                "Use the median rating of the current recommendation context as a dynamic minimum. If Minimum rating is higher, the stricter value wins.",
                COLOR_ACCENT
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING,
                "↘",
                "Rating fallback",
                "Available when Minimum rating is above zero or Smart rating is enabled. If no tracks pass, retry once without those thresholds before native GMMP fallback.",
                COLOR_AMBER
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED,
                "★",
                "Prefer higher-rated matches",
                "When several tracks are suitable, favor the ones you've rated higher.",
                COLOR_ACCENT
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_EXCLUDE_HALF_STAR,
                "½",
                "Exclude 0.5-star tracks",
                "Never select tracks rated exactly half a star. This remains active even during Rating fallback.",
                COLOR_RED
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_PREFER_STUDIO,
                "L",
                "Prefer studio over live",
                "Prefer studio recordings unless you're listening to live music.",
                COLOR_AMBER
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_MATCH_QUEUE_ERA,
                "Y",
                "Match current music era",
                "Give a small bonus to tracks from a similar release period.",
                COLOR_GREEN
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_FAVOR_RECENTLY_ADDED,
                "+",
                "Favor recently added tracks",
                "Favor recently added songs when you're listening to newer additions.",
                COLOR_GREEN
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES,
                "≠",
                "Prevent version duplicates",
                "Block Original, Radio, Extended and Club Mix variants of the same song family.",
                COLOR_ACCENT
            )
        )))

        container.addView(verticalGap(24))
        container.addView(sectionTitle("FALLBACK & STATUS"))
        container.addView(settingGroup(listOf(
            SettingSpec(
                GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES,
                "↩",
                "Fallback when no matches exist",
                "Use regular GMMP Auto-DJ when GoneSmart finds no suitable local track.",
                COLOR_RED
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES,
                "i",
                "Show status messages",
                "Show a short message when fallback or another important state is entered.",
                COLOR_ACCENT
            )
        )))

        container.addView(verticalGap(18))
        container.addView(infoCard(
            title = "Offline behavior",
            body = "A valid pool from the current queue can continue offline. A new queue never reuses an old pool; if GoneSmart has no usable cached track, GMMP Auto-DJ takes over and the player sparkle turns red."
        ))

        refreshSettingsSwitches()
        return scrollPage(container)
    }

    private fun buildUiPage(): View {
        val container = pageContainer()
        container.addView(pageTitle("UI"))
        container.addView(sectionTitle("PLAYLISTS"))
        container.addView(settingGroup(listOf(
            SettingSpec(
                GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST,
                "✓",
                "Multi-playlist selection",
                "Long-press a playlist when adding songs, select more " +
                    "playlists and tap the checkmark to add your songs to all of them.",
                COLOR_ACCENT
            )
        )))
        container.addView(verticalGap(24))
        container.addView(sectionTitle("PLAYBACK & QUEUE"))
        container.addView(settingGroup(listOf(
            SettingSpec(
                GoneSmartSettingsKeys.KEY_TRACK_MIX,
                "♫",
                "Track Mix",
                "Play any song and start a fresh Auto-DJ mix based on it.",
                COLOR_ACCENT
            ),
            SettingSpec(
                GoneSmartSettingsKeys.KEY_FLIP_QUEUE,
                "⇵",
                "Flip queue / Play flipped",
                "Reverse your entire queue while keeping the current song, " +
                    "or play any playlist or Smart Playlist from its last " +
                    "song to its first.",
                COLOR_ACCENT
            )
        )))
        refreshSettingsSwitches()
        return scrollPage(container)
    }

    private fun buildLogsPage(): View {
        val container = pageContainer()
        container.addView(pageTitle("Logs"))
        container.addView(textView(
            "Recent activity from Smart DJ, playlists, Flip and Track Mix.",
            14f,
            COLOR_TEXT_SECONDARY
        ))
        container.addView(verticalGap(14))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(outlineButton("Clear") {
            GoneSmartEventStore.clear(this)
            refreshLogs()
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(8)
        })
        actions.addView(actionButton("Copy") {
            val text = GoneSmartEventStore.logText(this)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GoneSmart logs", text))
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginStart = dp(8)
        })
        container.addView(actions)

        logCountText = textView("0 lines", 14f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(14), 0, dp(8))
        }
        container.addView(logCountText)

        val logCard = card().apply {
            setContentPadding(dp(18), dp(18), dp(18), dp(18))
        }
        logTextView = textView("No runtime events yet.", 13f, COLOR_TEXT_SECONDARY).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.14f)
        }
        logCard.addView(logTextView)
        container.addView(logCard)

        container.addView(verticalGap(14))
        container.addView(textView(
            "Shows recent activity from all GoneSmart features. For detailed " +
                "diagnostics, filter Logcat by GoneSmart, GoneSmartPlaylist, " +
                "GoneSmartFlip or GoneSmartTrackMix.",
            13f,
            COLOR_MUTED
        ))

        return scrollPage(container)
    }

    private fun buildHelpPage(): View {
        val container = pageContainer()
        container.addView(pageTitle("Help"))

        container.addView(sectionTitle("HOW IT WORKS"))
        container.addView(infoCard(
            title = "How are tracks selected?",
            body = "GoneSmart keeps GMMP's playback and queue handling, but replaces Auto-DJ track selection. It builds a session-aware context from the current and recent user-selected tracks, asks ListenBrainz and Last.fm for similar music, merges both signals, and matches the result against your local GMMP library. Only tracks that actually exist in your library can be selected."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "What does Track Mix do?",
            body = "In a song's three-dot menu, choose Track Mix after Play next. " +
                "GoneSmart plays that song, keeps it as the only initial queue " +
                "entry, switches GMMP to Auto-DJ and fills the queue to your " +
                "configured Initial Size with recommended local tracks. " +
                "Choosing Track Mix also enables Smart DJ if it was off. " +
                "Turn Track Mix on or off in the UI tab. Completed mixes " +
                "show one confirmation; errors appear separately."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "How much queue history is used?",
            body = "GoneSmart uses up to five representative seed tracks for the external recommendation providers. The current and most recent tracks carry the strongest weight. The wider session is still used for context such as rating, era, duplicate handling and drift control, without turning a long playlist into dozens of network requests."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "What happens when no local match is found?",
            body = "The normal provider pass runs first. If it produces zero usable local candidates, GoneSmart performs exactly one broader search with more Last.fm results and a wider ListenBrainz recording search. If that still finds nothing, the configured rating and GMMP Auto-DJ fallback rules decide what happens next."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "How does the recommendation pool work?",
            body = "GoneSmart prepares a pool of suitable local tracks instead of running a full network search for every song. GMMP can take tracks from that pool immediately. A refill is only prepared when the pool becomes low or when the queue session or relevant settings change, which keeps the system responsive and resource-efficient."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "How do Minimum rating and Smart rating work?",
            body = "Minimum rating is a fixed 0-5 star threshold in 0.5-star steps. Smart rating calculates the median GMMP rating of the tracks currently used as recommendation context. If both are enabled, GoneSmart uses whichever threshold is stricter. A 0 rating counts as 0 stars."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "What does Rating fallback do?",
            body = "The switch is only available while Minimum rating is above zero or Smart rating is enabled; otherwise it is greyed out and reset to off. When enabled, GoneSmart can retry the same recommendations once without those two thresholds. Exclude 0.5-star tracks still applies. If nothing suitable remains, the configured GMMP Auto-DJ fallback behavior applies."
        ))

        container.addView(verticalGap(22))
        container.addView(sectionTitle("RECOMMENDATION SOURCES"))
        container.addView(infoCard(
            title = "ListenBrainz + Last.fm",
            body = "ListenBrainz provides MusicBrainz-backed recording lookup and similar-recording data. Last.fm contributes similar-track data. GoneSmart sends only the seed metadata needed for those recommendation lookups; your full GMMP library stays local and is matched on-device. GoneSmart does not stream music from either service."
        ))

        container.addView(verticalGap(12))
        container.addView(outlineButton("Open ListenBrainz") {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://listenbrainz.org/")
                )
            )
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))

        container.addView(verticalGap(10))
        container.addView(outlineButton("Open Last.fm") {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.last.fm/")
                )
            )
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))

        container.addView(verticalGap(22))
        container.addView(sectionTitle("INDICATOR"))
        container.addView(infoCard(
            title = "What does the sparkle mean?",
            body = "Green: GMMP Auto-DJ is selected and GoneSmart is ready, either through an online recommendation path or a still-valid cached pool. Red: GMMP Auto-DJ is selected but GoneSmart currently cannot supply a smart track, an error/no-match state occurred, or regular GMMP Auto-DJ fallback is being used. No sparkle: GoneSmart is disabled, or the GMMP playback mode is Shuffle/Normal rather than Auto-DJ."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "What happens offline?",
            body = "A valid GoneSmart pool from the current queue session can continue offline. Starting a different queue never reuses an old session pool. If there is no suitable cached GoneSmart track and the device is offline, the regular GMMP Auto-DJ fallback can take over when enabled."
        ))

        container.addView(verticalGap(22))
        container.addView(sectionTitle("SETTINGS"))
        container.addView(infoCard(
            title = "Settings apply live",
            body = "Both Smart DJ and UI settings apply to the running GMMP process without a restart. Recommendation-related changes invalidate the old pool so the next Auto-DJ refill follows the new settings without interrupting playback. UI tweaks, including multi-playlist selection, can be enabled or disabled independently. Restart GMMP only after module/framework updates or if troubleshooting requires it."
        ))

        container.addView(verticalGap(22))
        container.addView(sectionTitle("COMPATIBILITY"))
        container.addView(infoCard(
            title = "Tested GMMP version",
            body = "GoneSmart is currently tested against GoneMAD Music Player $TESTED_GMMP_VERSION. Other GMMP versions are considered untested because GoneSmart hooks GMMP internals that can change between releases."
        ))

        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "Xposed / LSPatch",
            body = "The rooted setup targets the modern libxposed API 102 implementation in Vector 2.2 or newer. LSPatch 1.2 is documented as an experimental no-root path, but it has not yet been validated as thoroughly as the rooted Vector setup."
        ))

        container.addView(verticalGap(22))
        container.addView(sectionTitle("TROUBLESHOOTING"))
        container.addView(infoCard(
            title = "Module not active?",
            body = "Make sure GoneSmart is enabled for gonemad.gmmp in your Xposed framework. If hooks do not refresh after a module update, use Restart GMMP below. The Logs tab contains high-level GoneSmart events; detailed development logs are available in Logcat with the tag GoneSmart."
        ))

        container.addView(verticalGap(14))
        container.addView(outlineButton("Restart GMMP") {
            restartGmmp()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))

        container.addView(verticalGap(10))
        container.addView(actionButton("Open GoneSmart on GitHub") {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/alagga/GoneSmart")
                )
            )
        })

        return scrollPage(container)
    }

    private fun minimumRatingCard(): View {
        val options = settingsRepository.read()

        val card = card().apply {
            setContentPadding(dp(20), dp(18), dp(20), dp(14))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(
            textView(
                "Minimum rating",
                16f,
                COLOR_TEXT,
                bold = true
            ),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val valueText =
            textView(
                minimumRatingLabel(options.minimumRatingStars),
                15f,
                COLOR_ACCENT,
                bold = true
            )

        minimumRatingValueText =
            valueText

        titleRow.addView(
            valueText
        )

        content.addView(titleRow)
        content.addView(
            textView(
                "Only allow automatically selected tracks with at least this GMMP rating. 0 disables the fixed minimum.",
                13f,
                COLOR_TEXT_SECONDARY
            ).apply {
                setPadding(0, dp(5), 0, dp(8))
            }
        )

        val slider =
            Slider(this).apply {
                valueFrom = 0f
                valueTo = 5f
                stepSize = 0.5f
                value = options.minimumRatingStars.toFloat()
                trackActiveTintList = ColorStateList.valueOf(COLOR_ACCENT)
                trackInactiveTintList = ColorStateList.valueOf(0xFF494650.toInt())
                thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
                haloTintList = ColorStateList.valueOf(withAlpha(COLOR_ACCENT, 0.20f))
                setLabelFormatter { sliderValue ->
                    minimumRatingLabel(sliderValue.toDouble())
                }
                addOnChangeListener { _, sliderValue, fromUser ->
                    minimumRatingValueText?.text =
                        minimumRatingLabel(sliderValue.toDouble())

                    if (fromUser) {
                        settingsRepository.setFloat(
                            GoneSmartSettingsKeys.KEY_MINIMUM_RATING,
                            sliderValue
                        )
                        refreshRatingFallbackAvailability()
                    }
                }
            }

        minimumRatingSlider =
            slider

        content.addView(slider)
        card.addView(content)
        return card
    }

    private fun minimumRatingLabel(value: Double): String {
        val normalized =
            kotlin.math.round(value * 2.0) / 2.0

        return if (normalized <= 0.0) {
            "Off"
        } else {
            String.format(
                java.util.Locale.US,
                "%.1f ★",
                normalized
            )
        }
    }

    private fun settingGroup(specs: List<SettingSpec>): View {
        val card = card().apply {
            setContentPadding(0, dp(4), 0, dp(4))
        }
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        specs.forEachIndexed { index, spec ->
            group.addView(settingRow(spec))
            if (index != specs.lastIndex) {
                group.addView(divider().apply {
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    params.marginStart = dp(76)
                    params.marginEnd = dp(18)
                    layoutParams = params
                })
            }
        }
        card.addView(group)
        return card
    }

    private fun settingRow(spec: SettingSpec): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(14), dp(13))
            minimumHeight = dp(76)
        }

        val glyph = textView(spec.glyph, 18f, spec.accent, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(withAlpha(spec.accent, 0.18f), 14f)
        }
        row.addView(glyph, LinearLayout.LayoutParams(dp(46), dp(46)))

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        labels.addView(textView(spec.title, 16f, COLOR_TEXT, bold = true))
        labels.addView(textView(spec.subtitle, 13f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val switch = SwitchMaterial(this).apply {
            buttonTintList = null
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(COLOR_ACCENT_DARK, 0xFF46434D.toInt())
            )
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(COLOR_ACCENT, 0xFFC8C5CE.toInt())
            )
            setOnCheckedChangeListener { _, checked ->
                onSettingChanged(spec.key, checked)
            }
        }
        switches[spec.key] = switch
        settingRows[spec.key] = row
        row.addView(switch)
        return row
    }

    private fun refreshSettingsSwitches() {
        if (switches.isEmpty()) return
        val options = settingsRepository.read()

        setSwitch(GoneSmartSettingsKeys.KEY_ENABLED, options.enabled)
        setSwitch(GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST, options.multiPlaylistEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_FLIP_QUEUE, options.flipQueueEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_TRACK_MIX, options.trackMixEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)
        setSwitch(GoneSmartSettingsKeys.KEY_SMART_RATING, options.smartRatingEnabled)
        setSwitch(
            GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING,
            options.fallbackWithoutRatingRestrictions &&
                (options.minimumRatingStars > 0.0 || options.smartRatingEnabled)
        )
        setSwitch(GoneSmartSettingsKeys.KEY_EXCLUDE_HALF_STAR, options.excludeHalfStarTracks)

        minimumRatingSlider?.let { slider ->
            val value = options.minimumRatingStars.toFloat()
            if (kotlin.math.abs(slider.value - value) > 0.001f) {
                slider.value = value
            }
            minimumRatingValueText?.text = minimumRatingLabel(options.minimumRatingStars)
        }
        setSwitch(GoneSmartSettingsKeys.KEY_PREFER_STUDIO, options.preferStudioVersionsOverLive)
        setSwitch(GoneSmartSettingsKeys.KEY_MATCH_QUEUE_ERA, options.matchQueueEra)
        setSwitch(GoneSmartSettingsKeys.KEY_FAVOR_RECENTLY_ADDED, options.favorRecentlyAddedTracks)
        setSwitch(
            GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES,
            options.preventBaseVersionDuplicates
        )
        setSwitch(
            GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES,
            options.fallbackToNativeAutoDjWhenNoSuitableTracks
        )
        setSwitch(GoneSmartSettingsKeys.KEY_SHOW_STATUS_MESSAGES, options.showStatusMessages)
        refreshRatingFallbackAvailability(options)
    }

    /**
     * Rating fallback is meaningful only if Minimum rating or Smart rating
     * can actually exclude tracks. Disable and uncheck it otherwise. A
     * genuine user change is written immediately to the running target.
     */
    private fun refreshRatingFallbackAvailability(
        suppliedOptions: GoneSmartOptions? = null
    ) {
        val options = suppliedOptions ?: settingsRepository.read()
        val available =
            options.minimumRatingStars > 0.0 || options.smartRatingEnabled

        if (!available && options.fallbackWithoutRatingRestrictions) {
            // Prevent an invisible, pre-checked fallback from automatically
            // activating when the user later enables a rating threshold.
            settingsRepository.setBoolean(
                GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING,
                false
            )
        }

        val fallbackKey = GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING
        setSwitch(fallbackKey, available && options.fallbackWithoutRatingRestrictions)
        switches[fallbackKey]?.isEnabled = available
        settingRows[fallbackKey]?.alpha = if (available) 1f else 0.45f
    }

    private fun onSettingChanged(key: String, checked: Boolean) {
        if (key == GoneSmartSettingsKeys.KEY_FALLBACK_WITHOUT_RATING) {
            val options = settingsRepository.read()
            if (
                options.minimumRatingStars <= 0.0 &&
                !options.smartRatingEnabled
            ) {
                refreshRatingFallbackAvailability(options)
                return
            }
        }

        settingsRepository.setBoolean(key, checked)
        if (key == GoneSmartSettingsKeys.KEY_SMART_RATING) {
            refreshRatingFallbackAvailability()
        }
    }

    private fun setSwitch(key: String, value: Boolean) {
        val switch = switches[key] ?: return
        if (switch.isChecked == value) return
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = value
        switch.setOnCheckedChangeListener { _, checked ->
            onSettingChanged(key, checked)
        }
    }

    private fun refreshStatus() {
        if (!::statusHeadline.isInitialized) return

        val service = GoneSmartApplication.xposedService
        val serviceAvailable = service != null
        if (serviceAvailable && !lastServiceAvailable) {
            settingsRepository.synchronizeWithFramework()
            refreshSettingsSwitches()
        }
        lastServiceAvailable = serviceAvailable

        val running = try {
            service?.runningTargets?.any {
                it.processName == GMMP_PACKAGE || it.processName.startsWith("$GMMP_PACKAGE:")
            } == true
        } catch (_: Throwable) {
            false
        }

        val gmmpVersion = getGmmpVersion()
        val installed = gmmpVersion != null
        val options = settingsRepository.read()

        when {
            !serviceAvailable -> {
                statusHeadline.text = "Xposed service not connected"
                statusSubline.text = "Open your Xposed framework and verify GoneSmart is enabled."
                statusCard.setCardBackgroundColor(COLOR_SURFACE)
            }

            running && options.enabled -> {
                statusHeadline.text = "GoneSmart module active"
                statusSubline.text = "Injected into GoneMAD Music Player"
                statusCard.setCardBackgroundColor(withAlpha(COLOR_GREEN, 0.35f))
            }

            running && !options.enabled -> {
                statusHeadline.text = "GoneSmart disabled"
                statusSubline.text = "GMMP is running with its normal Auto-DJ selection."
                statusCard.setCardBackgroundColor(COLOR_SURFACE)
            }

            else -> {
                statusHeadline.text = "GoneSmart ready"
                statusSubline.text = "Start GoneMAD Music Player to activate the module."
                statusCard.setCardBackgroundColor(COLOR_SURFACE)
            }
        }

        gmmpStatusText.text = if (installed) {
            "GoneMAD Music Player\nInstalled • $gmmpVersion${if (running) " • running" else ""}"
        } else {
            "GoneMAD Music Player\nNot installed or not visible"
        }

        frameworkStatusText.text = if (service != null) {
            val frameworkName = try { service.frameworkName } catch (_: Throwable) { "Xposed" }
            val frameworkVersion = try { service.frameworkVersion } catch (_: Throwable) { "" }
            "Xposed framework\n$frameworkName $frameworkVersion • API ${service.apiVersion}"
        } else {
            "Xposed framework\nNot connected"
        }

        val runtime = GoneSmartEventStore.snapshot(this)
        runtimeStatusText.text = "GoneSmart state\n${runtimeDescription(runtime)}"

        compatibilityText.text = when {
            gmmpVersion == null -> "Compatibility\nTested with GMMP $TESTED_GMMP_VERSION"
            gmmpVersion == TESTED_GMMP_VERSION -> "Compatibility\nTested • GMMP $gmmpVersion"
            else -> "Compatibility\nUntested GMMP version $gmmpVersion • tested: $TESTED_GMMP_VERSION"
        }
    }

    private fun runtimeDescription(snapshot: GoneSmartEventStore.RuntimeSnapshot): String {
        val label = when (snapshot.mode) {
            GoneSmartRuntimeContract.MODE_SMART -> "Ready / GoneSmart"
            GoneSmartRuntimeContract.MODE_FALLBACK -> "Fallback / problem"
            GoneSmartRuntimeContract.MODE_STOPPED -> "Problem / stopped"
            else -> "Normal / idle"
        }
        if (snapshot.timestamp <= 0L) return label
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.timestamp))
        return "$label • $time\n${snapshot.message}"
    }

    private fun refreshLogs() {
        if (!::logTextView.isInitialized) return
        val text = GoneSmartEventStore.logText(this)
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val summary = GoneSmartEventStore.summary(this)
        logCountText.text =
            "${summary.total} events • Smart DJ ${summary.smartDj} • " +
                "Playlists ${summary.playlists} • Flip ${summary.flip} • " +
                "Track Mix ${summary.trackMix}" +
                if (summary.other > 0) " • Other ${summary.other}" else ""
        logTextView.text = if (lines.isEmpty()) {
            "No events yet. Activity will appear here as you use GoneSmart."
        } else {
            lines.joinToString("\n")
        }
    }

    private fun getGmmpVersion(): String? {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    GMMP_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(GMMP_PACKAGE, 0)
            }
            info.versionName
        } catch (_: Throwable) {
            null
        }
    }

    private fun openGmmp() {
        val intent = packageManager.getLaunchIntentForPackage(GMMP_PACKAGE)
        if (intent == null) {
            Toast.makeText(this, "GoneMAD Music Player was not found.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    private fun restartGmmp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(GMMP_PACKAGE)
        if (launchIntent == null) {
            Toast.makeText(this, "GoneMAD Music Player was not found.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Restarting GMMP…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "am force-stop $GMMP_PACKAGE"
                    )
                )

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw IllegalStateException("root force-stop returned $exitCode")
                }

                Thread.sleep(450L)

                runOnUiThread {
                    try {
                        startActivity(launchIntent)
                    } catch (_: Throwable) {
                        Toast.makeText(
                            this,
                            "GMMP was stopped but could not be opened automatically.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Could not restart GMMP. Root access may be unavailable.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun refreshUpdateStatus() {
        val (message, detail, color) = when (val state = updateState) {
            GitHubReleaseChecker.State.Checking ->
                Triple("Checking GitHub Releases…", "No APK downloads are started.", COLOR_TEXT_SECONDARY)
            is GitHubReleaseChecker.State.UpToDate ->
                Triple(
                    "You have the latest published release",
                    "GitHub: v${state.version}",
                    COLOR_GREEN
                )
            is GitHubReleaseChecker.State.NewVersion ->
                Triple(
                    "New version available: v${state.version}",
                    "Open Obtainium to install this update.",
                    COLOR_AMBER
                )
            is GitHubReleaseChecker.State.DevelopmentBuild ->
                Triple(
                    "Development build",
                    "Latest published release: v${state.version}",
                    COLOR_ACCENT
                )
            is GitHubReleaseChecker.State.Unavailable ->
                Triple(
                    "Update check unavailable",
                    "Check your connection or retry. ${state.reason}",
                    COLOR_TEXT_SECONDARY
                )
        }
        updateStatusText?.apply {
            text = message
            setTextColor(color)
        }
        updateVersionText?.text = detail
    }

    private fun checkForUpdates() {
        if (updateCheckRunning) return
        updateCheckRunning = true
        updateState = GitHubReleaseChecker.State.Checking
        refreshUpdateStatus()
        GitHubReleaseChecker.check(BuildConfig.VERSION_NAME) { result ->
            runOnUiThread {
                updateCheckRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateState = result
                refreshUpdateStatus()
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openObtainium() {
        // Official Obtainium deep link; package-pinned so no other app can
        // intercept this action. Importing is still explicitly confirmed
        // inside Obtainium, never silently installed by GoneSmart.
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("obtainium://add/https://github.com/alagga/GoneSmart")
        ).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage("dev.imranr.obtainium")
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("Obtainium is not installed")
                .setMessage(
                    "Obtainium can track GoneSmart's GitHub Releases " +
                        "and install signed updates. Install Obtainium " +
                        "first or add the GoneSmart repository URL manually."
                )
                .setPositiveButton("Get Obtainium") { _, _ ->
                    openUrl("https://github.com/ImranR98/Obtainium/releases/latest")
                }
                .setNeutralButton("View GoneSmart") { _, _ ->
                    openUrl("https://github.com/alagga/GoneSmart")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun pageContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(34))
        }
    }

    private fun scrollPage(content: View): View {
        return ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
    }

    private fun card(): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(COLOR_SURFACE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun infoCard(title: String, body: String): View {
        val card = card().apply {
            setContentPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(textView(title, 17f, COLOR_TEXT, bold = true))
        content.addView(textView(body, 14f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(7), 0, 0)
        })
        card.addView(content)
        return card
    }

    private fun statusRow(title: String, value: String): TextView {
        return textView("$title\n$value", 14f, COLOR_TEXT_SECONDARY).apply {
            setLineSpacing(0f, 1.08f)
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun pageTitle(text: String): View {
        return textView(text, 31f, COLOR_TEXT, bold = true).apply {
            setPadding(dp(4), dp(4), 0, dp(26))
        }
    }

    private fun sectionTitle(text: String): View {
        return textView(text, 15f, COLOR_ACCENT, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(dp(6), dp(10), 0, dp(12))
        }
    }

    private fun actionButton(
        text: String,
        onClick: () -> Unit
    ): MaterialButton {
        return MaterialButton(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFF17151E.toInt())
            setTypeface(typeface, Typeface.BOLD)
            cornerRadius = dp(24)
            backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        }
    }

    private fun outlineButton(
        text: String,
        onClick: () -> Unit
    ): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 16f
            setTextColor(COLOR_ACCENT)
            strokeColor = ColorStateList.valueOf(0xFF5A5662.toInt())
            strokeWidth = dp(1)
            cornerRadius = dp(24)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }
    }

    private fun textView(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFF343239.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }
    }

    private fun verticalGap(heightDp: Int): View {
        return Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
        }
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val alpha = (255 * fraction.coerceIn(0f, 1f)).toInt()
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class SettingSpec(
        val key: String,
        val glyph: String,
        val title: String,
        val subtitle: String,
        val accent: Int
    )


}
