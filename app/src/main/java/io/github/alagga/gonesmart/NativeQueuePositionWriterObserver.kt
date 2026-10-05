package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal interface NativeQueuePositionWriter {
    val description: String
    fun read(): Int?
    fun write(position: Int): Boolean
}

/**
 * Learns GMMP's writable current-position boundary only from NORMAL GMMP
 * behavior. Candidate methods are never probed. A method becomes eligible
 * only when one of its natural calls changes the independent ur-backed signal
 * exactly to the method's Int argument.
 *
 * GMMP 4.2.1 may publish the new current position shortly after the natural
 * MusicService call has returned, so discovery checks both the immediate
 * return and a short bounded delayed window. Delayed matches remain
 * fail-closed: all matching calls in that window are compared and only one
 * unique deepest candidate may graduate to a writer.
 */
internal class NativeQueuePositionWriterObserver(
    private val readSignal: (Any) -> NativeQueuePositionSignal.Reading? =
        NativeQueuePositionSignal::read
) {
    companion object {
        private const val TAG = "GoneSmartFlip"
        private const val PENDING_MAX_AGE_MS = 650L
        private val DELAYED_CHECKS_MS = longArrayOf(40L, 120L, 280L)
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
        val service: WeakReference<Any>,
        val autoDj: WeakReference<Any>,
        val method: Method,
        val looper: Looper,
        val signalSource: String
    )

    private val stack = ThreadLocal.withInitial { ArrayDeque<Frame>() }
    private val pendingLock = Any()
    private val pending = ArrayDeque<Pending>()
    private val reportedUnmatchedTransitions =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var verified: Verified? = null

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
        val before = readSignal(autoDj)
        val frame = Frame()
        val frames = stack.get()
        val depth = frames.size
        frames.addLast(frame)
        try {
            return proceed()
        } finally {
            frames.removeLast()
            val after = readSignal(autoDj)
            val changedToArgument =
                before != null && after != null &&
                    before.value != after.value && after.value == argument
            if (changedToArgument) {
                frames.lastOrNull()?.childMatched = true
                if (!frame.childMatched) {
                    val looper = Looper.myLooper()
                    if (looper != null) {
                        record(service, autoDj, method, looper, after!!.source)
                    }
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
            if (frames.isEmpty()) stack.remove()
        }
    }

    fun binding(autoDj: Any): NativeQueuePositionWriter? {
        val proof = verified ?: return null
        if (proof.autoDj.get() !== autoDj) return null
        val service = proof.service.get() ?: return null
        return object : NativeQueuePositionWriter {
            override val description: String =
                proof.method.declaringClass.name + "." + proof.method.name +
                    "(int) via " + proof.signalSource

            override fun read(): Int? = readSignal(autoDj)?.value

            override fun write(position: Int): Boolean {
                val before = read() ?: return false
                if (before == position) return true
                val invoke = {
                    runCatching {
                        proof.method.isAccessible = true
                        proof.method.invoke(service, position)
                    }.isSuccess
                }
                val invoked = if (Looper.myLooper() == proof.looper) {
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
                    if (read() == position) return true
                    Thread.sleep(20L)
                } while (SystemClock.elapsedRealtime() < deadline)
                return false
            }
        }
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

        val deepest = matches.maxOf { it.depth }
        val deepestMatches = matches.filter { it.depth == deepest }
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
            service = service,
            autoDj = autoDj,
            method = proof.method,
            looper = proof.looper,
            signalSource = reading.source
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
        service: Any,
        autoDj: Any,
        method: Method,
        looper: Looper,
        signalSource: String
    ) {
        val existing = verified
        if (existing != null &&
            (existing.service.get() !== service ||
                existing.autoDj.get() !== autoDj ||
                existing.method != method)
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
                WeakReference(service),
                WeakReference(autoDj),
                method,
                looper,
                signalSource
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
