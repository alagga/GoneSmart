package io.github.alagga.gonesmart

internal object SmartFolderHeaderScrollPolicy {
    fun folderScrollOffset(
        folderHeight: Int,
        listPaddingTop: Int,
        firstChildTop: Int,
        firstAdapterPosition: Int
    ): Int {
        if (folderHeight <= 0) return 0
        if (firstAdapterPosition > 0) return folderHeight
        if (firstAdapterPosition < 0) return 0
        return (listPaddingTop - firstChildTop).coerceIn(0, folderHeight)
    }
}
