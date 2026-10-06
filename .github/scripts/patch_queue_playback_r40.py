from pathlib import Path

ROOT = Path('.')

# 1) Correct GmmpQueueReader current-row ownership: qr.p/dx3 first; ur is
# explicitly excluded after r39 disproved it as playback queue_position.
path = ROOT / 'app/src/main/java/io/github/alagga/gonesmart/GmmpQueueReader.kt'
text = path.read_text()
old = '''        // r20 device evidence repeatedly resolves the actual 4.2.1
        // playback pointer from qr.t -> ur.method:b. Prefer that verified
        // state host before generic integer correlation; qr.r is the Track
        // DAO and happened to expose an unrelated integer with the same value.
        readObjectField(autoDjInstance, "t")?.let { state ->
            fromHost(
                state,
                "field:t->" + state.javaClass.name
            )?.let { return it }
        }

        // Earlier 4.2.1 builds exposed the pointer through qr.p/dx3.
        readObjectField(autoDjInstance, "p")?.let { fast ->
            fromHost(fast, "direct-state:" + fast.javaClass.name)
                ?.let { return it }
        }
'''
new = '''        // r39 device evidence disproved qr.t/ur.b as playback position:
        // ur.b remained 1 while MusicService was reading queue positions 7
        // and 8. Earlier 4.2.1 device passes repeatedly correlated qr.p/dx3
        // with the actually playing row, so restore that owner first.
        readObjectField(autoDjInstance, "p")?.let { state ->
            fromHost(state, "direct-state:" + state.javaClass.name)
                ?.let { return it }
        }

        // qr.t/ur is deliberately NOT a current-position fallback. Its value
        // can coincidentally equal a valid queue_position and caused r36-r39
        // to select row 1 even while another row was playing.
'''
if text.count(old) != 1:
    raise SystemExit(f'GmmpQueueReader preferred block count={text.count(old)}')
text = text.replace(old, new, 1)
old2 = '''        val candidates = hierarchyFields(autoDjInstance.javaClass)
            .mapNotNull { field ->
                field.isAccessible = true
'''
new2 = '''        val candidates = hierarchyFields(autoDjInstance.javaClass)
            .mapNotNull { field ->
                if (field.name == "t") return@mapNotNull null
                field.isAccessible = true
'''
if text.count(old2) != 1:
    raise SystemExit(f'GmmpQueueReader generic block count={text.count(old2)}')
text = text.replace(old2, new2, 1)
path.write_text(text)

# 2) QueueFlipController: add broad passive transition observer, state-writer
# observer entry point, and a bounded current-state snapshot at Flip time.
path = ROOT / 'app/src/main/java/io/github/alagga/gonesmart/QueueFlipController.kt'
text = path.read_text()
old = '''    private val positionWriterObserver = NativeQueuePositionWriterObserver()
    private val observedPositionCommands =
'''
new = '''    private val positionWriterObserver = NativeQueuePositionWriterObserver()
    private val playbackTransitionObserver =
        NativeQueuePlaybackTransitionObserver()
    private val observedPositionCommands =
'''
if text.count(old) != 1:
    raise SystemExit(f'QueueFlipController observer field count={text.count(old)}')
text = text.replace(old, new, 1)
old = '''    @Volatile private var nativeQueue: WeakReference<Any>? = null
    @Volatile private var nativeAutoDj: WeakReference<Any>? = null
'''
new = '''    @Volatile private var nativeQueue: WeakReference<Any>? = null
    @Volatile private var nativeAutoDj: WeakReference<Any>? = null
    @Volatile private var nativeMusicService: WeakReference<Any>? = null
'''
if text.count(old) != 1:
    raise SystemExit(f'QueueFlipController weak refs count={text.count(old)}')
text = text.replace(old, new, 1)
old = '''        val autoDj = nativeAutoDj?.get()
            ?: service?.let(::resolveNativeAutoDjFromService)
        if (service != null && value != null) {
'''
new = '''        val autoDj = nativeAutoDj?.get()
            ?: service?.let(::resolveNativeAutoDjFromService)
        if (service != null) nativeMusicService = WeakReference(service)
        if (service != null && value != null) {
'''
if text.count(old) != 1:
    raise SystemExit(f'QueueFlipController position method count={text.count(old)}')
