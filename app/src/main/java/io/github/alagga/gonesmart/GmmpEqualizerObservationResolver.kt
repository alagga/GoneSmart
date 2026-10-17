package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves GMMP's live Equalizer state owner by semantic shape rather than by
 * obfuscated R8 names. The known 4.2.1 owner is only a validated fast path.
 *
 * The resolved methods are observation-only setters: GoneSmart hooks natural
 * GMMP calls and never invokes these methods during discovery.
 */
object GmmpEqualizerObservationResolver {
    private const val TESTED_FAST_PATH = "r61"

    data class Mapping(
        val owner: Class<*>,
        val preSetter: Method,
        val bandSetter: Method,
        val enabledSetter: Method,
        val score: Int,
        val source: String
    )

    fun resolveFastPath(classLoader: ClassLoader): Mapping? {
        val candidate = runCatching { classLoader.loadClass(TESTED_FAST_PATH) }.getOrNull() ?: return null
        return classify(candidate)?.copy(source = "validated-fast-path")
    }

    /**
     * Pick a unique highest-confidence candidate from a bounded externally
     * supplied class set. Equal top scores fail closed.
     */
    fun resolveDynamic(classes: Sequence<Class<*>>): Mapping? {
        val candidates = classes
            .mapNotNull(::classify)
            .toList()
        if (candidates.isEmpty()) return null

        val bestScore = candidates.maxOf { it.score }
        val best = candidates.filter { it.score == bestScore }
        if (best.size != 1) return null
        return best.single().copy(source = "structural-fallback")
    }

    internal fun classify(candidate: Class<*>): Mapping? {
        val methods = candidate.declaredMethods.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

        val pre = methods.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Double::class.javaPrimitiveType))
        }
        val band = methods.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                )
        }
        val enabled = methods.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }
        if (pre.size != 1 || band.size != 1 || enabled.size != 1) return null

        val fields = candidate.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        if (fields.count { it.type == DoubleArray::class.java } != 1) return null

        var score = 4
        if (candidate.declaredConstructors.any { it.parameterCount == 0 }) score += 1
        if (fields.any { field ->
                field.type.name == "gonemad.gmmp.audioengine.DspManager" ||
                    (field.type.name.startsWith("gonemad.gmmp.audioengine.") &&
                        field.type.simpleName.contains("dsp", ignoreCase = true))
            }
        ) {
            score += 4
        }
        if (methods.any { it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }) score += 1
        if (methods.count {
                it.returnType == Double::class.javaPrimitiveType &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            } >= 1
        ) {
            score += 1
        }

        // Structural fallback must have more than the bare setter signature
        // triple. This keeps unrelated utility/model classes from qualifying.
        if (score < 6) return null

        return Mapping(
            owner = candidate,
            preSetter = pre.single().apply { isAccessible = true },
            bandSetter = band.single().apply { isAccessible = true },
            enabledSetter = enabled.single().apply { isAccessible = true },
            score = score,
            source = "candidate"
        )
    }
}
