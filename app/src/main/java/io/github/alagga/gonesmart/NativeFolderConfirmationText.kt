package io.github.alagga.gonesmart

/**
 * Only the displayed label of GMMP's ORIGINAL delete confirmation changes.
 * Native file selection, callbacks, warnings and deletion are untouched.
 */
internal object NativeFolderConfirmationText {
    fun withFolderPath(
        originalText: String,
        genericFilesLabel: String?,
        canonicalFolderPath: String
    ): String {
        if (canonicalFolderPath.isBlank() ||
            originalText.contains(canonicalFolderPath)
        ) return originalText
        if (originalText.trim().isEmpty() ||
            (!genericFilesLabel.isNullOrBlank() &&
                originalText.trim() == genericFilesLabel.trim())
        ) return canonicalFolderPath
        // Unknown/current-locale native text may be an important warning.
        return originalText + "\n" + canonicalFolderPath
    }
}