text = text.replace(old, new, 1)
marker = '''    private fun resolveNativeAutoDjFromService(service: Any): Any? {
'''
insert = '''    fun aroundNativePlaybackTransition(
        service: Any?,
        method: Method,
        args: List<Any?>,
        proceed: () -> Any?
    ): Any? {
        val autoDj = nativeAutoDj?.get()
            ?: service?.let(::resolveNativeAutoDjFromService)
        if (service != null) nativeMusicService = WeakReference(service)
        return playbackTransitionObserver.aroundNaturalInvocation(
            service = service,
            method = method,
            args = args,
            autoDj = autoDj,
            proceed = proceed
        )
    }

    fun aroundNativeStatePositionCommand(
        receiver: Any?,
        method: Method,
        value: Int?,
        proceed: () -> Any?
    ): Any? {
        val autoDj = nativeAutoDj?.get()
        if (receiver != null && value != null) {
            val key = method.declaringClass.name + "." + method.name + "(int)"
            if (observedPositionCommands.add(key)) {
                Log.i(
                    TAG,
                    "QUEUE STATE COMMAND OBSERVED | command=$key | arg=$value | " +
                        "autoDj=" + if (autoDj != null) "ready" else "unresolved"
                )
            }
        }
        return positionWriterObserver.aroundNaturalInvocation(
            service = receiver,
            method = method,
            argument = value,
            autoDj = autoDj,
            proceed = proceed
        )
    }

'''
if text.count(marker) != 1:
    raise SystemExit(f'QueueFlipController resolver marker count={text.count(marker)}')
text = text.replace(marker, insert + marker, 1)
old = '''            val positionWriter = positionWriterObserver.binding(autoDj)
            return GmmpQueueMutationBridge(
'''
new = '''            val snapshot = NativeQueuePlaybackDiagnostics.snapshot(
                nativeMusicService?.get(),
                autoDj
            )
            Log.i(
                TAG,
                "QUEUE PLAYBACK SNAPSHOT | " +
                    snapshot.values.entries.joinToString(",") {
                        it.key + "=" + it.value
                    }.ifBlank { "none" }
            )
            val positionWriter = positionWriterObserver.binding(autoDj)
            return GmmpQueueMutationBridge(
'''
if text.count(old) != 1:
    raise SystemExit(f'QueueFlipController flip snapshot count={text.count(old)}')
text = text.replace(old, new, 1)
path.write_text(text)

# 3) 4.2.1 mutation bridge: only a passively proven writer may move the
# playback pointer. Structural setter signatures are diagnostic evidence, not
# permission to invoke an unproven mutator.
path = ROOT / 'app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt'
text = path.read_text()
start = text.index('    private fun resolveStatePosition(\n')
end = text.index('    private fun isStateSignalHost(value: Any): Boolean {\n', start)
replacement = '''    private fun resolveStatePosition(
        currentQueuePosition: Int
    ): StatePositionBinding {
        fun externalBinding(): StatePositionBinding? {
            val writer = verifiedPositionWriter ?: return null
            if (writer.read() != currentQueuePosition) return null
            return ExternalStatePositionBinding(writer)
        }

        externalBinding()?.let { return it }

        // r39 disproved qr.t/ur.b as playback queue_position. Diagnose only
        // the older, repeatedly correlated qr.p/dx3 owner here, but do not
        // invoke any of its apparent setters until a natural GMMP call has
        // passively verified one through NativeQueuePositionWriterObserver.
        objectField(autoDj, "p")?.let { state ->
            val analysis = NativeQueueStateAccessorPolicy.analyze(
                state,
                currentQueuePosition
            )
            if (analysis.hasReadEvidence) {
                val key = "current-pointer|field:p|" +
                    state.javaClass.name + "|" + currentQueuePosition
                if (reportedShapes.add(key)) {
                    Log.w(
                        TAG,
                        "QUEUE CURRENT POINTER SHAPE | source=field:p | " +
                            analysis.describe(currentQueuePosition)
                    )
                }
                error(
                    "GMMP writable current-position binding is unresolved " +
                        "for corrected playback reader " + state.javaClass.name +
                        "; passive native writer has not been observed yet"
                )
            }
        }

        error(
            "GMMP current-position playback reader is unresolved; " +
                "passive native writer discovery remains fail-closed"
        )
    }

'''
text = text[:start] + replacement + text[end:]
path.write_text(text)

