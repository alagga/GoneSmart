package io.github.alagga.gonesmart

/**
 * Android resource IDs have a non-zero package byte. View.generateViewId()
 * deliberately returns IDs with a zero package byte. Calling
 * Resources.getResourceEntryName() for those IDs logs "Invalid ID" before
 * throwing, so reject them before touching Resources.
 */
internal object NativeResourceIdPolicy {
    fun canResolveEntryName(id: Int): Boolean =
        id > 0 && (id ushr 24) != 0
}
