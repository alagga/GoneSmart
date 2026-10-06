from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))


reader = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueReader.kt"
replace_once(
    reader,
    """    fun read(autoDjInstance: Any): QueueContext? {\n        readLegacy(autoDjInstance)?.let { return it }\n        return readThroughCursor(autoDjInstance)\n    }\n""",
    """    fun read(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int? = null\n    ): QueueContext? {\n        readLegacy(autoDjInstance)?.let { return it }\n        return readThroughCursor(autoDjInstance, currentQueuePositionHint)\n    }\n"""
)
replace_once(
    reader,
    """    private fun readThroughCursor(autoDjInstance: Any): QueueContext? =\n""",
    """    private fun readThroughCursor(\n        autoDjInstance: Any,\n        currentQueuePositionHint: Int?\n    ): QueueContext? =\n"""
)
replace_once(
    reader,
    """            val marker = resolveCurrentMarker(autoDjInstance, rows)\n                ?: return@runCatching null\n""",
    """            val marker = resolveCurrentMarker(\n                autoDjInstance,\n                rows,\n                currentQueuePositionHint\n            ) ?: return@runCatching null\n"""
)
replace_once(
    reader,
    """    private fun resolveCurrentMarker(\n        autoDjInstance: Any,\n        rows: List<CursorRow>\n    ): CurrentMarker? {\n        fun fromHost(host: Any, source: String): CurrentMarker? {\n""",
    """    private fun resolveCurrentMarker(\n        autoDjInstance: Any,\n        rows: List<CursorRow>,\n        currentQueuePositionHint: Int?\n    ): CurrentMarker? {\n        currentQueuePositionHint?.let { position ->\n            val matches = rows.indices.filter { index ->\n                rows[index].queuePosition == position\n            }\n            if (matches.size == 1) {\n                return CurrentMarker(\n                    rowIndex = matches.single(),\n                    orderKind = OrderKind.QUEUE,\n                    source = \"verified-native-writer-hint\"\n                )\n            }\n            Log.w(\n                TAG,\n                \"GMMP QUEUE CURRENT | absolute position hint rejected\" +\n                    \" | position=\" + position +\n                    \" | matches=\" + matches.size\n            )\n        }\n\n        fun fromHost(host: Any, source: String): CurrentMarker? {\n"""
)
replace_once(
    reader,
    """ * playback row is resolved only from structurally validated integer state;\n * ambiguous candidates fail closed and leave native Auto-DJ untouched.\n""",
    """ * playback row prefers a passively verified absolute queue-position hint.\n * Internal integer state is only a fallback; ambiguous candidates fail closed\n * and leave native Auto-DJ untouched.\n"""
)

bridge = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt"
replace_once(
    bridge,
    """            val verified = GmmpQueueReader().read(autoDj)\n                ?: error(\"Queue verification unavailable\")\n""",
    """            val verified = GmmpQueueReader().read(\n                autoDj,\n                statePosition.read()\n            ) ?: error(\"Queue verification unavailable\")\n"""
)
replace_once(
    bridge,
    """        val verified = GmmpQueueReader().read(autoDj) ?: return false\n""",
    """        val verified = GmmpQueueReader().read(\n            autoDj,\n            statePosition?.read()\n        ) ?: return false\n"""
)
replace_once(
    bridge,
    """        val context = GmmpQueueReader().read(autoDj)\n            ?: error(\"GMMP queue Cursor mapping unavailable\")\n""",
    """        val absoluteHint = verifiedPositionWriter?.read()\n        val context = GmmpQueueReader().read(autoDj, absoluteHint)\n            ?: error(\"GMMP queue Cursor mapping unavailable\")\n"""
)