# 4) GoneSmartModule: replace the narrow three-method observer with one bundled
# passive diagnostic over MusicService playback handlers plus state-host
# one-Int writers. No candidate is actively invoked by discovery.
path = ROOT / 'app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt'
text = path.read_text()
start_marker = '        // Learn the 4.2.1 writable current-position boundary passively.\n'
end_marker = '        // Playlist and Smart Playlist both use MusicService.w1(action=0)\n'
start = text.index(start_marker)
end = text.index(end_marker, start)
block = '''        // r40: observe the real 4.2.1 playback-position boundary passively.
        // r39 proved that the natural title transition bypasses the three
        // one-Int MusicService commands and also disproved qr.t/ur.b as the
        // current queue_position. Bundle service-event and Auto-DJ child
        // writer observation in one device pass. No candidate is invoked by
        // discovery; every hook only wraps a call GMMP makes naturally.
        runCatching {
            val serviceClass = param.classLoader.loadClass(
                "gonemad.gmmp.playback.service.MusicService"
            )
            val methodKey: (Method) -> String = { method ->
                method.declaringClass.name + "|" + method.name + "|" +
                    method.parameterTypes.joinToString(",") { it.name } + "|" +
                    method.returnType.name
            }

            val positionCandidates = GmmpReflectionPolicy
                .callableMethods(serviceClass)
                .filter { method ->
                    !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        method.parameterCount == 1 &&
                        (method.parameterTypes[0] == Integer.TYPE ||
                            method.parameterTypes[0] == Integer::class.java) &&
                        method.returnType == Void.TYPE
                }
                .distinctBy(methodKey)
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

            val positionKeys = positionCandidates.map(methodKey).toSet()
            val playbackCandidates = NativeQueuePlaybackDiagnostics
                .playbackMethods(serviceClass)
                .filter { methodKey(it) !in positionKeys }
            playbackCandidates.forEach { method ->
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val args = (0 until method.parameterCount).map { index ->
                        chain.getArg(index)
                    }
                    queueFlipController.aroundNativePlaybackTransition(
                        service = chain.getThisObject(),
                        method = method,
                        args = args
                    ) {
                        chain.proceed()
                    }
                }
            }

            val autoDjClass = param.classLoader.loadClass("qr")
            val stateWriterCandidates = NativeQueuePlaybackDiagnostics
                .stateWriterMethods(autoDjClass)
            stateWriterCandidates.forEach { method ->
                method.isAccessible = true
                hook(method).intercept { chain ->
                    queueFlipController.aroundNativeStatePositionCommand(
                        receiver = chain.getThisObject(),
                        method = method,
                        value = (chain.getArg(0) as? Number)?.toInt()
                    ) {
                        chain.proceed()
                    }
                }
            }

            Log.i(
                "GoneSmartFlip",
                "QUEUE PLAYBACK OBSERVER READY | serviceInt=" +
                    positionCandidates.size +
                    " | playback=" + playbackCandidates.size +
                    " | stateWriters=" + stateWriterCandidates.size +
                    " | writerSignatures=" +
                    NativeQueuePlaybackDiagnostics.signatures(
                        stateWriterCandidates
                    ).ifBlank { "none" }
            )
        }.onFailure { error ->
            Log.w(
                "GoneSmartFlip",
                "QUEUE PLAYBACK OBSERVER UNAVAILABLE | passive discovery disabled",
                error
            )
        }

'''
text = text[:start] + block + text[end:]
path.write_text(text)
