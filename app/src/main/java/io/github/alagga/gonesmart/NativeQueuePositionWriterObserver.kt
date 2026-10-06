package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface NativeQueuePositionWriter {
    val description: String
    fun read(): Int?
    fun write(position: Int): Boolean
}

/**
 * Learns GMMP's writable current-position boundary only from NORMAL GMMP
 * behavior. Candidate methods are never probed.
 *
 * The strongest 4.2.1 proof is causal and same-host: when GMMP naturally
 * invokes the structurally unique Auto-DJ child Int writer, integer read
 * signals on that exact receiver are sampled before/after. A writer graduates
 * only if one native readback changes to the exact argument and that argument
 * identifies exactly one live queue_position in the independent read-only
 * Cursor. This prevents the earlier cross-host/value-correlation mistakes.
 */
internal class NativeQueuePositionWriterObserver(
    private val readSignal: (Any) -> NativeQueuePositionSignal.Reading? =
        NativeQueuePositionSignal::read
) {
    companion object {
        private const val TAG = "GoneSmartFlip"
        private const val PENDING_MAX_AGE_MS = 650L
        private const val ABSOLUTE_CURSOR_PROOF =
            "natural-unique-state-writer+queue-position"
        private val DELAYED_CHECKS_MS = longArrayOf(40L, 120L, 280L)
        private val CAUSAL_PROOF_CHECKS_MS = longArrayOf(30L, 120L)
    }

    private data class Frame(var childMatched: Boolean = false)

    private data class Pending(
        val service: WeakReference<Any>,
        val autoDj: WeakReference<Any>,
        val method: Method,
        val looper: Looper,
        val beforeValue: Int,
        val argument: Int,
        val depth: Int,
        val createdAt: Long
    )

    private data class Verified(
        val receiver: WeakReference<Any>,
        val autoDj: WeakReference<Any>,
        val method: Method,
        val looper: Looper?,
        val signalSource: String,
        val readback: NativeQueuePositionCausalReadbackPolicy.Readback?
    )

    private val stack = ThreadLocal.withInitial { ArrayDeque<Frame>() }
    private val pendingLock = Any()
    private val pending = ArrayDeque<Pending>()
    private val reportedUnmatchedTransitions =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val causalExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "GoneSmartQueuePositionProof").apply { isDaemon = true }
    }

    @Volatile
    private var verified: Verified? = null

    @Volatile
    private var latestAbsolutePosition: Int? = null

    fun aroundNaturalInvocation(
        service: Any?,
        method: Method,
        argument: Int?,
        autoDj: Any?,
        proceed: () -> Any?
    ): Any? {
        if (service == null || argument == null || autoDj == null) {
            return proceed()
        }

        val uniqueStateWriter =
            NativeQueuePlaybackDiagnostics
                .stateWriterMethods(autoDj.javaClass)
                .singleOrNull()
                ?.let { sameMethod(it, method) } == true
        val causalBefore = if (uniqueStateWriter) {
            NativeQueuePositionCausalReadbackPolicy.snapshot(service)
        } else {
            null
        }
        val before = readSignal(autoDj)
        val frame = Frame()
        val frames = stack.get()
        val depth = frames.size
        frames.addLast(frame)
        try {
            return proceed()
        } finally {
            frames.removeLast()

            val causalReadback = causalBefore?.let {
                NativeQueuePositionCausalReadbackPolicy.select(
                    host = service,
                    before = it,
                    writerArgument = argument
                )
            }
            if (causalReadback != null) {
                frames.lastOrNull()?.childMatched = true
                scheduleCausalProof(
                    receiver = service,
                    autoDj = autoDj,
                    method = method,
                    argument = argument,
                    looper = Looper.myLooper(),
                    readback = causalReadback
                )
            } else {
                val after = readSignal(autoDj)
                val absoluteCursorProof =
                    uniqueStateWriter && isUniqueQueuePosition(autoDj, argument)

                if (absoluteCursorProof) {
                    latestAbsolutePosition = argument
                    frames.lastOrNull()?.childMatched = true
                    val looper = Looper.myLooper()
                    if (!frame.childMatched) {
                        record(
                            receiver = service,
                            autoDj = autoDj,
                            method = method,
                            looper = looper,
                            signalSource = ABSOLUTE_CURSOR_PROOF,
                            readback = null
                        )
                    }
                } else {
                    val changedToArgument =
                        before != null && after != null &&
                            before.value != after.value && after.value == argument
                    if (changedToArgument) {
                        frames.lastOrNull()?.childMatched = true
                        if (!frame.childMatched) {
                            record(
                                receiver = service,
                                autoDj = autoDj,
                                method = method,
                                looper = Looper.myLooper(),
                                signalSource = after!!.source,
                                readback = null
                            )
                        }
                    } else if (before != null) {
                        val looper = Looper.myLooper()
                        if (looper != null) {
                            scheduleDelayedObservation(
                                service = service,
                                autoDj = autoDj,
                                method = method,
                                looper = looper,
                                beforeValue = before.value,
                                argument = argument,
                                depth = depth
                            )
                        }
                    }
                }
            }
            if (frames.isEmpty()) stack.remove()
        }
    }

    fun binding(autoDj: Any): NativeQueuePositionWriter? {
        val proof = verified ?: return null
        if (proof.autoDj.get() !== autoDj) return null
        val receiver = proof.receiver.get() ?: return null
        return object : NativeQueuePositionWriter {
            override val description: String =
                proof.method.declaringClass.name + "." + proof.method.name +
                    "(int) via " + proof.signalSource

            override fun read(): Int? = when {
                proof.readback != null -> proof.readback.read(receiver)
                proof.signalSource == ABSOLUTE_CURSOR_PROOF -> latestAbsolutePosition
                else -> readSignal(autoDj)?.value
            }

            override fun write(position: Int): Boolean {
                val before = read() ?: return false
                if (before == position) return true
                val invoke = {
                    runCatching {
                        proof.method.isAccessible = true
                        proof.method.invoke(receiver, position)
                    }.isSuccess
                }
                val invoked = if (
                    proof.looper == null || Looper.myLooper() == proof.looper
                ) {
                    invoke()
                } else {
                    val done = CountDownLatch(1)
                    var ok = false
                    Handler(proof.looper).post {
                        ok = invoke()
                        done.countDown()
                    }
                    done.await(1500L, TimeUnit.MILLISECONDS) && ok
                }
                if (!invoked) return false

                val deadline = SystemClock.elapsedRealtime() + 1200L
                do {
                    if (read() == position) {
                        if (!isUniqueQueuePosition(autoDj, position)) return false
                        latestAbsolutePosition = position
                        return true
                    }
                    Thread.sleep(20L)
                } while (SystemClock.elapsedRealtime() < deadline)
                return false
            }
        }
    }

    private fun scheduleCausalProof(
        receiver: Any,
        autoDj: Any,
        method: Method,
        argument: Int,
        looper: Looper?,
        readback: NativeQueuePositionCausalReadbackPolicy.Readback
    ) {
        fun verify() {
            if (verified != null && verified?.method != method) return
            if (readback.read(receiver) != argument) return
            if (!isUniqueQueuePosition(autoDj, argument)) return
            latestAbsolutePosition = argument
            record(
                receiver = receiver,
                autoDj = autoDj,
                method = method,
                looper = looper,
                signalSource = "causal-same-host:" + readback.description,
                readback = readback
            )
        }

        // Try once outside the intercepted method immediately. If Room is
        // temporarily busy/re-entrant, repeat in a short bounded delayed
        // window. No writer is invoked by these checks.
        verify()
        CAUSAL_PROOF_CHECKS_MS.forEach { delay ->
            causalExecutor.schedule(
                { runCatching(::verify) },
                delay,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun sameMethod(left: Method, right: Method): Boolean =
        left.declaringClass == right.declaringClass &&
            left.name == right.name &&
            left.returnType == right.returnType &&
            left.parameterTypes.contentEquals(right.parameterTypes)

    private fun isUniqueQueuePosition(autoDj: Any, position: Int): Boolean {
        if (position < 0) return false
        return runCatching {
            val context = GmmpQueueReader().read(autoDj, position)
                ?: return@runCatching false
            context.currentQueuePosition == position &&
                context.items.count { it.queuePosition == position } == 1 &&
                context.items.count { it.state == QueueItemState.CURRENT } == 1
        }.getOrDefault(false)
    }

    private fun scheduleDelayedObservation(
        service: Any,
        autoDj: Any,
        method: Method,
        looper: Looper,
        beforeValue: Int,
        argument: Int,
        depth: Int
    ) {
        if (verified != null) return
        val observation = Pending(
            service = WeakReference(service),
            autoDj = WeakReference(autoDj),
            method = method,
            looper = looper,
            beforeValue = beforeValue,
            argument = argument,
            depth = depth,
            createdAt = SystemClock.elapsedRealtime()
        )
        synchronized(pendingLock) {
            prunePendingLocked(observation.createdAt)
            pending.addLast(observation)
        }
        val handler = Handler(looper)
        DELAYED_CHECKS_MS.forEach { delay ->
            handler.postDelayed(
                { verifyDelayedTransition(service, autoDj) },
                delay
            )
        }
    }

    private fun verifyDelayedTransition(service: Any, autoDj: Any) {
        if (verified != null) return
        val reading = readSignal(autoDj) ?: return
        val now = SystemClock.elapsedRealtime()
        val recent = synchronized(pendingLock) {
            prunePendingLocked(now)
            pending.filter { candidate ->
                candidate.service.get() === service &&
                    candidate.autoDj.get() === autoDj &&
                    candidate.beforeValue != reading.value
            }
        }
        if (recent.isEmpty()) return

        val matches = recent.filter { it.argument == reading.value }
        if (matches.isEmpty()) {
            val key = System.identityHashCode(autoDj).toString() + "|" + reading.value
            if (reportedUnmatchedTransitions.add(key)) {
                Log.w(
                    TAG,
                    "QUEUE POSITION TRANSITION UNMATCHED | position=" +
                        reading.value + " | recent=" +
                        recent.joinToString(",") {
                            it.method.declaringClass.name + "." + it.method.name +
                                "=" + it.argument
                        }
                )
            }
            return
        }

        val matchesDeepest = matches.maxOf { it.depth }
        val deepestMatches = matches.filter { it.depth == matchesDeepest }
        val methods = deepestMatches.distinctBy {
            it.method.declaringClass.name + "|" + it.method.name + "|" +
                it.method.parameterTypes.joinToString(",") { type -> type.name }
        }
        if (methods.size != 1) {
            Log.w(
                TAG,
                "QUEUE POSITION WRITER DELAYED AMBIGUOUS | position=" +
                    reading.value + " | candidates=" +
                    methods.joinToString(",") {
                        it.method.declaringClass.name + "." + it.method.name
                    }
            )
            synchronized(pendingLock) {
                pending.removeAll { candidate ->
                    candidate.service.get() === service &&
                        candidate.autoDj.get() === autoDj &&
                        candidate.argument == reading.value
                }
            }
            return
        }

        val proof = methods.single()
        record(
            receiver = service,
            autoDj = autoDj,
            method = proof.method,
            looper = proof.looper,
            signalSource = reading.source,
            readback = null
        )
        synchronized(pendingLock) {
            pending.removeAll { candidate ->
                candidate.service.get() === service &&
                    candidate.autoDj.get() === autoDj
            }
        }
    }

    private fun prunePendingLocked(now: Long) {
        while (pending.isNotEmpty()) {
            val first = pending.first()
            val expired = now - first.createdAt > PENDING_MAX_AGE_MS
            val dead = first.service.get() == null || first.autoDj.get() == null
            if (!expired && !dead) break
            pending.removeFirst()
        }
    }

    @Synchronized
    private fun record(
        receiver: Any,
        autoDj: Any,
        method: Method,
        looper: Looper?,
        signalSource: String,
        readback: NativeQueuePositionCausalReadbackPolicy.Readback?
    ) {
        val existing = verified
        if (existing != null &&
            (existing.receiver.get() !== receiver ||
                existing.autoDj.get() !== autoDj ||
                !sameMethod(existing.method, method))
        ) {
            Log.w(
                TAG,
                "QUEUE POSITION WRITER AMBIGUOUS | keeping first passive proof"
            )
            return
        }
        if (existing == null) {
            method.isAccessible = true
            verified = Verified(
                WeakReference(receiver),
                WeakReference(autoDj),
                method,
                looper,
                signalSource,
                readback
            )
            Log.i(
                TAG,
                "QUEUE POSITION WRITER VERIFIED | writer=" +
                    method.declaringClass.name + "." + method.name +
                    "(int) | signal=" + signalSource
            )
        }
    }
}