observer = "app/src/main/java/io/github/alagga/gonesmart/NativeQueuePositionWriterObserver.kt"
replace_once(
    observer,
    """        private const val PENDING_MAX_AGE_MS = 650L\n        private val DELAYED_CHECKS_MS = longArrayOf(40L, 120L, 280L)\n""",
    """        private const val PENDING_MAX_AGE_MS = 650L\n        private const val ABSOLUTE_CURSOR_PROOF =\n            \"natural-unique-state-writer+queue-position\"\n        private val DELAYED_CHECKS_MS = longArrayOf(40L, 120L, 280L)\n"""
)
replace_once(
    observer,
    """    @Volatile\n    private var verified: Verified? = null\n""",
    """    @Volatile\n    private var verified: Verified? = null\n\n    @Volatile\n    private var latestAbsolutePosition: Int? = null\n"""
)
replace_once(
    observer,
    """            val after = readSignal(autoDj)\n            val changedToArgument =\n                before != null && after != null &&\n                    before.value != after.value && after.value == argument\n            if (changedToArgument) {\n""",
    """            val after = readSignal(autoDj)\n            val uniqueStateWriter =\n                NativeQueuePlaybackDiagnostics\n                    .stateWriterMethods(autoDj.javaClass)\n                    .singleOrNull()\n                    ?.let { sameMethod(it, method) } == true\n            val absoluteCursorProof =\n                uniqueStateWriter &&\n                    isUniqueQueuePosition(autoDj, argument)\n\n            if (absoluteCursorProof) {\n                latestAbsolutePosition = argument\n                frames.lastOrNull()?.childMatched = true\n                val looper = Looper.myLooper()\n                if (looper != null && !frame.childMatched) {\n                    record(\n                        service,\n                        autoDj,\n                        method,\n                        looper,\n                        ABSOLUTE_CURSOR_PROOF\n                    )\n                } else if (looper == null) {\n                    Log.w(\n                        TAG,\n                        \"QUEUE POSITION WRITER NATURAL PROOF | no Looper\" +\n                            \" | writer=\" + method.declaringClass.name +\n                            \".\" + method.name + \"(int)\"\n                    )\n                }\n            } else {\n                val changedToArgument =\n                    before != null && after != null &&\n                        before.value != after.value && after.value == argument\n                if (changedToArgument) {\n"""
)
replace_once(
    observer,
    """            } else if (before != null) {\n                val looper = Looper.myLooper()\n                if (looper != null) {\n                    scheduleDelayedObservation(\n                        service = service,\n                        autoDj = autoDj,\n                        method = method,\n                        looper = looper,\n                        beforeValue = before.value,\n                        argument = argument,\n                        depth = depth\n                    )\n                }\n            }\n            if (frames.isEmpty()) stack.remove()\n""",
    """                } else if (before != null) {\n                    val looper = Looper.myLooper()\n                    if (looper != null) {\n                        scheduleDelayedObservation(\n                            service = service,\n                            autoDj = autoDj,\n                            method = method,\n                            looper = looper,\n                            beforeValue = before.value,\n                            argument = argument,\n                            depth = depth\n                        )\n                    }\n                }\n            }\n            if (frames.isEmpty()) stack.remove()\n"""
)
replace_once(
    observer,
    """            override fun read(): Int? = readSignal(autoDj)?.value\n\n            override fun write(position: Int): Boolean {\n""",
    """            override fun read(): Int? =\n                if (proof.signalSource == ABSOLUTE_CURSOR_PROOF) {\n                    latestAbsolutePosition\n                } else {\n                    readSignal(autoDj)?.value\n                }\n\n            override fun write(position: Int): Boolean {\n"""
)
replace_once(
    observer,
    """                if (!invoked) return false\n                val deadline = SystemClock.elapsedRealtime() + 1200L\n""",
    """                if (!invoked) return false\n                if (proof.signalSource == ABSOLUTE_CURSOR_PROOF) {\n                    if (!isUniqueQueuePosition(autoDj, position)) return false\n                    latestAbsolutePosition = position\n                    return true\n                }\n                val deadline = SystemClock.elapsedRealtime() + 1200L\n"""
)
insert_anchor = """    private fun scheduleDelayedObservation(\n"""
helpers = """    private fun sameMethod(left: Method, right: Method): Boolean =\n        left.declaringClass == right.declaringClass &&\n            left.name == right.name &&\n            left.returnType == right.returnType &&\n            left.parameterTypes.contentEquals(right.parameterTypes)\n\n    private fun isUniqueQueuePosition(autoDj: Any, position: Int): Boolean {\n        if (position < 0) return false\n        return runCatching {\n            GmmpReadOnlySql.query(\n                autoDjInstance = autoDj,\n                sql = \"SELECT COUNT(*) FROM queue_table WHERE queue_position = ?\",\n                args = arrayOf(position)\n            ) { cursor ->\n                cursor.moveToFirst() && cursor.getInt(0) == 1\n            }\n        }.getOrDefault(false)\n    }\n\n"""
replace_once(observer, insert_anchor, helpers + insert_anchor)
replace_once(
    observer,
    """ * only when one of its natural calls changes the corrected qr.p/dx3-backed\n * playback-position signal exactly to the method's Int argument.\n""",
    """ * only from natural GMMP behavior. The preferred 4.2.1 proof is the\n * structurally unique Auto-DJ child state writer being called naturally with\n * an argument that identifies exactly one live queue_position in the read-only\n * Cursor. Internal scalar state remains a compatibility fallback only.\n"""
)

print("r42 absolute queue-position patch applied")
