package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Small semantic reflection helper for Playlist Link.
 *
 * Obfuscated GMMP names are accepted only as fast-path evidence. Every member
 * returned here must still satisfy the supplied structural predicate. Fallback
 * without a preferred name is allowed only when the structural candidate is
 * unique; ambiguity fails closed.
 */
internal object PlaylistBridgeReflectionResolver {
    fun loadClass(
        loader: ClassLoader,
        candidateNames: List<String>,
        description: String,
        predicate: (Class<*>) -> Boolean = { true }
    ): Class<*> {
        // GMMP 4.2.1 still contains historical-looking ho3/ko3 classes, but
        // they no longer own Playlist persistence. Resolve this boundary from
        // GMDatabase FIRST so a stale R8 name can never pre-empt the semantic
        // DAO contract merely because it happens to expose a List method.
        // E/lo3 are accepted-version evidence only; future renames still work
        // when the unique database accessor returns the Playlist DAO shape.
        if (description == "native Playlist DAO") {
            resolvePlaylistDaoFromDatabase(loader, predicate)?.let { return it }
        }

        val rejected = arrayListOf<String>()
        candidateNames.distinct().forEach { name ->
            val type = runCatching { loader.loadClass(name) }.getOrNull()
                ?: return@forEach
            if (runCatching { predicate(type) }.getOrDefault(false)) {
                return type
            }
            rejected += name
        }

        throw IllegalStateException(
            "Could not resolve $description from ${candidateNames.joinToString()}" +
                if (rejected.isEmpty()) "" else " (shape rejected: ${rejected.joinToString()})"
        )
    }

    private fun resolvePlaylistDaoFromDatabase(
        loader: ClassLoader,
        predicate: (Class<*>) -> Boolean
    ): Class<*>? {
        val database = runCatching {
            loader.loadClass("gonemad.gmmp.data.database.GMDatabase")
        }.getOrNull() ?: return null

        val candidates = methods(database)
            .asSequence()
            .filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType != java.lang.Void.TYPE &&
                    !it.returnType.isPrimitive
            }
            .filter { method ->
                runCatching {
                    predicate(method.returnType) &&
                        matchesPlaylistDaoType(method.returnType)
                }.getOrDefault(false)
            }
            .toList()

