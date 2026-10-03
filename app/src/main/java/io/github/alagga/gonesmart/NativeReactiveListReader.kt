package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Read-only bridge for host reactive DAO return types whose concrete Rx/
 * observable class names and method names are R8-renamed.
 *
 * A source is eligible only after the caller has obtained it from a no-arg
 * method on GMMP's native Queue DAO. This reader may materialize a direct,
 * blocking, callback or shallow nested snapshot, but it never chooses a
 * writer. GmmpQueueMutationBridge remains the authority that must correlate
 * queue_id, song_id and queue_position one-to-one with the live read-only
 * Cursor before any native mutation method becomes eligible.
 */
internal object NativeReactiveListReader {
    private const val TAG = "GoneSmartQueue"

    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "GoneSmartNativeReactiveRead").apply { isDaemon = true }
    }

    private val reportedFailureShapes =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    data class Result(
        val rows: List<Any>,
        val boundary: String
    )

    private data class SubscribeAttempt(
        val rows: List<Any>?,
        val invocationFailure: Throwable? = null
    )

    private data class Invocation(
        val returned: Any?,
        val failure: Throwable?
    )

    fun read(
        source: Any,
        expectedRows: Int,
        timeoutMs: Long = 900L,
        expectedModelClass: Class<*>? = null,
        allowPartial: Boolean = false
    ): Result? {
        if (expectedRows <= 0) return null

        var bestPartial: Result? = null
        fun keepPartial(rows: List<Any>, boundary: String) {
            if (!allowPartial || rows.isEmpty()) return
            if (bestPartial == null || rows.size > bestPartial!!.rows.size) {
                bestPartial = Result(rows, boundary)
            }
        }

        directList(
            source = source,
            expectedRows = expectedRows,
            expectedModelClass = expectedModelClass,
            allowPartial = allowPartial
        )?.let { rows ->
            if (rows.size == expectedRows) {
                return Result(rows, "direct")
            }
            keepPartial(rows, "direct")
        }

        blockingList(
            source = source,
            expectedRows = expectedRows,
            timeoutMs = timeoutMs,
            expectedModelClass = expectedModelClass,
            allowPartial = allowPartial
        )?.let { (rows, boundary) ->
            if (rows.size == expectedRows) {
                return Result(rows, boundary)
            }
            keepPartial(rows, boundary)
        }

        val callbackCandidates =
            GmmpReflectionPolicy.callableMethods(source.javaClass)
                .asSequence()
                .filter { !Modifier.isStatic(it.modifiers) }
                .filter { it.parameterCount == 1 }
                .filter { it.parameterTypes.single().isInterface }
                // Transformation operators commonly return the source family
                // again. A terminal subscription/observation boundary is void
                // or returns a different disposable/subscription family.
                .filter {
                    it.returnType == java.lang.Void.TYPE ||
                        !it.returnType.isAssignableFrom(source.javaClass)
                }
                .distinctBy {
                    it.declaringClass.name + "|" + it.name + "|" +
                        it.parameterTypes.single().name + "|" +
                        it.returnType.name
                }
                .take(16)
                .toList()

        val voidCandidates = callbackCandidates.filter {
            it.returnType == java.lang.Void.TYPE
        }
        val methods = (voidCandidates.ifEmpty { callbackCandidates })
            .sortedByDescending { method ->
                observerScore(method.parameterTypes.single())
            }

        for (method in methods) {
            val background = subscribeOnce(
                source = source,
                method = method,
                expectedRows = expectedRows,
                timeoutMs = timeoutMs,
                expectedModelClass = expectedModelClass,
                allowPartial = allowPartial,
                invokeOnMain = false
            )
            background.rows?.let { rows ->
                val boundary = callbackBoundary(source, method, "callback")
                if (rows.size == expectedRows) {
                    return Result(rows, boundary)
                }
                keepPartial(rows, boundary)
            }

            // Lifecycle-style observables reject observer registration away
            // from Android's main thread. Retry only after the first invoke
            // actually failed; a merely quiet Rx source is never subscribed a
            // second time speculatively.
            if (
                background.rows == null &&
                background.invocationFailure != null &&
                method.returnType == java.lang.Void.TYPE
            ) {
                val mainThread = subscribeOnce(
                    source = source,
                    method = method,
                    expectedRows = expectedRows,
                    timeoutMs = timeoutMs,
                    expectedModelClass = expectedModelClass,
                    allowPartial = allowPartial,
                    invokeOnMain = true
                )
                mainThread.rows?.let { rows ->
                    val boundary = callbackBoundary(
                        source,
                        method,
                        "main-callback"
                    )
                    if (rows.size == expectedRows) {
                        return Result(rows, boundary)
                    }
                    keepPartial(rows, boundary)
                }
            }
        }

        if (bestPartial == null) {
            reportFailureShape(source)
        }
        return bestPartial
    }

    private fun callbackBoundary(
        source: Any,
        method: Method,
        mode: String
    ): String =
        source.javaClass.name + "." + method.name +
            "(" + method.parameterTypes.single().name + "):" + mode

    private fun blockingList(
        source: Any,
        expectedRows: Int,
        timeoutMs: Long,
        expectedModelClass: Class<*>?,
        allowPartial: Boolean
    ): Pair<List<Any>, String>? {
        val methods = GmmpReflectionPolicy.callableMethods(source.javaClass)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == Any::class.java &&
                    it.declaringClass != Any::class.java &&
                    it.name != "toString" &&
                    it.name != "clone"
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" +
                    it.returnType.name
            }
            .take(12)

        for (method in methods) {
            val future = executor.submit<Any?> {
                method.isAccessible = true
                method.invoke(source)
            }
            val returned = runCatching {
                future.get(
                    timeoutMs.coerceAtMost(650L).coerceAtLeast(50L),
                    TimeUnit.MILLISECONDS
                )
            }.getOrElse {
                future.cancel(true)
                null
            }
            val rows = extractRowsDeep(
                value = returned,
                expectedRows = expectedRows,
                expectedModelClass = expectedModelClass,
                allowPartial = allowPartial,
                depth = 2
            ) ?: continue
            return rows to (
                source.javaClass.name + "." + method.name +
                    "():blocking-object"
                )
        }
        return null
    }

    private fun directList(
        source: Any,
        expectedRows: Int,
        expectedModelClass: Class<*>?,
        allowPartial: Boolean
    ): List<Any>? {
        // Only the source value itself may be a direct snapshot. Do not inspect
        // arbitrary private fields here: doing so bypasses the carrier's native
        // blocking/callback terminal and can mistake implementation state for
        // the actual emitted DAO value.
        extractRowsDirect(
            value = source,
            expectedRows = expectedRows,
            expectedModelClass = expectedModelClass,
            allowPartial = allowPartial
        )?.let { return it }

        val candidates = GmmpReflectionPolicy.callableMethods(source.javaClass)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
        for (method in candidates.take(8)) {
            val returned = runCatching {
                method.isAccessible = true
                method.invoke(source)
            }.getOrNull()
            val accepted = extractRowsDeep(
                value = returned,
                expectedRows = expectedRows,
                expectedModelClass = expectedModelClass,
                allowPartial = allowPartial,
                depth = 2
            )
            if (accepted != null) return accepted
        }
        return null
    }

    private fun subscribeOnce(
        source: Any,
        method: Method,
        expectedRows: Int,
        timeoutMs: Long,
        expectedModelClass: Class<*>?,
        allowPartial: Boolean,
        invokeOnMain: Boolean
    ): SubscribeAttempt {
        val callbackType = method.parameterTypes.single()
        val callbackMethods = callbackType.methods
        val canReceiveValue = callbackMethods.any {
            it.parameterCount == 1 &&
                !Throwable::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (!canReceiveValue) return SubscribeAttempt(null)

        val result = AtomicReference<List<Any>?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val cancellation = AtomicReference<Any?>(null)
        val streamedRows = java.util.Collections.synchronizedList(
            arrayListOf<Any>()
        )
        val streamedRowClass = AtomicReference<Class<*>?>(null)
        val queueEntityShapeCache =
            java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()

        fun acceptRows(rows: List<Any>) {
            if (rows.isEmpty()) return
            val model = expectedModelClass ?: rows.first().javaClass
            if (!rows.all(model::isInstance)) return
            val expectedClass = streamedRowClass.get()
            if (expectedClass == null) {
                streamedRowClass.compareAndSet(null, model)
            }
            if (streamedRowClass.get() != model) return
            synchronized(streamedRows) {
                rows.forEach { row ->
                    if (streamedRows.none { it === row }) {
                        streamedRows.add(row)
                    }
                }
                if (streamedRows.size == expectedRows) {
                    result.compareAndSet(null, streamedRows.toList())
                    latch.countDown()
                }
            }
        }

        val callback = Proxy.newProxyInstance(
            callbackType.classLoader ?: source.javaClass.classLoader,
            arrayOf(callbackType)
        ) { proxy, invoked, args ->
            when (invoked.name) {
                "toString" ->
                    return@newProxyInstance "GoneSmart reactive queue reader"
                "hashCode" ->
                    return@newProxyInstance System.identityHashCode(proxy)
                "equals" ->
                    return@newProxyInstance proxy === args?.firstOrNull()
            }

            args.orEmpty().forEach { value ->
                when (value) {
                    is Throwable -> {
                        failure.compareAndSet(null, value)
                        latch.countDown()
                    }
                    null -> Unit
                    else -> {
                        extractRowsDeep(
                            value = value,
                            expectedRows = expectedRows,
                            expectedModelClass = expectedModelClass,
                            allowPartial = true,
                            depth = 2
                        )?.let { rows ->
                            acceptRows(rows)
                            return@forEach
                        }

                        // Reactive Streams Subscriber requires request(n)
                        // before onNext. A subscription/control object must
                        // never be mistaken for a queue row.
                        val requesters =
                            GmmpReflectionPolicy.callableMethods(value.javaClass)
                                .filter {
                                    !Modifier.isStatic(it.modifiers) &&
                                        it.declaringClass != Any::class.java &&
                                        it.parameterCount == 1 &&
                                        it.parameterTypes[0] ==
                                            java.lang.Long.TYPE &&
                                        it.returnType == java.lang.Void.TYPE
                                }
                        if (requesters.size == 1) {
                            runCatching {
                                requesters.single().apply {
                                    isAccessible = true
                                }.invoke(value, Long.MAX_VALUE)
                            }
                            cancellation.compareAndSet(null, value)
                            return@newProxyInstance primitiveDefault(
                                invoked.returnType
                            )
                        }

                        expectedModelClass?.let { model ->
                            embeddedExpectedEntity(value, model)?.let {
                                acceptRows(listOf(it))
                                return@forEach
                            }
                        }

                        val queueRow = when {
                            expectedModelClass?.isInstance(value) == true -> value
                            queueEntityShapeCache.computeIfAbsent(
                                value.javaClass
                            ) { looksLikeQueueEntity(value) } -> value
                            else -> uniqueNestedQueueEntity(
                                value,
                                queueEntityShapeCache
                            )
                        }
                        if (queueRow != null) {
                            acceptRows(listOf(queueRow))
                        } else {
                            cancellation.compareAndSet(null, value)
                        }
                    }
                }
            }
            primitiveDefault(invoked.returnType)
        }

        val invocation = if (invokeOnMain) {
            invokeOnAndroidMain(
                source = source,
                method = method,
                callback = callback,
                timeoutMs = timeoutMs
            )
        } else {
            invokeOnWorker(
                source = source,
                method = method,
                callback = callback,
                timeoutMs = timeoutMs
            )
        }
        if (invocation.failure != null) {
            return SubscribeAttempt(
                rows = null,
                invocationFailure = invocation.failure
            )
        }
        if (invocation.returned != null) {
            cancellation.compareAndSet(null, invocation.returned)
        }

        if (result.get() == null && failure.get() == null) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        cancelIfUnambiguous(cancellation.get())
        result.get()?.let {
            return SubscribeAttempt(it)
        }
        if (allowPartial) {
            synchronized(streamedRows) {
                if (streamedRows.isNotEmpty()) {
                    return SubscribeAttempt(streamedRows.toList())
                }
            }
        }
        return SubscribeAttempt(null, failure.get())
    }

    private fun invokeOnWorker(
        source: Any,
        method: Method,
        callback: Any,
        timeoutMs: Long
    ): Invocation {
        val future = executor.submit<Any?> {
            method.isAccessible = true
            method.invoke(source, callback)
        }
        return try {
            Invocation(
                returned = future.get(
                    timeoutMs.coerceAtMost(650L).coerceAtLeast(50L),
                    TimeUnit.MILLISECONDS
                ),
                failure = null
            )
        } catch (failure: Throwable) {
            future.cancel(true)
            Invocation(null, unwrapInvocationFailure(failure))
        }
    }

    private fun invokeOnAndroidMain(
        source: Any,
        method: Method,
        callback: Any,
        timeoutMs: Long
    ): Invocation {
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
            ?: return Invocation(
                null,
                IllegalStateException("Android main looper unavailable")
            )
        if (runCatching { Looper.myLooper() == mainLooper }.getOrDefault(false)) {
            return runCatching {
                method.isAccessible = true
                Invocation(method.invoke(source, callback), null)
            }.getOrElse {
                Invocation(null, unwrapInvocationFailure(it))
            }
        }

        val returned = AtomicReference<Any?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val posted = runCatching {
            Handler(mainLooper).post {
                runCatching {
                    method.isAccessible = true
                    method.invoke(source, callback)
                }.onSuccess {
                    returned.set(it)
                }.onFailure {
                    failure.set(unwrapInvocationFailure(it))
                }
                latch.countDown()
            }
        }.getOrDefault(false)
        if (!posted) {
            return Invocation(
                null,
                IllegalStateException("Could not post observer to main looper")
            )
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return Invocation(
                null,
                TimeoutException("Main-thread observer registration timed out")
            )
        }
        return Invocation(returned.get(), failure.get())
    }

    private fun unwrapInvocationFailure(failure: Throwable): Throwable =
        when (failure) {
            is java.util.concurrent.ExecutionException ->
                failure.cause ?: failure
            is java.lang.reflect.InvocationTargetException ->
                failure.targetException ?: failure
            else -> failure
        }

    private fun extractRowsDeep(
        value: Any?,
        expectedRows: Int,
        expectedModelClass: Class<*>?,
        allowPartial: Boolean,
        depth: Int
    ): List<Any>? {
        if (value == null) return null

        extractRowsDirect(
            value = value,
            expectedRows = expectedRows,
            expectedModelClass = expectedModelClass,
            allowPartial = allowPartial
        )?.let { return it }

        if (depth <= 0 || isPlatformValue(value)) return null

        val nested = hierarchyFields(value.javaClass)
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
            }
            .filter { it !== value }
            .mapNotNull { child ->
                extractRowsDeep(
                    value = child,
                    expectedRows = expectedRows,
                    expectedModelClass = expectedModelClass,
                    allowPartial = allowPartial,
                    depth = depth - 1
                )
            }
            .distinctBy(::rowSetFingerprint)

        return nested.singleOrNull()
    }

    private fun extractRowsDirect(
        value: Any,
        expectedRows: Int,
        expectedModelClass: Class<*>?,
        allowPartial: Boolean
    ): List<Any>? {
        val rawRows: List<Any> = when {
            expectedModelClass?.isInstance(value) == true ->
                listOf(value)
            value is Iterable<*> ->
                value.filterNotNull().map { it }
            value.javaClass.isArray -> {
                val size = java.lang.reflect.Array.getLength(value)
                (0 until size).mapNotNull { index ->
                    java.lang.reflect.Array.get(value, index)
                }
            }
            expectedModelClass != null ->
                embeddedExpectedEntity(value, expectedModelClass)
                    ?.let(::listOf)
                    ?: return null
            else -> return null
        }
        if (rawRows.isEmpty()) return null

        val rows = if (expectedModelClass == null) {
            val model = rawRows.first().javaClass
            if (!rawRows.all(model::isInstance)) return null
            rawRows
        } else {
            rawRows.map { row ->
                when {
                    expectedModelClass.isInstance(row) -> row
                    else -> embeddedExpectedEntity(
                        row,
                        expectedModelClass
                    ) ?: return null
                }
            }
        }
        if (rows.isEmpty()) return null
        if (expectedModelClass != null &&
            !rows.all(expectedModelClass::isInstance)
        ) return null
        return when {
            rows.size == expectedRows -> rows
            allowPartial && rows.size < expectedRows -> rows
            else -> null
        }
    }

    private fun uniqueNestedQueueEntity(
        wrapper: Any,
        cache: java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>
    ): Any? {
        if (isPlatformValue(wrapper)) return null
        val matches = hierarchyFields(wrapper.javaClass)
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(wrapper)
                }.getOrNull()
            }
            .filter { child ->
                child !== wrapper &&
                    !isPlatformValue(child) &&
                    cache.computeIfAbsent(child.javaClass) {
                        looksLikeQueueEntity(child)
                    }
            }
            .distinctBy(System::identityHashCode)
        return matches.singleOrNull()
    }

    private fun embeddedExpectedEntity(
        wrapper: Any,
        expectedModelClass: Class<*>
    ): Any? {
        if (expectedModelClass.isInstance(wrapper)) return wrapper
        val matches = hierarchyFields(wrapper.javaClass)
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(wrapper)
                }.getOrNull()
            }
            .filter(expectedModelClass::isInstance)
            .distinctBy(System::identityHashCode)
        return matches.singleOrNull()
    }

    private fun observerScore(type: Class<*>): Int {
        val methods = type.methods.filter {
            it.declaringClass != Any::class.java
        }
        val hasError = methods.any {
            it.parameterCount == 1 &&
                Throwable::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        val hasValue = methods.any {
            it.parameterCount == 1 &&
                !Throwable::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        val hasTerminal = methods.any { it.parameterCount == 0 }
        return (if (hasValue) 4 else 0) +
            (if (hasError) 2 else 0) +
            (if (hasTerminal) 1 else 0)
    }

    private fun looksLikeQueueEntity(value: Any): Boolean {
        if (isPlatformValue(value)) return false
        val numericFields = hierarchyFields(value.javaClass)
            .count {
                it.type == Integer.TYPE ||
                    it.type == Integer::class.java ||
                    it.type == java.lang.Long.TYPE ||
                    it.type == java.lang.Long::class.java ||
                    it.type == java.lang.Short.TYPE ||
                    it.type == java.lang.Short::class.java
            }
        return numericFields >= 3
    }

    private fun isPlatformValue(value: Any): Boolean {
        val name = value.javaClass.name
        return name.startsWith("java.") ||
            name.startsWith("android.") ||
            name.startsWith("kotlin.") ||
            value is Number ||
            value is CharSequence ||
            value is Boolean ||
            value is Enum<*>
    }

    private fun hierarchyFields(type: Class<*>) =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
            .toList()

    private fun rowSetFingerprint(rows: List<Any>): String =
        rows.joinToString("|") { row ->
            row.javaClass.name + "@" + System.identityHashCode(row)
        }

    private fun cancelIfUnambiguous(handle: Any?) {
        if (handle == null || isPlatformValue(handle)) return
        val methods = GmmpReflectionPolicy.callableMethods(handle.javaClass)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == java.lang.Void.TYPE &&
                    it.declaringClass != Any::class.java
            }
        if (methods.size == 1) {
            runCatching {
                methods.single().apply { isAccessible = true }.invoke(handle)
            }
        }
    }

    private fun reportFailureShape(source: Any) {
        val key = source.javaClass.name
        if (!reportedFailureShapes.add(key)) return

        val methods = GmmpReflectionPolicy.callableMethods(source.javaClass)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.declaringClass != Any::class.java
            }
        val terminals = methods
            .filter {
                it.parameterCount == 1 &&
                    it.parameterTypes.single().isInterface
            }
            .take(12)
            .joinToString(";") { method ->
                val callback = method.parameterTypes.single()
                val callbackShape = callback.methods
                    .filter { it.declaringClass != Any::class.java }
                    .take(10)
                    .joinToString(",") { cb ->
                        cb.name + "(" +
                            cb.parameterTypes.joinToString(",") { it.name } +
                            "):" + cb.returnType.name
                    }
                method.name + "(" + callback.name + "):" +
                    method.returnType.name +
                    "<" + safeGenericName(method) + ">" +
                    "{" + callbackShape + "}"
            }
            .ifBlank { "none" }
        val noArgObjects = methods
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == Any::class.java
            }
            .take(10)
            .joinToString(",") {
                it.name + "():Object<" + safeGenericName(it) + ">"
            }
            .ifBlank { "none" }
        val fields = hierarchyFields(source.javaClass)
            .take(16)
            .joinToString(",") {
                it.name + ":" + it.type.name
            }
            .ifBlank { "none" }
        val genericInterfaces = runCatching {
            source.javaClass.genericInterfaces.joinToString(",") {
                it.typeName
            }
        }.getOrDefault("none").ifBlank { "none" }
        val genericSuper = runCatching {
            source.javaClass.genericSuperclass?.typeName ?: "none"
        }.getOrDefault("none")

        // Local JVM tests do not provide an implementation for android.util.Log.
        // Diagnostics must never turn a normal fail-closed read into a failure.
        runCatching {
            Log.w(
                TAG,
                "QUEUE REACTIVE READ FAIL | source=" + source.javaClass.name +
                    " | genericSuper=" + genericSuper +
                    " | genericInterfaces=" + genericInterfaces +
                    " | noArgObjects=" + noArgObjects +
                    " | terminals=" + terminals +
                    " | fields=" + fields
            )
        }
    }

    private fun safeGenericName(method: Method): String =
        runCatching { method.genericReturnType.typeName }
            .getOrDefault(method.returnType.name)

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Void.TYPE -> null
        else -> null
    }
}
