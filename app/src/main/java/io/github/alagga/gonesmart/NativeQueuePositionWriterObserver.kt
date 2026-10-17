package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal interface NativeQueuePositionWriter {
    val description: String
    fun read(): Int?
    fun write(position: Int): Boolean

    /**
     * Re-dispatch the already verified native position writer even when the
     * requested position is already Current. This is deliberately separate
     * from write(): ordinary queue mutation keeps its no-op optimization,
     * while Track Mix may use the native notification boundary once after a
     * CLEAR_QUEUE/refill cycle to rebuild GMMP's current/next AudioSources.
     */
    fun refreshCurrent(position: Int): Boolean
}

/**
 * Learns GMMP's writable current-position boundary only from NORMAL GMMP
 * behavior. Candidate methods are never probed.
 *
 * GMMP 4.2.1 naturally invokes the structurally unique Auto-DJ child
 * Int->void writer (observed as dx3.c2(int)) with the absolute queue_position
 * it is switching to. The natural invocation is promoted only when the cheap,
 * independently verified Auto-DJ position signal proves the transition. A
 * broader same-host readback remains a bounded compatibility fallback only.
 *
 * Once a writer has been proved for the live Auto-DJ instance, discovery is
 * retired from the playback hot path: subsequent natural calls proceed
 * without reflection, delayed callbacks, or queue SQL. This is important
 * because some innocent-looking zero-arg GMMP getters perform queue lookups.
 *
 * Identification is deliberately separate from controlled-write success:
 * a Queue Flip write succeeds only when the verified native readback reaches
 * the requested position and that queue_position still exists uniquely.
 */
