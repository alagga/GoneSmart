package io.github.alagga.gonesmart

import android.content.Context
import android.util.Log

/**
 * Passive GMMP 4.2.0 move/rename discovery. Inspect only stable resource
 * IDs and declared signatures; NEVER invoke a candidate file/DB writer.
 * An observed menu icon alone does not establish native transactional safety.
 */
internal class NativeGmmpMoveDiscovery(private val loader: ClassLoader) {
    private var reported = false
    fun reportOnce(context: Context) {
        if (reported) return
        reported = true
        val res = context.resources
        val ids = listOf(
            "actionMenuMove", "menuContextMove", "menuMove",
            "actionMenuRename", "menuContextRename",
            "menu_gm_action_files", "menu_gm_context_files",
            "menu_gm_context_playlist_list"
        ).map { name ->
            val type = if (name.startsWith("menu_gm_")) "menu" else "id"
            name + "=" + (res.getIdentifier(name, type, context.packageName) != 0)
        }
        val signatures = listOf("kg1", "yn3", "zp3", "x6", "hp3", "t6")
            .map { name ->
                val methods = runCatching {
                    loader.loadClass(name).declaredMethods
                        .filter {
                            // Diagnostic names and type signatures only,
                            // never arguments, library paths or invocation.
                            it.parameterCount <= 3 &&
                                (name == "kg1" || name == "yn3" ||
                                    it.name.contains("move", true) ||
                                    it.name.contains("rename", true))
                        }
                        .sortedBy { it.name }
                        .take(18)
                        .joinToString(";") { method ->
                            method.name + "(" +
                                method.parameterTypes.joinToString(",") {
                                    it.simpleName
                                } + ")"
                        }
                }.getOrDefault("unavailable")
                "$name:$methods"
            }
        Log.i(
            "GoneSmartPlaylist",
            "FOLDER MOVE DISCOVERY | read-only GMMP IDs " +
                ids.joinToString(";")
        )
        Log.i(
            "GoneSmartPlaylist",
            "FOLDER MOVE DISCOVERY | read-only signatures " +
                signatures.joinToString(" | ")
        )
    }
}
