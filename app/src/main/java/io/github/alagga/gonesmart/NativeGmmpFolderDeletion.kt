package io.github.alagga.gonesmart

import android.content.Context
import android.util.Log
import java.io.File

/**
 * GMMP 4.2.0 originals, verified from supplied APK:
 *  - yn3.n native Playlists bulk Delete (actionMenuDelete 0x7f090036)
 *    and kg1.n native Files bulk Delete BOTH call py0.b(Context,List).
 *  - Both use original pn1/th1(File, Long?) file models, the original
 *    GC1 confirmation dialog and native WorkManager/GMDatabase pipeline.
 *
 * This class NEVER writes an M3U or deletes any file. Its input must have
 * passed FolderDeletePolicy's bounded canonical path/file audit.
 */
internal class NativeGmmpFolderDeletion(
    private val hostClassLoader: ClassLoader
) {
    fun confirmNativeDeletion(
        context: Context,
        nativeFilePaths: List<File>
    ): Boolean = runCatching {
        require(nativeFilePaths.isNotEmpty())
        require(nativeFilePaths.all { it.exists() })
        val itemClass = hostClassLoader.loadClass("th1")
        val itemCtor = itemClass.getDeclaredConstructor(
            File::class.java, java.lang.Long::class.java
        ).apply { isAccessible = true }
        val nativeFiles = ArrayList<Any>(nativeFilePaths.size)
        nativeFilePaths.forEach { file ->
            nativeFiles += itemCtor.newInstance(file, null)
        }
        val original = hostClassLoader.loadClass("py0")
            .getDeclaredMethod(
                "b",
                Context::class.java,
                java.util.List::class.java
            ).apply { isAccessible = true }
        original.invoke(null, context, nativeFiles)
        Log.i(
            "GoneSmartPlaylist",
            "FOLDER DELETE | original GMMP confirmation opened" +
                " | items=" + nativeFiles.size
        )
        true
    }.onFailure {
        Log.e(
            "GoneSmartPlaylist",
            "FOLDER DELETE | native confirmation unavailable; no files changed",
            it
        )
    }.getOrDefault(false)
}
