from pathlib import Path

controller = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
text = controller.read_text()
old = '''            if (!targetUsable) {
                // A current-row change with no accompanying list rebuild is a
                // genuine retarget/manual playback change. Do not ever isolate
                // that unrelated row. During a Smart Playlist rebuild the
                // queue size changes and the original selected track remains
                // uniquely identifiable, which is handled below.
                stableSnapshot = null
                stableAt = now
                continue
            }
'''
new = '''            if (!targetUsable) {
                val sinceDetection = now - detectedAt
                val mayRetarget =
                    TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                        source = request.source,
                        sinceDetectionMs = sinceDetection,
                        requiredGuardMs = completionGuard,
                        selectedTrackOccurrences = selectedOccurrences,
                        currentStillSelected = currentStillSelected,
                        playbackChangedFromBefore = playbackChanged
                    )
                if (mayRetarget) {
                    Log.i(
                        TAG,
                        "MIX PLAY RETARGETED | reason=transient-current-disappeared" +
                            " | oldEntry=" + (target.queueEntryId ?: -1L) +
                            " | oldTrack=" + target.trackId +
                            " | newEntry=" + (identity.queueEntryId ?: -1L) +
                            " | newTrack=" + identity.trackId +
                            " | sinceDetectionMs=" + sinceDetection
                    )
                    targetIdentity = identity
                    detectedAt = now
                    detectedQueueSize = current.ids.size
                    stableSnapshot = current
                    stableAt = now
                    continue
                }

                // Outside the guarded native Play settling window, a current
                // row change is treated as a competing/manual playback change.
                // Never retarget there and never isolate an unrelated row.
                stableSnapshot = null
                stableAt = now
                continue
            }
'''
if old not in text:
    raise SystemExit('TrackMixController target block not found')
controller.write_text(text.replace(old, new, 1))

policy = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixQueueSettlingPolicy.kt')
text = policy.read_text()
anchor = '''    fun selectedTargetStillUsable(
        selectedTrackOccurrences: Int,
        currentStillSelected: Boolean,
        detectedQueueSize: Int,
        currentQueueSize: Int
    ): Boolean =
        selectedTrackOccurrences == 1 &&
            (
                currentStillSelected ||
                    currentQueueSize != detectedQueueSize
            )

'''
addition = anchor + '''    /**
     * GMMP's generic track menu is also used by Smart Playlists. During native
     * Play the first observed CURRENT can be a short-lived intermediate row.
     * If that provisional row disappears entirely before the completion guard
     * has elapsed, the independently observed new CURRENT may replace it.
     * After the guard, fail closed so a manual playback change is never
     * mistaken for the originally selected Track Mix seed.
     */
    fun shouldRetargetTransientTarget(
        source: String,
        sinceDetectionMs: Long,
        requiredGuardMs: Long,
        selectedTrackOccurrences: Int,
        currentStillSelected: Boolean,
        playbackChangedFromBefore: Boolean
    ): Boolean =
        source == "menu_gm_context_track" &&
            requiredGuardMs > 0L &&
            sinceDetectionMs < requiredGuardMs &&
            selectedTrackOccurrences == 0 &&
            !currentStillSelected &&
            playbackChangedFromBefore

'''
if anchor not in text:
    raise SystemExit('settling policy anchor not found')
policy.write_text(text.replace(anchor, addition, 1))

tests = Path('app/src/test/java/io/github/alagga/gonesmart/TrackMixQueueSettlingPolicyTest.kt')
text = tests.read_text()
closing = '\n}\n'
if not text.endswith(closing):
    raise SystemExit('settling policy test closing not found')
extra = '''
    @Test
    fun vanishedProvisionalSmartPlaylistCurrentMayBeRetargetedInsideGuard() {
        assertTrue(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_track",
                sinceDetectionMs = 400L,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 0,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
    }

    @Test
    fun retargetingFailsClosedOutsideSmartPlaylistSettlingWindow() {
        assertFalse(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_track",
                sinceDetectionMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 0,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
        assertFalse(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_queue",
                sinceDetectionMs = 400L,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 0,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
        assertFalse(
            TrackMixQueueSettlingPolicy.shouldRetargetTransientTarget(
                source = "menu_gm_context_track",
                sinceDetectionMs = 400L,
                requiredGuardMs = TrackMixQueueSettlingPolicy.COMPLETION_GUARD_MS,
                selectedTrackOccurrences = 1,
                currentStillSelected = false,
                playbackChangedFromBefore = true
            )
        )
    }
'''
tests.write_text(text[:-2] + extra + '}\n')

agents = Path('AGENTS.md')
text = agents.read_text()
rule = '- Track Mix target identity is provisional during the guarded native Play settle window for the generic track menu: if the first observed CURRENT disappears completely before the guard finishes and a different playback identity is independently observed, retarget to that live CURRENT and restart settling. After the guard, fail closed; never follow unrelated/manual playback.\n'
if rule not in text:
    agents.write_text(text + ('' if text.endswith('\n') else '\n') + rule)

playbook = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
text = playbook.read_text()
note = '''
### Track Mix provisional CURRENT during Smart Playlist Play
The generic track context menu can expose a transient CURRENT immediately after native Play. Treat that first identity as provisional until the existing Smart-Playlist completion guard finishes. If the candidate disappears completely during that guarded window and a different playback identity is independently observed, retarget to the live CURRENT and restart candidate settling. Once outside the guard, fail closed rather than following a competing/manual playback change.
'''
if '### Track Mix provisional CURRENT during Smart Playlist Play' not in text:
    playbook.write_text(text + note)
