package io.github.alagga.gonesmart

/** Pure UI policy for complete audio-profile cards. */
object AudioProfileControlPolicy {
    fun masterEnabledFieldId(group: AudioProfileGroup): String? {
        if (group.fields.size <= 1) return null
        val first = group.fields.first()
        return first.id.takeIf {
            first.valueType == AudioProfileValueType.BOOLEAN && first.id.endsWith(".enabled")
        }
    }

    fun isFieldInteractive(
        group: AudioProfileGroup,
        fieldId: String,
        overridden: Boolean,
        masterEnabled: Boolean
    ): Boolean {
        if (!overridden) return false
        val master = masterEnabledFieldId(group) ?: return true
        return fieldId == master || masterEnabled
    }
}
