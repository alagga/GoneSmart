package io.github.alagga.gonesmart

import android.content.Context
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/**
 * Resolves GMMP's original playlist-delete entry point and the exact native
 * list element type as one semantic unit. The element type is derived from
 * py0's generic List<T> contract first; "th1" remains only a 4.2.0 fallback.
 */
internal class NativeGmmpPlaylistDeleteBinding private constructor(
    val deleteMethod: Method,
    private val wrapperType: Class<*>,
    private val wrapperConstructor: Constructor<*>?
) {
    fun wrap(file: File): Any {
        if (wrapperType == File::class.java) return file
        val ctor = wrapperConstructor
            ?: error("GMMP playlist wrapper constructor unavailable")
        val p = ctor.parameterTypes
        return when (p.size) {
            1 -> ctor.newInstance(file)
            2 -> ctor.newInstance(
                file,
                if (p[1] == java.lang.Long.TYPE) 0L else null
            )
            else -> error("Unsupported GMMP playlist wrapper shape")
        }
    }

    companion object {
        private data class Candidate(
            val method: Method,
            val wrapper: Class<*>,
            val constructor: Constructor<*>?,
            val score: Int
        )

        fun resolve(loader: ClassLoader): NativeGmmpPlaylistDeleteBinding {
            val deleteType = loader.loadClass("py0")
            val methods = deleteType.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 2 &&
                    Context::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    java.util.List::class.java.isAssignableFrom(
                        it.parameterTypes[1]
                    )
            }

            val typed = methods.mapNotNull { method ->
                val wrapper = genericListElementClass(method, 1)
                    ?: return@mapNotNull null
                val factory = wrapperFactory(wrapper)
                    ?: return@mapNotNull null
                Candidate(
                    method = method,
                    wrapper = wrapper,
                    constructor = factory.first,
                    score = factory.second
                )
            }

            val exactTyped = typed.filter { it.method.name == "b" }
            val chosen = when {
                exactTyped.size == 1 -> exactTyped.single()
                else -> {
                    val bestScore = typed.maxOfOrNull { it.score }
                    val best = typed.filter { it.score == bestScore }
                    best.singleOrNull()
                }
            } ?: run {
                val legacy = loader.loadClass("th1")
                val factory = wrapperFactory(legacy)
                    ?: error("GMMP legacy playlist wrapper unavailable")
                val delete = methods.singleOrNull { it.name == "b" }
                    ?: methods.singleOrNull()
                    ?: error(
                        "GMMP playlist delete method is not structurally unique: " +
                            methods.joinToString(",") { it.name }
                    )
                Candidate(
                    delete,
                    legacy,
                    factory.first,
                    factory.second
                )
            }

            chosen.method.isAccessible = true
            chosen.constructor?.isAccessible = true
            return NativeGmmpPlaylistDeleteBinding(
                chosen.method,
                chosen.wrapper,
                chosen.constructor
            )
        }

        private fun wrapperFactory(
            wrapper: Class<*>
        ): Pair<Constructor<*>?, Int>? {
            if (wrapper == File::class.java) return null to 0
            val one = wrapper.declaredConstructors.filter {
                val p = it.parameterTypes
                p.size == 1 &&
                    File::class.java.isAssignableFrom(p[0])
            }
            val two = wrapper.declaredConstructors.filter {
                val p = it.parameterTypes
                p.size == 2 &&
                    File::class.java.isAssignableFrom(p[0]) &&
                    (p[1] == java.lang.Long::class.java ||
                        p[1] == java.lang.Long.TYPE)
            }
            val ctor = when {
                two.size == 1 -> two.single()
                one.size == 1 -> one.single()
                else -> null
            } ?: return null
            ctor.isAccessible = true
            return ctor to if (ctor.parameterCount == 2) 2 else 1
        }

        private fun genericListElementClass(
            method: Method,
            parameterIndex: Int
        ): Class<*>? {
            val type = method.genericParameterTypes
                .getOrNull(parameterIndex) as? ParameterizedType
                ?: return null
            val argument = type.actualTypeArguments.singleOrNull()
                ?: return null
            return when (argument) {
                is Class<*> -> argument
                is ParameterizedType -> argument.rawType as? Class<*>
                else -> null
            }
        }
    }
}
