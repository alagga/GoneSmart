from pathlib import Path

path = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
text = path.read_text()

anchor = '''        // Playlist and Smart Playlist both use MusicService.w1(action=0)
'''
if text.count(anchor) != 1:
    raise SystemExit(f"Queue position observer insertion anchor count={text.count(anchor)}")

block = '''        // Learn the 4.2.1 writable current-position boundary passively.
        // Hook only natural GMMP MusicService commands that take exactly one
        // Int. The observer NEVER invokes a candidate during discovery: the
        // original call proceeds once, and a writer is retained only when the
        // independent ur-backed position signal changes exactly to that Int.
        // This avoids repeating the earlier signature/value-guessing mistake.
        runCatching {
            val serviceClass = param.classLoader.loadClass(
                "gonemad.gmmp.playback.service.MusicService"
            )
            val positionCandidates = GmmpReflectionPolicy
                .callableMethods(serviceClass)
                .filter { method ->
                    !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        method.parameterCount == 1 &&
                        (method.parameterTypes[0] == Int::class.javaPrimitiveType ||
                            method.parameterTypes[0] == Integer::class.java) &&
                        method.declaringClass.name.startsWith("gonemad.gmmp.")
                }
                .distinctBy {
                    it.declaringClass.name + "|" + it.name + "|" +
                        it.returnType.name
                }
            require(positionCandidates.isNotEmpty()) {
                "No passive MusicService Int command candidates"
            }
            positionCandidates.forEach { method ->
                method.isAccessible = true
                hook(method).intercept { chain ->
                    queueFlipController.aroundNativePositionCommand(
                        service = chain.getThisObject(),
                        method = method,
                        value = (chain.getArg(0) as? Number)?.toInt()
                    ) {
                        chain.proceed()
                    }
                }
            }
            Log.i(
                "GoneSmartFlip",
                "QUEUE POSITION OBSERVER READY | candidates=" +
                    positionCandidates.size
            )
        }.onFailure { error ->
            Log.w(
                "GoneSmartFlip",
                "QUEUE POSITION OBSERVER UNAVAILABLE | passive discovery disabled",
                error
            )
        }

'''
text = text.replace(anchor, block + anchor, 1)
path.write_text(text)
