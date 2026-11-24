package io.github.alagga.gonesmart

import java.lang.reflect.Modifier

/**
 * The GMMP 4.2.1 normal-Playlist RecyclerView's runtime-correlated ao3
 * model is yn3 (long ID, two string fields), NOT the unrelated xn3
 * PlayingInfoUpdater. Called only on models already obtained from the
 * verified original adapter row/source.
 */
internal object Gmmp421PlaylistModelPolicy {
    fun isOriginalPlaylistRow(type: Class<*>): Boolean {
        if (type.simpleName != "yn3") return false
        val fields = type.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
        val idFields = fields.count { it.type == java.lang.Long.TYPE }
        val textFields = fields.count { it.type == String::class.java }
        val hasId = type.declaredMethods.any {
            it.name == "getId" && it.parameterCount == 0 &&
                it.returnType == java.lang.Long.TYPE
        }
        return idFields == 1 && textFields == 2 && hasId
    }
}
