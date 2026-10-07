package io.github.alagga.gonesmart

/**
 * Native Smart rows may be masked/suppressed only while their list fragment is
 * actually the front GMMP surface. A RecyclerView can stay attached behind a
 * Smart-Playlist detail fragment; hiding it there causes black/missing rows
 * when that detail is popped.
 */
internal object SmartFolderAttachPolicy {
    fun mayMaskNativeRows(isFrontSurface: Boolean): Boolean =
        isFrontSurface

    fun mayControlNativeRootSubmission(isFrontSurface: Boolean): Boolean =
        isFrontSurface
}