internal class NativeQueuePositionWriterObserver(
    private val readSignal: (Any) -> NativeQueuePositionSignal.Reading? =
        NativeQueuePositionSignal::read
) {
    companion object {
        private const val TAG = "GoneSmartFlip"
        private const val WRITE_DISPATCH_TIMEOUT_MS = 4_000L
        private const val WRITE_READBACK_TIMEOUT_MS = 1_500L
        private val DELAYED_SIGNAL_CHECKS_MS = longArrayOf(40L, 120L, 280L)
    }

    private data class Verified(
        val receiver: WeakReference<Any>,
        val autoDj: WeakReference<Any>,
        val method: Method,
        val looper: Looper?,
        val signalSource: String,
        val sameHostReader: NativeQueueObservedStateBinding.Reader?
    )

    private data class PassiveTrackMixCandidate(
        val receiver: WeakReference<Any>,
        val autoDj: WeakReference<Any>,
        val method: Method,
        val looper: Looper?,
        val lastArgument: Int,
        val observedAt: Long
    )

    private val controlledWrite = ThreadLocal<Boolean>()

    @Volatile
    private var passiveTrackMixCandidate: PassiveTrackMixCandidate? = null

    @Volatile
    private var passiveTrackMixAmbiguous = false
    private val stateWriterSignatures = ConcurrentHashMap<Class<*>, Set<String>>()
    private val broadFallbackAttempted = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var verified: Verified? = null

    fun aroundNaturalInvocation(
        service: Any?,
        method: Method,
        argument: Int?,
        autoDj: Any?,
        proceed: () -> Any?
    ): Any? {
        if (controlledWrite.get() == true) return proceed()
        if (service == null || argument == null || autoDj == null) {
            return proceed()
        }

        // Discovery is complete for this live Auto-DJ instance. Do not keep
        // sampling native state on every playback callback: r41 device logs
        // showed that the old fallback could execute hundreds of GMMP queue
        // queries per second after the semantic boundary was already known.
        verified?.let { proof ->
            if (proof.autoDj.get() === autoDj && proof.receiver.get() != null) {
                return proceed()
            }
        }

        val stateWriter = isStateWriter(autoDj.javaClass, method)
        val before = readSignal(autoDj)
        val invocationLooper = Looper.myLooper()
        val result = proceed()
        val after = readSignal(autoDj)

        // The accepted 4.2.1 proof is intentionally cheap: qr.p/dx3 exposes
        // the independently verified integer field directly. Prefer it before
        // any generic getter scan, because arbitrary GMMP getters may touch DB.
        val nativeSignalProof =
            before != null && after != null &&
                before.value != after.value && after.value == argument
        if (nativeSignalProof && isUniqueQueuePosition(autoDj, argument)) {
            record(
                receiver = service,
                autoDj = autoDj,
                method = method,
                looper = invocationLooper,
                signalSource = after!!.source,
                sameHostReader = null
            )
            return result
        }

        if (stateWriter) {
            recordPassiveTrackMixCandidate(
                receiver = service,
                autoDj = autoDj,
                method = method,
                looper = invocationLooper,
                argument = argument,
                observedAt = SystemClock.elapsedRealtime()
            )
        }

        if (!stateWriter) return result

        // Compatibility fallback for a future GMMP shape. Run the broad
        // same-host accessor analysis at most ONCE per concrete method in a
        // process; never on each natural playback invocation.
        val fallbackKey = methodKey(method)
        if (broadFallbackAttempted.add(fallbackKey) &&
            isUniqueQueuePosition(autoDj, argument)
        ) {
            val sameHostProof = NativeQueueObservedStateBinding.resolve(
                host = service,
                observedWriter = method,
                observedValue = argument
            )
            if (sameHostProof != null) {
                record(
                    receiver = service,
                    autoDj = autoDj,
                    method = method,
                    looper = invocationLooper,
                    signalSource = "same-host:" + sameHostProof.reader.description,
                    sameHostReader = sameHostProof.reader
                )
                return result
            }
        }

        // Delayed observation is cheap-only. It samples the already accepted
        // native position signal and never repeats generic reflection scans.
        scheduleDelayedObservation(
            receiver = service,
            autoDj = autoDj,
            method = method,
            argument = argument,
            looper = invocationLooper,
            beforeValue = before?.value
        )
        return result
    }

    fun hasVerifiedWriter(autoDj: Any): Boolean {
        val proof = verified ?: return false
        return proof.autoDj.get() === autoDj && proof.receiver.get() != null
    }

    fun binding(autoDj: Any): NativeQueuePositionWriter? {
        val proof = verified ?: return null
        if (proof.autoDj.get() !== autoDj) return null
        val receiver = proof.receiver.get() ?: return null
        return object : NativeQueuePositionWriter {
            override val description: String =
                proof.method.declaringClass.name + "." + proof.method.name +
                    "(int) via " + proof.signalSource

            override fun read(): Int? =
                proof.sameHostReader?.read(receiver)
                    ?: readSignal(autoDj)?.value

            override fun write(position: Int): Boolean {
                val before = read() ?: return false
                if (before == position) {
                    return isUniqueQueuePosition(autoDj, position)
                }
                return invokeAndVerify(position, "WRITE")
            }

            override fun refreshCurrent(position: Int): Boolean {
                val before = read() ?: return false
                if (before != position || !isUniqueQueuePosition(autoDj, position)) {
                    return false
                }
                return invokeAndVerify(position, "REFRESH")
            }

            private fun invokeAndVerify(position: Int, operation: String): Boolean {
                val invoked = invokeControlled(
                    proof = proof,
                    receiver = receiver,
                    autoDj = autoDj,
                    position = position
                )
                if (!invoked) return false

                val deadline = SystemClock.elapsedRealtime() + WRITE_READBACK_TIMEOUT_MS
                do {
                    val nativePosition = read()
                    if (nativePosition == position &&
                        isUniqueQueuePosition(autoDj, position)
                    ) {
                        Log.i(
                            TAG,
                            "QUEUE POSITION $operation VERIFIED | writer=" +
                                proof.method.declaringClass.name + "." +
                                proof.method.name + " | position=" + position
                        )
                        return true
                    }
                    Thread.sleep(20L)
                } while (SystemClock.elapsedRealtime() < deadline)

                Log.e(
                    TAG,
                    "QUEUE POSITION $operation REJECTED | writer=" +
                        proof.method.declaringClass.name + "." +
                        proof.method.name + " | requested=" + position +
                        " | nativeRead=" + (read()?.toString() ?: "unavailable")
                )
                return false
            }
        }
    }

    /**
     * Track Mix may need to move the exact CURRENT row from a Smart-source
     * absolute queue_position to 1. r36 device evidence showed the naturally
     * observed state method may use a zero-based coordinate (for example a
     * natural argument 4 while GMMP owns queue_position 5). Keep this mapping
     * request-local: correlate the latest naturally observed structural state
     * writer and native read signal with the already pinned CURRENT, accepting
     * only exact offsets 0 or 1. Never promote this weaker mapping to Queue
     * Flip's global writer proof.
     */
    fun trackMixBinding(
        autoDj: Any,
        currentQueuePosition: Int
    ): NativeQueuePositionWriter? {
        binding(autoDj)?.let { absolute ->
            if (runCatching { absolute.read() }.getOrNull() == currentQueuePosition) {
                return absolute
            }
        }
        if (passiveTrackMixAmbiguous) return null
        val candidate = passiveTrackMixCandidate ?: return null
        if (candidate.autoDj.get() !== autoDj) return null
        val receiver = candidate.receiver.get() ?: return null
        val reading = readSignal(autoDj) ?: return null
        val mapping = TrackMixZeroPoolPolicy.resolveStateWriterMapping(
            currentQueuePosition = currentQueuePosition,
            observedArgument = candidate.lastArgument,
            signalValue = reading.value,
            ageMs = SystemClock.elapsedRealtime() - candidate.observedAt
        ) ?: return null
        val provisional = Verified(
            receiver = candidate.receiver,
            autoDj = candidate.autoDj,
            method = candidate.method,
            looper = candidate.looper,
            signalSource = reading.source,
            sameHostReader = null
        )

        Log.i(
            TAG,
            "QUEUE POSITION TRACK MIX MAPPING | writer=" +
                candidate.method.declaringClass.name + "." + candidate.method.name +
                "(int) | queuePosition=" + currentQueuePosition +
                " | naturalArg=" + candidate.lastArgument +
                " | signal=" + reading.value +
                " | argumentOffset=" + mapping.argumentOffset +
                " | signalOffset=" + mapping.signalOffset +
                " | requestScoped=true"
        )

        return object : NativeQueuePositionWriter {
            override val description: String =
                candidate.method.declaringClass.name + "." + candidate.method.name +
                    "(int) via track-mix-offset(arg=" + mapping.argumentOffset +
                    ",signal=" + mapping.signalOffset + ")"

            override fun read(): Int? =
                readSignal(autoDj)?.value?.let { nativeValue ->
                    nativeValue + mapping.signalOffset
                }?.takeIf { it > 0 }

            override fun write(position: Int): Boolean {
                if (position <= 0) return false
                val nativeArgument = position - mapping.argumentOffset
                if (nativeArgument < 0) return false
                if (!invokeControlled(
                        proof = provisional,
                        receiver = receiver,
                        autoDj = autoDj,
                        position = nativeArgument
                    )
                ) return false

                val deadline = SystemClock.elapsedRealtime() + WRITE_READBACK_TIMEOUT_MS
                do {
                    val mappedPosition = read()
                    if (mappedPosition == position &&
                        isUniqueQueuePosition(autoDj, position)
                    ) {
                        Log.i(
                            TAG,
                            "QUEUE POSITION TRACK MIX WRITE VERIFIED | writer=" +
                                candidate.method.declaringClass.name + "." +
                                candidate.method.name +
                                " | queuePosition=" + position +
                                " | nativeArgument=" + nativeArgument +
                                " | requestScoped=true"
                        )
                        return true
                    }
                    Thread.sleep(20L)
                } while (SystemClock.elapsedRealtime() < deadline)
                return false
            }

            override fun refreshCurrent(position: Int): Boolean = false
        }
    }

    @Synchronized
    private fun recordPassiveTrackMixCandidate(
        receiver: Any,
        autoDj: Any,
        method: Method,
        looper: Looper?,
        argument: Int,
        observedAt: Long
    ) {
        val existing = passiveTrackMixCandidate
        if (existing != null && existing.autoDj.get() !== autoDj) {
            passiveTrackMixCandidate = null
            passiveTrackMixAmbiguous = false
        }
        val current = passiveTrackMixCandidate
        if (current == null) {
            method.isAccessible = true
            passiveTrackMixCandidate = PassiveTrackMixCandidate(
                receiver = WeakReference(receiver),
                autoDj = WeakReference(autoDj),
                method = method,
                looper = looper,
                lastArgument = argument,
                observedAt = observedAt
            )
            Log.i(
                TAG,
                "QUEUE POSITION TRACK MIX CANDIDATE | writer=" +
                    method.declaringClass.name + "." + method.name +
                    "(int) | natural=true"
            )
            return
        }
        if (current.receiver.get() === receiver &&
            current.autoDj.get() === autoDj &&
            sameMethod(current.method, method)
        ) {
            passiveTrackMixCandidate = current.copy(
                looper = looper,
                lastArgument = argument,
                observedAt = observedAt
            )
            return
        }

        passiveTrackMixAmbiguous = true
        Log.w(
            TAG,
            "QUEUE POSITION TRACK MIX AMBIGUOUS | zero-pool rebase disabled"
        )
    }

    private fun invokeControlled(
        proof: Verified,
        receiver: Any,
        autoDj: Any,
        position: Int
    ): Boolean {
        val invoke = {
            controlledWrite.set(true)
            try {
                runCatching {
                    proof.method.isAccessible = true
                    proof.method.invoke(receiver, position)
                }.isSuccess
            } finally {
                controlledWrite.remove()
            }
        }

        if (proof.looper != null) {
            if (Looper.myLooper() == proof.looper) return invoke()
            val done = CountDownLatch(1)
            val started = AtomicBoolean(false)
            var ok = false
            val handler = Handler(proof.looper)
            val task = Runnable {
                started.set(true)
                try {
                    ok = invoke()
                } finally {
                    done.countDown()
                }
            }
            if (!handler.post(task)) return false
            if (done.await(WRITE_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return ok
            }
            if (!started.get()) {
                handler.removeCallbacks(task)
                Log.w(
                    TAG,
                    "QUEUE POSITION DISPATCH TIMEOUT | writer=" +
                        proof.method.declaringClass.name + "." + proof.method.name +
                        " | route=looper | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                        " | started=false | cancelled=true"
                )
                return false
            }
            val finishedInGrace =
                done.await(WRITE_READBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.w(
                TAG,
                "QUEUE POSITION DISPATCH DELAY | writer=" +
                    proof.method.declaringClass.name + "." + proof.method.name +
                    " | route=looper | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                    " | started=true | finishedInGrace=$finishedInGrace"
            )
            return finishedInGrace && ok
        }

        val executor = nativeExecutor(autoDj)
        if (executor != null) {
            val started = AtomicBoolean(false)
            val future = executor.submit<Boolean> {
                started.set(true)
                invoke()
            }
            return try {
                future.get(WRITE_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (!started.get()) {
                    val cancelled = future.cancel(false)
                    Log.w(
                        TAG,
                        "QUEUE POSITION DISPATCH TIMEOUT | writer=" +
                            proof.method.declaringClass.name + "." + proof.method.name +
                            " | route=executor | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                            " | started=false | cancelled=$cancelled"
                    )
                    false
                } else {
                    val finished = runCatching {
                        future.get(WRITE_READBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }.getOrNull()
                    Log.w(
                        TAG,
                        "QUEUE POSITION DISPATCH DELAY | writer=" +
                            proof.method.declaringClass.name + "." + proof.method.name +
                            " | route=executor | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                            " | started=true | finishedInGrace=${finished != null}"
                    )
                    finished ?: false
                }
            } catch (_: Throwable) {
                false
            }
        }
        return invoke()
    }

    private fun nativeExecutor(autoDj: Any): ExecutorService? {
        val values = hierarchyFields(autoDj.javaClass)
            .asSequence()
            .filter { field ->
                !Modifier.isStatic(field.modifiers) &&
                    ExecutorService::class.java.isAssignableFrom(field.type)
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(autoDj) as? ExecutorService
                }.getOrNull()
            }
            .distinctBy(System::identityHashCode)
            .toList()
        return values.singleOrNull()
    }

    private fun scheduleDelayedObservation(
        receiver: Any,
        autoDj: Any,
        method: Method,
        argument: Int,
        looper: Looper?,
        beforeValue: Int?
    ) {
        if (looper == null) return
        DELAYED_SIGNAL_CHECKS_MS.forEach { delay ->
            Handler(looper).postDelayed(
                {
                    verified?.let { proof ->
                        if (proof.autoDj.get() === autoDj) return@postDelayed
                    }
                    val reading = readSignal(autoDj) ?: return@postDelayed
                    if (reading.value != argument ||
                        reading.value == beforeValue
                    ) return@postDelayed
                    if (!isUniqueQueuePosition(autoDj, argument)) {
                        return@postDelayed
                    }
                    record(
                        receiver = receiver,
                        autoDj = autoDj,
                        method = method,
                        looper = looper,
                        signalSource = reading.source,
                        sameHostReader = null
                    )
                },
                delay
            )
        }
    }

    private fun isStateWriter(autoDjClass: Class<*>, method: Method): Boolean {
        val signatures = stateWriterSignatures.computeIfAbsent(autoDjClass) {
            NativeQueuePlaybackDiagnostics.stateWriterMethods(it)
                .map(::methodKey)
                .toSet()
        }
        return methodKey(method) in signatures
    }

    private fun methodKey(method: Method): String =
        method.declaringClass.name + "|" + method.name + "|" +
            method.returnType.name + "|" +
            method.parameterTypes.joinToString(",") { it.name }

    private fun isUniqueQueuePosition(autoDj: Any, position: Int): Boolean {
        if (position < 0) return false
        return runCatching {
            val count = GmmpReadOnlySql.query(
                autoDjInstance = autoDj,
                sql = "SELECT COUNT(*) AS match_count FROM queue_table " +
                    "WHERE queue_position = $position"
            ) { cursor ->
                if (!cursor.moveToFirst()) {
                    0
                } else {
                    val column = cursor.getColumnIndex("match_count")
                    if (column < 0) 0 else cursor.getInt(column)
                }
            }
            count == 1
        }.getOrDefault(false)
    }

    @Synchronized
    private fun record(
        receiver: Any,
        autoDj: Any,
        method: Method,
        looper: Looper?,
        signalSource: String,
        sameHostReader: NativeQueueObservedStateBinding.Reader?
    ) {
        val existing = verified
        if (existing != null) {
            if (existing.receiver.get() !== receiver ||
                existing.autoDj.get() !== autoDj ||
                !sameMethod(existing.method, method)
            ) {
                Log.w(
                    TAG,
                    "QUEUE POSITION WRITER AMBIGUOUS | keeping first passive proof"
                )
            }
            return
        }
        method.isAccessible = true
        verified = Verified(
            receiver = WeakReference(receiver),
            autoDj = WeakReference(autoDj),
            method = method,
            looper = looper,
            signalSource = signalSource,
            sameHostReader = sameHostReader
        )
        Log.i(
            TAG,
            "QUEUE POSITION WRITER VERIFIED | writer=" +
                method.declaringClass.name + "." + method.name +
                "(int) | signal=" + signalSource +
                " | discovery=retired"
        )
    }

    private fun sameMethod(left: Method, right: Method): Boolean =
        left.declaringClass == right.declaringClass &&
            left.name == right.name &&
            left.returnType == right.returnType &&
            left.parameterTypes.contentEquals(right.parameterTypes)

    private fun hierarchyFields(type: Class<*>): List<java.lang.reflect.Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .toList()
}
