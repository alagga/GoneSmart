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
                    val mainOwner = mainCallback.getDeclaredField("n")
                    val owner = picker.getDeclaredField("n")
                    val state = loader.loadClass("tp3").getDeclaredField("w")
                    val lazy = loader.loadClass("vp3").getDeclaredField("E")
                    val source = loader.loadClass("ho3").getDeclaredField("z")
                    val selected = loader.loadClass("io3").getDeclaredField("a")
                    mainOwner.type == loader.loadClass("tp3") &&
                        mainCallback.declaredConstructors.any {
                            it.parameterTypes.contentEquals(arrayOf(mainOwner.type))
                        } &&
                        owner.type == loader.loadClass("ho3") &&
                        state.type == loader.loadClass("vp3") &&
                        lazy.type == loader.loadClass("r15") &&
                        source.type == loader.loadClass("io3") &&
                        java.util.List::class.java.isAssignableFrom(selected.type) &&
                        picker.declaredConstructors.any {
                            it.parameterTypes.contentEquals(arrayOf(owner.type))
                        }
                }.getOrDefault(false) -> Layout.GMMP_421

            method("fo3", "invoke", *args) &&
                method("va4", "getValue") -> Layout.GMMP_420

            else -> null
        }
    }

    fun nativeUnit(loader: ClassLoader, layout: Layout): Any? = runCatching {
        val unit = loader.loadClass(layout.unitType)
        unit.declaredFields.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.type == unit
        }?.apply { isAccessible = true }?.get(null)
    }.getOrNull()

    /**
     * In 4.2.1 sp3.n/go3.n -> tp3.w -> vp3.E -> r15.getValue()
     * -> sa4.getValue() supplies playlist_saveLocation. The old 4.2.0
     * callback/delegate hierarchy is separately retained.
     */
    fun resolveDelegate(callback: Any, layout: Layout): Any? = runCatching {
        if (layout == Layout.GMMP_421) {
            val owner = callback.javaClass.getDeclaredField("n")
                .apply { isAccessible = true }.get(callback) ?: return@runCatching null
            val presenter = generateSequence<Class<*>>(owner.javaClass) { it.superclass }
                .firstOrNull { it.name == "tp3" } ?: return@runCatching null
            val state = presenter.getDeclaredField("w")
                .apply { isAccessible = true }.get(owner)
                ?.takeIf { it.javaClass.name == "vp3" } ?: return@runCatching null
            val lazy = state.javaClass.getDeclaredField("E")
                .apply { isAccessible = true }.get(state)
                ?.takeIf { it.javaClass.name == "r15" } ?: return@runCatching null
            lazy.javaClass.getDeclaredMethod("getValue")
                .apply { isAccessible = true }.invoke(lazy)
                ?.takeIf { it.javaClass.name == "sa4" }
        } else {
            val owner = callback.javaClass.getDeclaredField("o")
                .apply { isAccessible = true }.get(callback) ?: return@runCatching null
            val state = generateSequence<Class<*>>(owner.javaClass) { it.superclass }
                .mapNotNull { type ->
                    runCatching {
                        type.getDeclaredField("x")
                            .apply { isAccessible = true }.get(owner)
                    }.getOrNull()
                }.firstOrNull()?.takeIf { it.javaClass.name == "vp3" }
                ?: return@runCatching null
            val lazy = state.javaClass.getDeclaredField("F")
                .apply { isAccessible = true }.get(state)
                ?: return@runCatching null
            lazy.javaClass.getDeclaredMethod("getValue")
                .apply { isAccessible = true }.invoke(lazy)
                ?.takeIf { it.javaClass.name == "va4" }
        }
    }.getOrNull()
}
