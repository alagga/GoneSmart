package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Promotes a naturally observed one-Int/void command to a writable queue
 * position boundary only when the SAME runtime host exposes an unambiguous
 * read signal with the same value after the native call.
 *
 * This intentionally does not infer semantics from a matching integer value
 * on another object. The writer must be the unique directly-owned Int->void
 * setter on the observed host and the readback must live on that same host.
 */
internal object NativeQueueObservedStateBinding {
    sealed interface Reader {
        val description: String
        fun read(host: Any): Int?
    }

    data class MethodReader(private val method: Method) : Reader {
        override val description: String =
            method.declaringClass.name + ".method:" + method.name

        override fun read(host: Any): Int? = runCatching {
            method.isAccessible = true
            (method.invoke(host) as? Number)?.toInt()
        }.getOrNull()
    }

    data class FieldReader(private val field: Field) : Reader {
        override val description: String =
            field.declaringClass.name + ".field:" + field.name

        override fun read(host: Any): Int? = runCatching {
            field.isAccessible = true
            (field.get(host) as? Number)?.toInt()
        }.getOrNull()
    }

    data class Match(
        val reader: Reader,
        val writer: Method
    )

    fun resolve(
        host: Any,
        observedWriter: Method,
        observedValue: Int
    ): Match? {
        val analysis = NativeQueueStateAccessorPolicy.analyze(
            host = host,
            currentValue = observedValue
        )

        val directWriter = analysis.directSetters.singleOrNull()
            ?: return null
        if (!sameMethod(directWriter, observedWriter)) return null

        val directGetters = analysis.matchingGetters.filter {
            it.declaringClass == host.javaClass
        }
        val getter = directGetters.singleOrNull()
            ?: analysis.matchingGetters.singleOrNull()
        if (getter != null) {
            getter.isAccessible = true
            return Match(MethodReader(getter), directWriter)
        }

        val directFields = analysis.matchingFields.filter {
            it.declaringClass == host.javaClass
        }
        val field = directFields.singleOrNull()
            ?: analysis.matchingFields.singleOrNull()
            ?: return null
        field.isAccessible = true
        return Match(FieldReader(field), directWriter)
    }

    private fun sameMethod(left: Method, right: Method): Boolean =
        left.declaringClass == right.declaringClass &&
            left.name == right.name &&
            left.returnType == right.returnType &&
            left.parameterTypes.contentEquals(right.parameterTypes)
}
