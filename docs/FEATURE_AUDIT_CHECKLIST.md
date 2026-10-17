# Feature acceptance audit checklist

Use this checklist before declaring a GoneSmart feature branch complete or ready for integration. It complements the feature-specific docs, `AGENTS.md`, and the GMMP compatibility playbook; it does not replace them.

## Required checks

1. **Behavior / scope** — the implemented behavior matches the accepted feature scope and known limitations are documented.
2. **GMMP compatibility** — GMMP-facing reads/hooks/writers follow the native-first and semantic/dynamic compatibility rules. Tested obfuscated names may be fast paths, not semantic identity.
3. **Safety / failure behavior** — ambiguous state-changing paths fail closed, unrelated feature families remain independent, and fallback behavior is explicit.
4. **Performance / logging** — no discovery-era hot-path cost, excessive polling, repeated full scans, or log spam remains on an accepted version.
5. **Localization / I18N — mandatory for every feature audit** — explicitly determine whether the feature adds or changes any user-visible text inside GMMP. Follow `docs/GONESMART_GMMP_I18N.md`: prefer the installed GMMP application's own localized resources, use the centralized GoneSmart GMMP-string table only for genuinely missing host phrases, and keep the GoneSmart companion app English-only. If the feature adds no GMMP-facing strings, record the audit result as **no new translations required** instead of silently skipping localization review.
6. **Documentation** — README/help/FAQ/feature docs/roadmap/release-facing copy agree with the accepted behavior and shipping state.
7. **Validation** — relevant tests pass and the normal exact-head Build workflow is green before the branch is treated as integration-ready.

## Localization audit outcome template

For each feature closeout, record one of these outcomes in the feature/audit documentation:

- **New GMMP-facing wording:** list the semantic phrases, which native GMMP resources are reused, and any genuinely missing phrases handled by `GoneSmartGmmpStrings.kt`.
- **Changed GMMP-facing wording:** re-run the same native-first/resource/locale review for the changed text.
- **No new GMMP-facing wording:** state **no new translations required**. Companion-only settings/help text remains English by design.

Translation coverage tests verify source coverage, not linguistic quality. New custom translations still require native-speaker review where practical, as described in `docs/GONESMART_GMMP_I18N.md`.
