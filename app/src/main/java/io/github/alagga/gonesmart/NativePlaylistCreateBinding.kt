package io.github.alagga.gonesmart

import java.lang.reflect.Modifier

/**
 * The two APK-verified GMMP playlist-creation chains. Keep the native writer,
 * dialog and playlist scanner intact; only the verified preference getter is
 * substituted for the lifetime of its own creation callback.
 */
internal object NativePlaylistCreateBinding {
    enum class Layout(
        val pickerCallback: String,
        val preferenceGetter: String,
        val unitType: String
    ) {
        GMMP_420("fo3", "va4", "uf5"),
        GMMP_421("go3", "sa4", "ag5")
    }

    fun resolveLayout(loader: ClassLoader): Layout? {
        fun method(type: String, name: String, vararg params: Class<*>): Boolean =
            runCatching {
                loader.loadClass(type).getDeclaredMethod(name, *params)
            }.isSuccess

        val args = arrayOf(Any::class.java, Any::class.java)
        val main = method("sp3", "invoke", *args)
        if (!main) return null
        return when {
            method("go3", "invoke", *args) &&
                method("sa4", "getValue") &&
                runCatching {
                    val picker = loader.loadClass("go3")
                    val mainCallback = loader.loadClass("sp3")
                    // Field *names* are not part of the contract:
                    // correlate unique typed edges in the native callback,
                    // presenter, preference delegate and selected-source
                    // graphs. If any edge becomes ambiguous, fail closed.
                    val mainType = loader.loadClass("tp3")
                    val pickerType = loader.loadClass("ho3")
                    val stateType = loader.loadClass("vp3")
                    val lazyType = loader.loadClass("r15")
                    val sourceType = loader.loadClass("io3")
                    val mainOwner = uniqueInstanceField(mainCallback, mainType)
                    val pickerOwner = uniqueInstanceField(picker, pickerType)
                    val state = uniqueInstanceField(mainType, stateType)
                    val lazy = uniqueInstanceField(stateType, lazyType)
                    val source = uniqueInstanceField(pickerType, sourceType)
                    val selected = sourceType.declaredFields.filter {
                        !Modifier.isStatic(it.modifiers) &&
                            java.util.List::class.java.isAssignableFrom(it.type)
                    }.singleOrNull()
                    mainOwner != null &&
                        mainCallback.declaredConstructors.any {
                            it.parameterTypes.contentEquals(arrayOf(mainType))
                        } &&
                        pickerOwner != null && state != null &&
                        lazy != null && source != null && selected != null &&
                        picker.declaredConstructors.any {
                            it.parameterTypes.contentEquals(arrayOf(pickerType))
                        }
                }.getOrDefault(false) -> Layout.GMMP_421

            method("fo3", "invoke", *args) &&
                method("va4", "getValue") -> Layout.GMMP_420

            else -> null
        }
    }

    /** Unique typed edge, with inherited class fields inspected once. */
    private fun uniqueInstanceField(
        ownerType: Class<*>,
        targetType: Class<*>
    ): java.lang.reflect.Field? =
        generateSequence<Class<*>>(ownerType) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    targetType.isAssignableFrom(it.type)
            }.toList().singleOrNull()
            ?.apply { isAccessible = true }

    internal fun uniqueTypedMember(owner: Any, type: Class<*>): Any? =
        runCatching {
            uniqueInstanceField(owner.javaClass, type)?.get(owner)
        }.getOrNull()

    /**
     * Picker create-only must redirect exactly the native selected-source
     * List, not a field named a/z/n. Typed ownership is already accepted for
     * this APK layout. Require one list field, restore it in the caller's
     * finally, and never mutate GMMP's persisted save location.
     */
    fun resolvePickerSelectedList(
        callback: Any,
        layout: Layout
    ): Triple<Any, java.lang.reflect.Field, List<*>>? = runCatching {
        val loader = callback.javaClass.classLoader ?: return@runCatching null
        val ownerType = loader.loadClass(
            if (layout == Layout.GMMP_421) "ho3" else "go3"
        )
        val sourceType = loader.loadClass(
            if (layout == Layout.GMMP_421) "io3" else "ho3"
        )
        val owner = uniqueTypedMember(callback, ownerType)
            ?: return@runCatching null
        val source = uniqueTypedMember(owner, sourceType)
            ?: return@runCatching null
        val listFields = generateSequence<Class<*>>(source.javaClass) {
            it.superclass
        }.flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    java.util.List::class.java.isAssignableFrom(it.type)
            }.toList()
        val field = listFields.singleOrNull()
            ?.apply { isAccessible = true } ?: return@runCatching null
        val original = field.get(source) as? List<*>
            ?: return@runCatching null
        Triple(source, field, original)
    }.getOrNull()

    fun nativeUnit(loader: ClassLoader, layout: Layout): Any? = runCatching {
        val unit = loader.loadClass(layout.unitType)
        unit.declaredFields.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.type == unit
        }?.apply { isAccessible = true }?.get(null)
    }.getOrNull()

    /**
     * Read-only semantic graph resolver for an APK-proven create callback.
     * No obfuscated field names are used after the callback/owner types are
     * selected. The scoped preference getter hook still verifies the actual
     * live GMMP root before the native writer is allowed to run.
     */
    fun resolveDelegate(callback: Any, layout: Layout): Any? = runCatching {
        val loader = callback.javaClass.classLoader ?: return@runCatching null
        if (layout == Layout.GMMP_420) {
            // Historical 4.2.0 map remains a separate accepted branch.
            // Never substitute unverified 4.2.1 owner types into it.
            val owner = callback.javaClass.getDeclaredField("o")
                .apply { isAccessible = true }.get(callback)
                ?: return@runCatching null
            val state = generateSequence<Class<*>>(owner.javaClass) {
                it.superclass
            }.mapNotNull { type ->
                runCatching {
                    type.getDeclaredField("x")
                        .apply { isAccessible = true }.get(owner)
                }.getOrNull()
            }.firstOrNull()?.takeIf { it.javaClass.name == "vp3" }
                ?: return@runCatching null
            val lazy = state.javaClass.getDeclaredField("F")
                .apply { isAccessible = true }.get(state)
                ?: return@runCatching null
            return@runCatching lazy.javaClass.getDeclaredMethod("getValue")
                .apply { isAccessible = true }.invoke(lazy)
                ?.takeIf { it.javaClass.name == layout.preferenceGetter }
        }

        val ownerType = loader.loadClass("tp3")
        val stateType = loader.loadClass("vp3")
        val getterType = loader.loadClass(layout.preferenceGetter)
        val callbackOwner = if (layout == Layout.GMMP_421 &&
            callback.javaClass.name == layout.pickerCallback
        ) loader.loadClass("ho3") else ownerType

        val owner = uniqueTypedMember(callback, callbackOwner)
            ?: return@runCatching null
        val state = uniqueTypedMember(owner, stateType)
            ?: return@runCatching null
        // The known version's lazy delegate type is the only permitted
        // read-only getValue() host; never call arbitrary zero-arg methods.
        val lazy = uniqueTypedMember(state, loader.loadClass("r15"))
            ?: return@runCatching null

        val getValue = lazy.javaClass.methods.singleOrNull {
            it.name == "getValue" && it.parameterCount == 0
        } ?: lazy.javaClass.getDeclaredMethod("getValue")
        getValue.isAccessible = true
        getValue.invoke(lazy)?.takeIf { getterType.isInstance(it) }
    }.getOrNull()
}
