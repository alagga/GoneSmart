package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Read-only bridge for host reactive DAO return types whose concrete Rx class
 * names/method names are R8-renamed.
 *
 * It never guesses a writer. A source is eligible only when a no-arg DAO
 * method has already returned it and one callback-style terminal boundary
 * emits a List of the expected live row count. The caller still performs the
 * authoritative queue_id/song_id/queue_position correlation before any
 * mutation method is allowed.
 */
internal object NativeReactiveListReader {
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "GoneSmartNativeReactiveRead").apply { isDaemon = true }
    }

    data class Result(
        val rows: List<Any>,
        val boundary: String
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
            if (bestPartial == null ||
                rows.size > bestPartial!!.rows.size
            ) {
                bestPartial = Result(rows, boundary)
            }
        }

        directList(
            source,
            expectedRows,
            expectedModelClass,
            allowPartial
        )?.let { rows ->
            if (rows.size == expectedRows) {
                return Result(rows, "direct")
            }
            keepPartial(rows, "direct")
        }

        // Room/Rx 4.2.1 can expose a generated DAO read as a Single/Maybe/
        // Observable-like carrier whose terminal value method erases T to
        // Object. Invoking a verified read carrier's no-arg Object-returning
        // terminal is still read-only; accept it only when the runtime result
        // is exactly a List with the already-known Cursor row count.
        blockingList(
            source,
            expectedRows,
            timeoutMs,
            expectedModelClass,
            allowPartial
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
                // Operators such as map/doOnNext generally return the same
                // reactive source family. A subscribe/observe terminal either
                // returns void or a different disposable/subscription type.
                .filter {
                    it.returnType == java.lang.Void.TYPE ||
                        !it.returnType.isAssignableFrom(source.javaClass)
                }
                .distinctBy {
                    it.name + "|" + it.parameterTypes.single().name + "|" +
                        it.returnType.name
                }
                .take(12)
                .toList()
        // Rx/Room's observer-style terminal is void on the tested 4.2.1
        // carriers. Prefer those methods so we do not spend multiple timeout
        // windows speculatively invoking transformation operators.
        val voidCandidates = callbackCandidates.filter {
            it.returnType == java.lang.Void.TYPE
        }
        val methods = (voidCandidates.ifEmpty { callbackCandidates })
            .sortedByDescending { method ->
                observerScore(method.parameterTypes.single())
            }

        for (method in methods) {
            subscribeOnce(
                source,
                method,
                expectedRows,
                timeoutMs,
                expectedModelClass,
                allowPartial
            )?.let { rows ->
                val boundary =
                    source.javaClass.name + "." + method.name +
                        "(" + method.parameterTypes.single().name + ")"
                if (rows.size == expectedRows) {
                    return Result(
                        rows = rows,
                        boundary = boundary
                    )
                }
                keepPartial(rows, boundary)
            }
        }
        return bestPartial
    }

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
            val rows = extractRows(
                returned,
                expectedRows,
                expectedModelClass,
                allowPartial
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
        extractRows(
            source,
            expectedRows,
            expectedModelClass,
            allowPartial
        )?.let { return it }
        val candidates = GmmpReflectionPolicy.callableMethods(source.javaClass)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
        for (method in candidates.take(6)) {
            val rows = runCatching {
                method.isAccessible = true
                (method.invoke(source) as? List<*>)
                    ?.filterNotNull()
                    ?.map { it }
            }.getOrNull()
            val accepted = extractRows(
                rows,
                expectedRows,
                expectedModelClass,
                allowPartial
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
        allowPartial: Boolean
    ): List<Any>? {
        val callbackType = method.parameterTypes.single()
        val callbackMethods = callbackType.methods
        val canReceiveValue = callbackMethods.any {
            it.parameterCount == 1 &&
                !Throwable::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (!canReceiveValue) return null

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

        val callback = Proxy.newProxyInstance(
            callbackType.classLoader ?: source.javaClass.classLoader,
            arrayOf(callbackType)
        ) { proxy, invoked, args ->
            when (invoked.name) {
                "toString" -> return@newProxyInstance "GoneSmart reactive queue reader"
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
            }

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
                        result.compareAndSet(
                            null,
                            streamedRows.toList()
                        )
                        latch.countDown()
                    }
                }
            }

            args.orEmpty().forEach { value ->
                when (value) {
                    is Throwable -> {
                        failure.compareAndSet(null, value)
                        latch.countDown()
                    }
                    null -> Unit
                    else -> {
                        extractRows(
                            value,
                            expectedRows,
                            expectedModelClass,
                            allowPartial = true
                        )?.let { rows ->
                            acceptRows(rows)
                            return@forEach
                        }

                        // Reactive Streams Subscriber requires request(n)
                        // before onNext. A subscription/control object must
                        // never be mistaken for a queue entity.
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

                        // If DAO ownership already proved the concrete entity
                        // class (r21: queue-specific ww3[] contracts), that
                        // type is stronger evidence than the old numeric-field
                        // heuristic. Keep the heuristic only as a legacy
                        // fallback when no type witness is available.
                        val entityShape =
                            expectedModelClass?.isInstance(value) == true ||
                                queueEntityShapeCache.computeIfAbsent(
                                    value.javaClass
                                ) { looksLikeQueueEntity(value) }
                        if (entityShape) {
                            acceptRows(listOf(value))
                        } else {
                            cancellation.compareAndSet(null, value)
                        }
                    }
                }
            }
            primitiveDefault(invoked.returnType)
        }

        val future = executor.submit<Any?> {
            method.isAccessible = true
            method.invoke(source, callback)
        }
        val returned = runCatching {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrElse {
            future.cancel(true)
            return null
        }
        if (returned != null) cancellation.compareAndSet(null, returned)

        if (result.get() == null && failure.get() == null) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        cancelIfUnambiguous(cancellation.get())
        result.get()?.let { return it }
        if (allowPartial) {
            synchronized(streamedRows) {
                if (streamedRows.isNotEmpty()) {
                    return streamedRows.toList()
                }
            }
        }
        return null
    }

    private fun extractRows(
        value: Any?,
        expectedRows: Int,
        expectedModelClass: Class<*>?,
        allowPartial: Boolean
    ): List<Any>? {
        if (value == null) return null
        val rows: List<Any> = when {
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
            else -> return null
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
        val name = value.javaClass.name
        if (name.startsWith("java.") ||
            name.startsWith("android.") ||
            name.startsWith("kotlin.")
        ) return false

        val numericFields =
            generateSequence<Class<*>>(value.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter {
                    !Modifier.isStatic(it.modifiers) &&
                        !it.isSynthetic &&
                        (
                            it.type == Integer.TYPE ||
                                it.type == Integer::class.java ||
                                it.type == java.lang.Long.TYPE ||
                                it.type == java.lang.Long::class.java
                        )
                }
                .take(6)
                .count()
        // queue_id + song_id + queue_position are the minimum identity shape.
        return numericFields >= 3
    }

    private fun cancelIfUnambiguous(handle: Any?) {
        if (handle == null) return
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