        candidates.filter { it.name == "E" }.singleOrNull()?.let {
            return it.returnType
        }
        return candidates.singleOrNull()?.returnType
    }

    /**
     * Playlist DAO contract shared by the accepted 4.2.0 ko3 and the
     * statically inspected 4.2.1 lo3 interface: a zero-arg list reader plus a
     * String lookup returning a Playlist row/model with at least two Strings
     * (stored path/URI and display name). Names such as G1/J1 are deliberately
     * not required.
     */
    internal fun matchesPlaylistDaoType(type: Class<*>): Boolean {
        val typeMethods = methods(type)
        val hasListReader = typeMethods.any { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                java.util.List::class.java.isAssignableFrom(method.returnType)
        }
        if (!hasListReader) return false

        return typeMethods.any { method ->
            if (
                java.lang.reflect.Modifier.isStatic(method.modifiers) ||
                !method.parameterTypes.contentEquals(arrayOf(String::class.java)) ||
                method.returnType == java.lang.Void.TYPE ||
                method.returnType.isPrimitive
            ) {
                return@any false
            }
            val model = method.returnType
            val instanceStrings = declaredFieldsSafely(model).count { field ->
                !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    field.type == String::class.java
            }
            instanceStrings >= 2 || model.declaredConstructors.any { ctor ->
                ctor.parameterTypes.count { it == String::class.java } >= 2
            }
        }
    }

    fun optionalClass(
        loader: ClassLoader,
        candidateNames: List<String>,
        predicate: (Class<*>) -> Boolean = { true }
    ): Class<*>? = candidateNames.distinct().firstNotNullOfOrNull { name ->
        runCatching { loader.loadClass(name) }.getOrNull()?.takeIf {
            runCatching { predicate(it) }.getOrDefault(false)
        }
    }

    fun methods(type: Class<*>): List<Method> {
        val out = linkedMapOf<String, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.forEach { method ->
                val key = method.name + "(" +
                    method.parameterTypes.joinToString(",") { it.name } + ")" +
                    ":" + method.returnType.name
                out.putIfAbsent(key, method)
            }
            current = current.superclass
        }
        type.methods.forEach { method ->
            val key = method.name + "(" +
                method.parameterTypes.joinToString(",") { it.name } + ")" +
                ":" + method.returnType.name
            out.putIfAbsent(key, method)
        }
        return out.values.toList()
    }

    internal fun declaredFieldsSafely(
        type: Class<*>,
        reader: (Class<*>) -> Array<Field> = { it.declaredFields }
    ): List<Field> = try {
        reader(type).toList()
    } catch (_: LinkageError) {
        emptyList()
    } catch (_: TypeNotPresentException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    fun fields(type: Class<*>): List<Field> {
        val out = linkedMapOf<String, Field>()
        var current: Class<*>? = type
        while (current != null) {
            val declaring = current
            declaredFieldsSafely(declaring).forEach { field ->
                out.putIfAbsent(declaring.name + "#" + field.name, field)
            }
            current = runCatching { declaring.superclass }.getOrNull()
        }
        return out.values.toList()
    }

    /**
     * Accepted-version aliases remain fast-path evidence only. The caller's
     * structural predicate is applied before this order is consulted, so an
     * R8 name can never make an incompatible field acceptable.
     *
     * Static comparison of GMMP 4.2.0 hs4 with 4.2.1 es4 shows the editor
     * state fields shifted by one slot: paths x->w, selected index y->x and
     * dirty v->u. The selected index needs the explicit alias because es4 has
     * several int fields and structural fallback alone is therefore
     * intentionally ambiguous.
     *
     * Static 4.2.1 bytecode/XML inspection also proves the Smart-Playlist
     * MatchAll flag moved from the historical model slot s to ts4.r, while a
     * nested Smart-rule group's MatchAll flag is gt4.o rather than the old p.
     * Both classes contain another boolean field, so structural fallback alone
     * is intentionally ambiguous and must fail closed without these aliases.
     */
    internal fun acceptedFieldNameOrder(
        typeName: String,
        preferredNames: List<String>
    ): List<String> {
        val aliases = when (typeName) {
            "es4" -> preferredNames.flatMap { name ->
                when (name) {
                    "x" -> listOf("w")
                    "y" -> listOf("x")
                    "v" -> listOf("u")
                    else -> emptyList()
                }
            }
            "ts4" -> preferredNames.flatMap { name ->
                if (name == "s") listOf("r") else emptyList()
            }
            "gt4" -> preferredNames.flatMap { name ->
                if (name == "p") listOf("o") else emptyList()
            }
            else -> emptyList()
        }
        return (preferredNames + aliases).distinct()
    }

    fun matchesDeclaredPresenterRuleAction(
        method: Method,
        presenterClass: Class<*>,
        baseRuleClass: Class<*>
    ): Boolean =
        method.declaringClass == presenterClass &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(baseRuleClass)) &&
            method.returnType == java.lang.Void.TYPE

    private fun containsSemanticIdentifier(
        text: String,
        expectedValue: String
    ): Boolean {
        val cleaned = text
            .replace('`', ' ')
            .replace('"', ' ')
            .replace('[', ' ')
            .replace(']', ' ')
        val token = Regex.escape(expectedValue)
        return Regex(
            "(?<![A-Za-z0-9_])(?:[A-Za-z0-9_]+\\.)?$token(?![A-Za-z0-9_])"
        ).containsMatchIn(cleaned)
    }

    /**
     * R8 may leave a query-field constant under a broad/opaque object whose
     * own toString no longer exposes the SQL column. Walk only that already
     * loaded in-memory value graph (bounded depth, no method calls) and look
     * for the exact native semantic identifier. No database/query action is
     * executed here.
     */
    private fun semanticRepresentations(value: Any): Set<String> {
        val out = linkedSetOf<String>()
        val seen = java.util.IdentityHashMap<Any, Boolean>()

        fun visit(candidate: Any?, depth: Int) {
            if (candidate == null || depth < 0) return
            when (candidate) {
                is CharSequence -> {
                    out += candidate.toString()
                    return
                }
                is Enum<*> -> {
                    out += candidate.name
                    out += candidate.toString()
                    return
                }
                is Number, is Boolean, is Char, is Class<*> -> return
                is Collection<*> -> {
                    candidate.take(64).forEach { visit(it, depth - 1) }
                    return
                }
                is Map<*, *> -> {
                    candidate.entries.take(64).forEach {
                        visit(it.key, depth - 1)
                        visit(it.value, depth - 1)
                    }
                    return
                }
            }

            if (candidate.javaClass.isArray) {
                val length = java.lang.reflect.Array.getLength(candidate)
                repeat(length.coerceAtMost(64)) { index ->
                    visit(java.lang.reflect.Array.get(candidate, index), depth - 1)
                }
                return
            }
            if (seen.put(candidate, true) != null) return

            runCatching { out += candidate.toString() }
            if (depth == 0) return

            var type: Class<*>? = candidate.javaClass
            while (type != null && type != Any::class.java) {
                val name = type.name
                if (
                    name.startsWith("java.") ||
                    name.startsWith("kotlin.") ||
                    name.startsWith("android.")
                ) {
                    break
                }
                declaredFieldsSafely(type)
                    .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
                        runCatching {
                            field.isAccessible = true
                            visit(field.get(candidate), depth - 1)
                        }
                    }
                type = type.superclass
            }
        }

        visit(value, 4)
        return out
    }

    fun matchesStaticFieldSemanticValue(
        field: Field,
        valueClass: Class<*>,
        expectedValue: String,
        valueProbe: ((Any) -> Any?)? = null
    ): Boolean {
        if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) {
            return false
        }
        return runCatching {
            field.isAccessible = true
            val value = field.get(null) ?: return@runCatching false
            if (!valueClass.isInstance(value)) return@runCatching false

            val direct = semanticRepresentations(value).any {
                containsSemanticIdentifier(it, expectedValue)
            }
            if (direct) return@runCatching true

            val probed = valueProbe?.invoke(value)
                ?: return@runCatching false
            semanticRepresentations(probed).any {
                containsSemanticIdentifier(it, expectedValue)
            }
        }.getOrDefault(false)
    }

    internal fun uniqueInstanceInObjectGraph(
        root: Any,
        valueClass: Class<*>,
        maxDepth: Int = 6
    ): Any? {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val matches = java.util.IdentityHashMap<Any, Boolean>()

        fun visit(candidate: Any?, depth: Int) {
            if (candidate == null || depth < 0) return
            if (valueClass.isInstance(candidate)) {
                matches[candidate] = true
                return
            }
            when (candidate) {
                is CharSequence, is Number, is Boolean, is Char,
                is Enum<*>, is Class<*> -> return
                is Collection<*> -> {
                    candidate.take(256).forEach { visit(it, depth - 1) }
                    return
                }
                is Map<*, *> -> {
                    candidate.entries.take(256).forEach { entry ->
                        visit(entry.key, depth - 1)
                        visit(entry.value, depth - 1)
                    }
                    return
                }
            }
            if (candidate.javaClass.isArray) {
                val length = java.lang.reflect.Array.getLength(candidate)
                repeat(length.coerceAtMost(256)) { index ->
                    visit(java.lang.reflect.Array.get(candidate, index), depth - 1)
                }
                return
            }
            if (seen.put(candidate, true) != null || depth == 0) return

            var type: Class<*>? = candidate.javaClass
            while (type != null && type != Any::class.java) {
                val name = type.name
                if (
                    name.startsWith("java.") ||
                    name.startsWith("kotlin.") ||
                    name.startsWith("android.")
                ) break
                declaredFieldsSafely(type)
                    .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
                        runCatching {
                            field.isAccessible = true
                            visit(field.get(candidate), depth - 1)
                        }
                    }
                type = runCatching { type.superclass }.getOrNull()
            }
        }

        visit(root, maxDepth)
        return matches.keys.toList().singleOrNull()
    }

    private fun readNamedField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            val field = runCatching {
                current.getDeclaredField(name)
            }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }
            type = current.superclass
        }
        return null
    }

    /**
     * Enumerate only class names already present in this target ClassLoader's
     * dex files. Classes are loaded without initialization; static values are
     * touched only after their declared field shape is compatible with the
     * native query-field parameter type.
     */
    private fun dexClassNames(loader: ClassLoader): Sequence<String> = sequence {
        val pathList = readNamedField(loader, "pathList")
            ?: return@sequence
        val elements = readNamedField(pathList, "dexElements")
            ?: return@sequence
        if (!elements.javaClass.isArray) return@sequence

        val seen = linkedSetOf<String>()
        val length = java.lang.reflect.Array.getLength(elements)
        for (index in 0 until length) {
            val element = java.lang.reflect.Array.get(elements, index)
                ?: continue
            val dexFile = readNamedField(element, "dexFile")
                ?: continue
            val entries = runCatching {
                dexFile.javaClass.getMethod("entries").invoke(dexFile)
                    as? java.util.Enumeration<*>
            }.getOrNull() ?: continue
            while (entries.hasMoreElements()) {
                val name = entries.nextElement() as? String ?: continue
                if (seen.add(name)) yield(name)
            }
        }
    }

    private fun potentialSemanticHolder(
        type: Class<*>,
        valueClass: Class<*>
    ): Boolean = declaredFieldsSafely(type).any { field ->
        runCatching {
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                !field.type.isPrimitive &&
                field.type != Any::class.java &&
                (
                    field.type.isAssignableFrom(valueClass) ||
                        valueClass.isAssignableFrom(field.type)
                    )
        }.getOrDefault(false)
    }

    internal fun resolveStaticFieldBySemanticValue(
        preferredHolders: List<Class<*>>,
        fallbackHolders: Sequence<Class<*>>,
        valueClass: Class<*>,
        expectedValue: String,
        description: String,
        valueProbe: ((Any) -> Any?)? = null
    ): Field {
        fun matches(type: Class<*>): List<Field> =
            fields(type).filter { field ->
                matchesStaticFieldSemanticValue(
                    field = field,
                    valueClass = valueClass,
                    expectedValue = expectedValue,
                    valueProbe = valueProbe
                )
            }

        preferredHolders.distinctBy { it.name }.forEach { holder ->
            val found = matches(holder)
            require(found.size <= 1) {
                "$description on preferred holder ${holder.name} is ambiguous: " +
                    found.joinToString { it.name }
            }
            found.singleOrNull()?.let {
                return it.apply { isAccessible = true }
            }
        }

        val discovered = arrayListOf<Field>()
        fallbackHolders
            .distinctBy { it.name }
            .filter { potentialSemanticHolder(it, valueClass) }
            .forEach { holder ->
                val found = matches(holder)
                require(found.size <= 1) {
                    "$description on discovered holder ${holder.name} is ambiguous: " +
                        found.joinToString { it.name }
                }
                found.singleOrNull()?.let { field ->
                    discovered += field
                    require(discovered.size <= 1) {
                        "$description is ambiguous across holders: " +
                            discovered.joinToString {
                                it.declaringClass.name + "." + it.name
                            }
                    }
                }
            }

        require(discovered.size == 1) {
            "$description is missing after semantic dex discovery"
        }
        return discovered.single().apply { isAccessible = true }
    }

    fun resolveStaticFieldBySemanticValue(
        loader: ClassLoader,
        preferredHolderNames: List<String>,
        valueClass: Class<*>,
        expectedValue: String,
        description: String,
        valueProbe: ((Any) -> Any?)? = null
    ): Field {
        val preferredNames = preferredHolderNames.distinct()
        val preferred = preferredNames.mapNotNull { name ->
            runCatching { loader.loadClass(name) }.getOrNull()
        }
        val valuePackage = valueClass.name.substringBeforeLast(
            '.',
            missingDelimiterValue = ""
        )
        val fallback = dexClassNames(loader)
            .filter { className ->
                className !in preferredNames &&
                    className.substringBeforeLast(
                        '.',
                        missingDelimiterValue = ""
                    ) == valuePackage
            }
            .mapNotNull { className ->
                runCatching {
                    Class.forName(className, false, loader)
                }.getOrNull()
            }

        return resolveStaticFieldBySemanticValue(
            preferredHolders = preferred,
            fallbackHolders = fallback,
            valueClass = valueClass,
            expectedValue = expectedValue,
            description = description,
            valueProbe = valueProbe
        )
    }

    fun method(
        type: Class<*>,
        preferredNames: List<String>,
        description: String,
        predicate: (Method) -> Boolean
    ): Method {
        val candidates = methods(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { it.name }
        }
        return candidates.single().apply { isAccessible = true }
    }

    fun optionalMethod(
        type: Class<*>,
        preferredNames: List<String>,
        predicate: (Method) -> Boolean
    ): Method? {
        val candidates = methods(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        return candidates.singleOrNull()?.apply { isAccessible = true }
    }

    fun field(
        type: Class<*>,
        preferredNames: List<String>,
        description: String,
        predicate: (Field) -> Boolean
    ): Field {
        val candidates = fields(type).filter(predicate)
        acceptedFieldNameOrder(type.name, preferredNames).forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { it.name + ":" + it.type.name }
        }
        return candidates.single().apply { isAccessible = true }
    }

    fun optionalField(
        type: Class<*>,
        preferredNames: List<String>,
        predicate: (Field) -> Boolean
    ): Field? {
        val candidates = fields(type).filter(predicate)
        acceptedFieldNameOrder(type.name, preferredNames).forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        return candidates.singleOrNull()?.apply { isAccessible = true }
    }

    fun constructor(
        type: Class<*>,
        description: String,
        predicate: (Constructor<*>) -> Boolean
    ): Constructor<*> {
        val candidates = type.declaredConstructors.filter(predicate)
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { ctor ->
                    ctor.parameterTypes.joinToString(",", prefix = "(", postfix = ")") { it.name }
                }
        }
        return candidates.single().apply { isAccessible = true }
    }
}
