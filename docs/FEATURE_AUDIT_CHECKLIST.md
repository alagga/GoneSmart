# Feature acceptance audit checklist

Use this checklist before declaring a GoneSmart feature branch complete or ready for integration. It complements the feature-specific docs, `AGENTS.md`, and the GMMP compatibility playbook; it does not replace them.

## Required checks

1. **Behavior / scope** — the implemented behavior matches the accepted feature scope and known limitations are documented.
2. **GMMP compatibility** — GMMP-facing reads/hooks/writers follow the native-first and semantic/dynamic compatibility rules. Tested obfuscated names may be fast paths, not semantic identity.
3. **Safety / failure behavior** — ambiguous state-changing paths fail closed, unrelated feature families remain independent, and fallback behavior is explicit.
4. **Performance / Logcat diagnostics** — no discovery-era hot-path cost, excessive polling, repeated full scans, or normal-runtime log spam remains on an accepted version. Keep concise summaries, decisions, failures and useful latency metrics; remove or explicitly gate per-candidate dumps and broad inventories.
5. **GoneSmart Logs / support diagnostics — mandatory for every feature audit** — review the companion app Logs surface and every new/changed runtime event. The Logs tab is a bounded, copyable high-signal support timeline: record meaningful state changes, verified user actions, fallbacks/degraded states, feature-unavailable conditions and failures with enough context to identify the broad failure class. Do not append routine per-track/per-refill success noise or low-level discovery detail. Re-check category counts, duplicate suppression, retention bounds, Copy/Clear behavior, Help text and the displayed Logcat-filter guidance whenever logging or feature tags change. Detailed developer diagnostics belong in Logcat; the filter hint must list the real current GoneSmart tag family.
6. **Code hygiene / cleanup — mandatory for every feature audit** — remove dead/obsolete feature code, temporary probes, helper workflows/scripts, debug-only flags/dumps, stale comments, duplicate implementations and compatibility scaffolding that is no longer needed on the accepted path. Keep future-compatibility machinery only when it is deliberately gated, cheap while inactive and documented. Re-check imports/constants/helpers after cleanup.
7. **Localization / I18N — mandatory for every feature audit** — explicitly determine whether the feature adds or changes any user-visible text inside GMMP. Follow `docs/GONESMART_GMMP_I18N.md`: prefer the installed GMMP application's own localized resources, use the centralized GoneSmart GMMP-string table only for genuinely missing host phrases, and keep the GoneSmart companion app English-only. If the feature adds no GMMP-facing strings, record the audit result as **no new translations required** instead of silently skipping localization review.
8. **Documentation** — README/help/FAQ/feature docs/roadmap/release-facing copy agree with the accepted behavior and shipping state.
9. **Validation** — relevant tests pass and the normal exact-head Build workflow is green before the branch is treated as integration-ready.

## Logging audit outcome template

For each feature closeout, explicitly record whether the feature changes support diagnostics:

- **New or changed high-level events:** list the user-visible action/state/fallback/failure that should appear in the companion Logs tab and verify it is categorized without hot-path spam.
- **New or changed Logcat diagnostics:** keep stable GoneSmart-family tags, concise normal summaries and gated deep diagnostics; update the Logs/Help filter guidance when the tag family changes.
- **No logging change required:** state that existing high-level events and detailed diagnostics already cover the feature's success/failure modes.

The companion Logs tab should normally be the first artifact requested for bug triage; filtered Logcat is the second-level diagnostic when the high-level timeline is insufficient.

## Localization audit outcome template

For each feature closeout, record one of these outcomes in the feature/audit documentation:

- **New GMMP-facing wording:** list the semantic phrases, which native GMMP resources are reused, and any genuinely missing phrases handled by `GoneSmartGmmpStrings.kt`.
- **Changed GMMP-facing wording:** re-run the same native-first/resource/locale review for the changed text.
- **No new GMMP-facing wording:** state **no new translations required**. Companion-only settings/help text remains English by design.

Translation coverage tests verify source coverage, not linguistic quality. New custom translations still require native-speaker review where practical, as described in `docs/GONESMART_GMMP_I18N.md`.
