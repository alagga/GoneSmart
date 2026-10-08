from pathlib import Path

MODULE = Path('app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt')
AGENTS = Path('AGENTS.md')
PLAYBOOK = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')

text = MODULE.read_text()

needle = '''    private val compatibilityStaticProbeStarted =\n        AtomicBoolean(false)\n'''
replacement = needle + '''\n    // Temporary, bounded read-only correlation for the still-unaccepted\n    // GMMP 4.2.1 Playlist Link action boundary. Retire after the real native\n    // call chain has been passively observed on device.\n    private val playlistBridgeActionProbeUntilMs =\n        AtomicLong(0L)\n\n    private val playlistBridgeActionProbeDialogsLeft =\n        AtomicLong(0L)\n'''
assert needle in text, 'probe field anchor missing'
text = text.replace(needle, replacement, 1)

needle = '''    private fun installPlaylistBridgeHooks(\n        param: PackageReadyParam\n    ) {\n        val loader = param.classLoader\n        val bindingsReady = playlistBridgeController.configure(loader)\n'''
replacement = '''    private fun installPlaylistBridgeHooks(\n        param: PackageReadyParam\n    ) {\n        val loader = param.classLoader\n        installPlaylistBridgeActionProbe(loader)\n        val bindingsReady = playlistBridgeController.configure(loader)\n'''
assert needle in text, 'bridge install anchor missing'
text = text.replace(needle, replacement, 1)

anchor = '''    private fun installPlaylistBridgeEditorHooks(\n        loader: ClassLoader\n    ): Int {\n'''
assert anchor in text, 'editor hook anchor missing'
probe = r'''    private fun installPlaylistBridgeActionProbe(loader: ClassLoader) {
        runCatching {
            val presenterClass = loader.loadClass("as4")
            val presenterConstructor = presenterClass.declaredConstructors
                .singleOrNull { constructor ->
                    constructor.parameterTypes.contentEquals(
                        arrayOf(
                            android.content.Context::class.java,
                            android.os.Bundle::class.java
                        )
                    )
                }
                ?: error("4.2.1 Smart editor constructor is not unique")
            presenterConstructor.isAccessible = true

            hook(presenterConstructor).intercept { chain ->
                val result = chain.proceed()
                if (playlistBridgeController.isEnabled()) {
                    val now = SystemClock.elapsedRealtime()
                    playlistBridgeActionProbeUntilMs.set(now + 30_000L)
                    playlistBridgeActionProbeDialogsLeft.set(3L)

                    val methods = presenterClass.declaredMethods
                        .asSequence()
                        .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .sortedWith(
                            compareBy<Method>({ it.name }, { it.parameterCount })
                        )
                        .take(64)
                        .joinToString(",") { method ->
                            method.name + "(" +
                                method.parameterTypes.joinToString(",") { it.name } +
                                "):" + method.returnType.name
                        }
                    val nested = presenterClass.declaredClasses
                        .take(24)
                        .joinToString(",") { it.name }
                        .ifBlank { "none" }
                    val ds4 = runCatching { loader.loadClass("ds4") }
                        .getOrNull()
                    val ds4Methods = ds4?.declaredMethods
                        ?.asSequence()
                        ?.sortedWith(
                            compareBy<Method>({ it.name }, { it.parameterCount })
                        )
                        ?.take(32)
                        ?.joinToString(",") { method ->
                            method.name + "(" +
                                method.parameterTypes.joinToString(",") { it.name } +
                                "):" + method.returnType.name
                        }
                        .orEmpty()
                        .ifBlank { "none" }

                    Log.w(
                        PLAYLIST_BRIDGE_TAG,
                        "BRIDGE ACTION PROBE ARMED | presenter=" +
                            presenterClass.name +
                            " | windowMs=30000 | methods=" + methods +
                            " | nested=" + nested +
                            " | ds4Methods=" + ds4Methods
                    )
                }
                result
            }

            val dialogShow = android.app.Dialog::class.java
                .getDeclaredMethod("show")
                .apply { isAccessible = true }
            hook(dialogShow).intercept { chain ->
                val now = SystemClock.elapsedRealtime()
                if (
                    playlistBridgeController.isEnabled() &&
                    now <= playlistBridgeActionProbeUntilMs.get()
                ) {
                    var claim = false
                    while (true) {
                        val remaining = playlistBridgeActionProbeDialogsLeft.get()
                        if (remaining <= 0L) break
                        if (
                            playlistBridgeActionProbeDialogsLeft.compareAndSet(
                                remaining,
                                remaining - 1L
                            )
                        ) {
                            claim = true
                            break
                        }
                    }
                    if (claim) {
                        val stack = Throwable().stackTrace
                            .asSequence()
                            .filterNot { frame ->
                                val name = frame.className
                                name.startsWith("android.") ||
                                    name.startsWith("java.") ||
                                    name.startsWith("kotlin.") ||
                                    name.startsWith("io.github.alagga.gonesmart.") ||
                                    name.startsWith("io.github.libxposed.")
                            }
                            .take(32)
                            .joinToString(" <- ") { frame ->
                                frame.className + "." + frame.methodName +
                                    ":" + frame.lineNumber
                            }
                            .ifBlank { "platform-only" }
                        Log.w(
                            PLAYLIST_BRIDGE_TAG,
                            "BRIDGE ACTION DIALOG TRACE | dialog=" +
                                (chain.getThisObject()?.javaClass?.name ?: "unknown") +
                                " | remaining=" +
                                playlistBridgeActionProbeDialogsLeft.get() +
                                " | stack=" + stack
                        )
                    }
                }
                chain.proceed()
            }

            Log.i(
                PLAYLIST_BRIDGE_TAG,
                "BRIDGE ACTION PROBE READY | passive Dialog.show correlation"
            )
        }.onFailure { error ->
            Log.w(
                PLAYLIST_BRIDGE_TAG,
                "BRIDGE ACTION PROBE UNAVAILABLE | native behavior unchanged",
                error
            )
        }
    }

'''
text = text.replace(anchor, probe + anchor, 1)
MODULE.write_text(text)

agents = AGENTS.read_text()
needle = '- Playlist Link must share semantic/runtime GMMP boundaries with the accepted Playlist/Smart-Playlist stack. Historical 4.2.0 obfuscated names are fast-path evidence only; the leaf rule, presenter, parser, query builder, playlist DAO/model and Smart writer must be shape-validated and ambiguity must fail closed.\n'
replacement = needle + '- A remapped Playlist Link UI action is not proven by an old obfuscated method name plus a matching signature. Correlate the real user-triggered native call passively (for example from the resulting native dialog/event stack) before promoting an intercept boundary.\n'
assert needle in agents, 'AGENTS Playlist Link anchor missing'
agents = agents.replace(needle, replacement, 1)
AGENTS.write_text(agents)

playbook = PLAYBOOK.read_text()
needle = 'Resolvers prefer semantic adapter/model/writer ownership. Historical class names remain fast paths only where useful and safe.\n'
replacement = needle + '\nFor the still-pending Playlist Link re-acceptance, the native editor Link action itself must be passively correlated from a real user-triggered GMMP dialog/event chain. A historical method name plus the same parameter shape is insufficient evidence for interception.\n'
assert needle in playbook, 'playbook Playlist Link anchor missing'
playbook = playbook.replace(needle, replacement, 1)
PLAYBOOK.write_text(playbook)
