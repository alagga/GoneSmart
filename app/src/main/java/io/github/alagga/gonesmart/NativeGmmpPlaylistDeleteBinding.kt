package io.github.alagga.gonesmart

import android.content.Context
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/**
 * Resolves GMMP's ORIGINAL playlist-delete entry point from its
 * Context + List<T> contract.
 *
 * On 4.2.1 T is the already-bound native playlist model (yn3). Do not
 * manufacture a stale 4.2.0 File wrapper when GMMP has already supplied the
 * exact native model for the visible row. Legacy File wrappers remain only
 * for older builds where the delete contract itself proves that shape.
 */
internal class NativeGmmpPlaylistDeleteBinding private constructor(
    val deleteMethod: Method,
    private val elementType: Class<*>?,
    private val legacyConstructor: Constructor<*>?
) {
    fun accepts(model: Any): Boolean =
        elementType == null || elementType.isInstance(model)

    fun nativeModel(model: Any): Any {
        require(accepts(model)) {
            "GMMP playlist delete model mismatch: " +
                model.javaClass.name + " -> " +
                (elementType?.name ?: "<erased>")
        }
        return model
    }

    fun wrapLegacy(file: File): Any {
        if (elementType == File::class.java) return file
        val ctor = legacyConstructor
            ?: error("GMMP legacy playlist wrapper unavailable")
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
            val element: Class<*>?
        )

        fun resolve(
            loader: ClassLoader,
            sampleModel: Any? = null
        ): NativeGmmpPlaylistDeleteBinding {
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
            require(methods.isNotEmpty()) {
                "GMMP playlist delete method unavailable"
            }

            val candidates = methods.map {
                Candidate(it, genericListElementClass(it, 1))
            }

            val chosen = if (sampleModel != null) {
                val modelClass = sampleModel.javaClass
                val typed = candidates.filter { candidate ->
                    candidate.element?.let {
                        it.isAssignableFrom(modelClass) ||
                            modelClass.isAssignableFrom(it)
                    } == true
                }
                typed.singleOrNull()
                    ?: typed.singleOrNull { it.method.name == "b" }
                    ?: candidates.singleOrNull()
                    ?: error(
                        "GMMP playlist delete method is not unique for " +
                            modelClass.name + ": " +
                            candidates.joinToString(",") {
                                it.method.name + "<" +
                                    (it.element?.name ?: "erased") + ">"
                            }
                    )
            } else {
                candidates.singleOrNull { it.method.name == "b" }
                    ?: candidates.singleOrNull()
                    ?: run {
                        // 4.2.0 fallback: identify the List<T> whose T has
                        // the native File[,Long] constructor.
                        val legacy = candidates.filter {
                            it.element?.let(::legacyFactory) != null
                        }
                        legacy.singleOrNull()
                            ?: error(
                                "GMMP playlist delete method is not structurally unique: " +
                                    candidates.joinToString(",") {
                                        it.method.name + "<" +
                                            (it.element?.name ?: "erased") + ">"
                                    }
                            )
                    }
            }

            chosen.method.isAccessible = true
            val ctor = chosen.element?.let(::legacyFactory)
            ctor?.isAccessible = true
            return NativeGmmpPlaylistDeleteBinding(
                chosen.method,
                chosen.element,
                ctor
            )
        }

        private fun legacyFactory(
            type: Class<*>
        ): Constructor<*>? {
            if (type == File::class.java) return null
            val two = type.declaredConstructors.filter {
                val p = it.parameterTypes
                p.size == 2 &&
                    File::class.java.isAssignableFrom(p[0]) &&
                    (p[1] == java.lang.Long::class.java ||
                        p[1] == java.lang.Long.TYPE)
            }
            val one = type.declaredConstructors.filter {
                val p = it.parameterTypes
                p.size == 1 &&
                    File::class.java.isAssignableFrom(p[0])
            }
            return when {
                two.size == 1 -> two.single()
                one.size == 1 -> one.single()
                else -> null
            }
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
