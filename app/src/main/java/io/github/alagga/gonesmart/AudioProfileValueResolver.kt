package io.github.alagga.gonesmart

/** Pure normalization/fallback policy shared by audio-profile runtime readers. */
object AudioProfileValueResolver {
    fun resolve(fieldId: String, primary: String?, fallback: String?): String? {
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return null
        return primary?.let(field::normalize) ?: fallback?.let(field::normalize)
    }
}
