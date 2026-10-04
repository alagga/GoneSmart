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
 * exactly to the method's Int argument. Nested candidate calls are tracked so
 * only the deepest method that actually caused the transition is retained.
 */
internal class NativeQueuePositionWriterObserver(
    private val readSignal: (Any) -> NativeQueuePositionSignal.Reading? =
        NativeQueuePositionSignal::read
) {
    companion object {
        private const val TAG = "GoneSmartFlip"
    }

    private data class Frame(var childMatched: Boolean = false)

    private data class Verified(
        val service: WeakReference<Any>,
        val autoDj: WeakReference<Any>,
        val method: Method,
        val looper: Looper,
        val signalSource: String
    )

    private val stack = ThreadLocal.withInitial { ArrayDeque<Frame>() }

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
