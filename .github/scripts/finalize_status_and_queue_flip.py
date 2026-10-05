from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/io/github/alagga/gonesmart/MainActivity.kt")
main = main_path.read_text()

main = replace_once(
    main,
    "    private lateinit var statusCard: MaterialCardView\n",
    "    private lateinit var statusCard: MaterialCardView\n"
    "    private lateinit var statusSummarySection: LinearLayout\n",
    "status summary field",
)

old_home = '''        val statusContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
        }

        statusHeadline = textView("Checking module…", 21f, COLOR_TEXT, bold = true)
        statusSubline = textView("Waiting for Xposed service", 15f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(5), 0, dp(14))
        }
        statusContent.addView(statusHeadline)
        statusContent.addView(statusSubline)
        // Exactly one separator belongs below the overall status. Individual
        // health rows use spacing/backgrounds instead of additional dividers.
        statusContent.addView(divider())
'''
new_home = '''        val statusContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        statusSummarySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
        }
        statusHeadline = textView("Checking module…", 21f, COLOR_TEXT, bold = true)
        statusSubline = textView("Waiting for Xposed service", 15f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(5), 0, 0)
        }
        statusSummarySection.addView(statusHeadline)
        statusSummarySection.addView(statusSubline)
        statusContent.addView(statusSummarySection)
        // The only separator remains directly below the aggregate status.
        // The following health rows are contiguous colored sections of this
        // same outer card, not nested cards of their own.
        statusContent.addView(divider())
'''
main = replace_once(main, old_home, new_home, "home status layout")

old_helpers = '''    private fun statusRow(title: String, value: String): TextView {
        return textView("$title\\n$value", 14f, COLOR_TEXT_SECONDARY).apply {
            setLineSpacing(0f, 1.08f)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(COLOR_SURFACE_2, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    private fun applyStatusTone(view: TextView, tone: StatusHealthPolicy.Tone) {
        val accent = when (tone) {
            StatusHealthPolicy.Tone.GREEN -> COLOR_GREEN
            StatusHealthPolicy.Tone.AMBER -> COLOR_AMBER
            StatusHealthPolicy.Tone.RED -> COLOR_RED
        }
        view.background = rounded(blendColors(COLOR_SURFACE_2, accent, 0.30f), 14f)
        view.setTextColor(COLOR_TEXT_SECONDARY)
    }
'''
new_helpers = '''    private fun statusRow(title: String, value: String): TextView {
        return textView("$title\\n$value", 14f, COLOR_TEXT_SECONDARY).apply {
            setLineSpacing(0f, 1.08f)
            setPadding(dp(22), dp(13), dp(22), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun applyStatusTone(view: View, tone: StatusHealthPolicy.Tone) {
        val accent = when (tone) {
            StatusHealthPolicy.Tone.GREEN -> COLOR_GREEN
            StatusHealthPolicy.Tone.AMBER -> COLOR_AMBER
            StatusHealthPolicy.Tone.RED -> COLOR_RED
        }
        view.setBackgroundColor(blendColors(COLOR_SURFACE, accent, 0.30f))
    }
'''
main = replace_once(main, old_helpers, new_helpers, "status row helpers")

main = replace_once(
    main,
    "        applyStatusTone(gmmpStatusText, StatusHealthPolicy.gmmp(installed, running))\n",
    "        val gmmpTone = StatusHealthPolicy.gmmp(installed, running)\n"
    "        applyStatusTone(gmmpStatusText, gmmpTone)\n",
    "gmmp tone",
)

old_framework = '''        applyStatusTone(
            frameworkStatusText,
            StatusHealthPolicy.framework(serviceAvailable, frameworkApi)
        )
'''
new_framework = '''        val frameworkTone = StatusHealthPolicy.framework(serviceAvailable, frameworkApi)
        applyStatusTone(frameworkStatusText, frameworkTone)
'''
main = replace_once(main, old_framework, new_framework, "framework tone")

old_runtime = '''        applyStatusTone(
            runtimeStatusText,
            StatusHealthPolicy.runtime(
                serviceAvailable = serviceAvailable,
                gmmpInstalled = installed,
                running = running,
                enabled = options.enabled,
                runtimeMode = runtime.mode
            )
        )
'''
new_runtime = '''        val runtimeTone = StatusHealthPolicy.runtime(
            serviceAvailable = serviceAvailable,
            gmmpInstalled = installed,
            running = running,
            enabled = options.enabled,
            runtimeMode = runtime.mode
        )
        applyStatusTone(runtimeStatusText, runtimeTone)
'''
main = replace_once(main, old_runtime, new_runtime, "runtime tone")

old_compat = '''        applyStatusTone(
            compatibilityText,
            StatusHealthPolicy.compatibility(compatibilityState)
        )
'''
new_compat = '''        val compatibilityTone = StatusHealthPolicy.compatibility(compatibilityState)
        applyStatusTone(compatibilityText, compatibilityTone)

        val overallTone = StatusHealthPolicy.overall(
            gmmpTone,
            frameworkTone,
            runtimeTone,
            compatibilityTone
        )
        applyStatusTone(statusSummarySection, overallTone)
'''
main = replace_once(main, old_compat, new_compat, "compatibility/overall tone")

main_path.write_text(main)

queue_path = Path("app/src/main/java/io/github/alagga/gonesmart/QueueFlipController.kt")
queue = queue_path.read_text()
old_queue = '''            val positionWriter = positionWriterObserver.binding(autoDj)
                ?: error(
                    "GMMP native current-position writer has not been " +
                        "passively observed yet"
                )
            return GmmpQueueMutationBridge(
                autoDj,
                positionWriter
            ).reverseQueue()
'''
new_queue = '''            // First let the mutation bridge resolve a writable boundary on
            // the same native state host that already proves the read signal.
            // A passively observed MusicService writer is an additional safe
            // fallback, not a prerequisite for Queue Flip to run.
            val positionWriter = positionWriterObserver.binding(autoDj)
            return GmmpQueueMutationBridge(
                autoDj,
                positionWriter
            ).reverseQueue()
'''
queue = replace_once(queue, old_queue, new_queue, "queue flip premature writer gate")
queue_path.write_text(queue)
