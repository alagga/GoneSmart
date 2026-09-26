package io.github.alagga.gonesmart

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * GMMP's AndroidX RecyclerView can be loaded by its own APK classloader,
 * while GoneSmart bundles the SAME library version in a different loader.
 * Never treat a failed Kotlin "as? RecyclerView" as an absent native
 * adapter or ItemAnimator. Read the public host API reflectively.
 *
 * This is strictly read-only: no native adapter/holder changes or hooks.
 */
internal object NativeRecyclerBridge {
    data class Snapshot(
        val adapter: Any?,
        val animator: Any?,
        val edgeEffectFactory: Any?,
        val moduleClassMatch: Boolean
    )

    fun snapshot(list: ViewGroup): Snapshot {
        val own = list as? RecyclerView
        return Snapshot(
            adapter = own?.adapter ?: invokeNoArg(list, "getAdapter"),
            animator = own?.itemAnimator ?: invokeNoArg(list, "getItemAnimator"),
            edgeEffectFactory = own?.edgeEffectFactory
                ?: invokeNoArg(list, "getEdgeEffectFactory"),
            moduleClassMatch = own != null
        )
    }

    fun childAdapterPosition(list: ViewGroup, child: View): Int {
        val own = list as? RecyclerView
        if (own != null) return own.getChildAdapterPosition(child)
        return runCatching {
            list.javaClass.getMethod(
                "getChildAdapterPosition", View::class.java
            ).invoke(list, child) as? Int
        }.getOrNull() ?: -1
    }

    fun duration(animator: Any?, getterName: String): Long? {
        if (animator == null) return null
        return (invokeNoArg(animator, getterName) as? Number)
            ?.toLong()?.takeIf { it >= 0 }
    }

    private fun invokeNoArg(target: Any, method: String): Any? =
        runCatching {
            target.javaClass.getMethod(method).invoke(target)
        }.getOrNull()
}
