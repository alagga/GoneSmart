package io.github.alagga.gonesmart

/**
 * Chooses the title already stored in GMMP's native playlist models by
 * comparing model text fields with the native titles on bound playlist rows.
 *
 * File paths remain playlist identities; titles are presentation only.
 * When the evidence is ambiguous, prefer an observed native row title and
 * otherwise leave the current filename fallback rather than invent a name.
 */
internal object NativePlaylistTitleResolver {
    data class Model(
        val path: String,
        val textFields: Map<String, String>
    )

    data class FieldScore(
        val field: String,
        val matches: Int,
        val mismatches: Int,
        val distinctiveMatches: Int,
        val coverage: Int
    )

    data class Result(
        val names: Map<String, String>,
        val chosenField: String?,
        val observedLabels: Int,
        val nativeTitles: Int,
        val filenameFallbacks: Int,
        val candidates: List<FieldScore>
    )

    fun resolve(
        models: Collection<Model>,
        observedTitles: Map<String, String>
    ): Result {
        val unique = models.associateBy { it.path }
        val observed = observedTitles.mapValues { it.value.trim() }
            .filterValues { it.isNotBlank() }
        val keys = unique.values.flatMap { it.textFields.keys }.distinct()
        val candidates = keys.map { key ->
            var matches = 0
            var mismatches = 0
            var distinctive = 0
            var coverage = 0
            for (model in unique.values) {
                val value = clean(model.textFields[key])
                if (value == null || looksLikePath(value)) continue
                coverage++
                val rendered = observed[model.path] ?: continue
                if (same(value, rendered)) {
                    matches++
                    if (!same(rendered, model.path.substringAfterLast('/')) &&
                        !same(rendered, model.path.substringAfterLast('/')
                            .substringBeforeLast('.'))
                    ) distinctive++
                } else {
                    mismatches++
                }
            }
            FieldScore(key, matches, mismatches, distinctive, coverage)
        }.sortedWith(
            compareByDescending<FieldScore> {
                it.matches * 4 + it.distinctiveMatches * 3 - it.mismatches * 6
            }.thenByDescending { it.distinctiveMatches }
                .thenByDescending { it.coverage }
                .thenBy { it.field }
        )

        val observedCount = unique.keys.count { observed.containsKey(it) }
        val requiredCoverage = (unique.size * 65 + 99) / 100
        val field = candidates.firstOrNull { candidate ->
            candidate.coverage >= requiredCoverage &&
                candidate.matches >= if (observedCount >= 2) 2 else 1 &&
                candidate.matches * 100 >=
                    (candidate.matches + candidate.mismatches) * 80
        }?.field ?: if (observedCount == 0) {
            candidates.firstOrNull { candidate ->
                candidate.coverage >= unique.size * 9 / 10 &&
                    candidate.field.substringAfterLast('.')
                        .lowercase() in setOf(
                            "name", "title", "displayname", "playlistname"
                        )
            }?.field
        } else null

        var nativeCount = 0
        val titles = linkedMapOf<String, String>()
        for (model in unique.values) {
            val observedTitle = clean(observed[model.path])
            val fieldTitle = field?.let {
                clean(model.textFields[it])?.takeUnless(::looksLikePath)
            }
            val chosen = observedTitle ?: fieldTitle
            if (chosen != null) {
                titles[model.path] = chosen
                nativeCount++
            }
        }
        return Result(
            names = titles,
            chosenField = field,
            observedLabels = observedCount,
            nativeTitles = nativeCount,
            filenameFallbacks = unique.size - nativeCount,
            candidates = candidates.take(8)
        )
    }

    private fun clean(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotBlank() }

    private fun same(left: String, right: String): Boolean =
        left.trim().replace(Regex("\\s+"), " ")
            .equals(right.trim().replace(Regex("\\s+"), " "), true)

    private fun looksLikePath(raw: String): Boolean =
        raw.startsWith("/") || raw.startsWith("\\") || raw.contains("://")
}
