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
        timeoutMs: Long = 900L
    ): Result? {
        if (expectedRows <= 0) return null

        directList(source, expectedRows)?.let {
            return Result(it, "direct")
        }

        // Room/Rx 4.2.1 can expose a generated DAO read as a Single/Maybe/
        // Observable-like carrier whose terminal value method erases T to
        // Object. Invoking a verified read carrier's no-arg Object-returning
        // terminal is still read-only; accept it only when the runtime result
        // is exactly a List with the already-known Cursor row count.
        blockingList(source, expectedRows, timeoutMs)?.let { (rows, boundary) ->
            return Result(rows, boundary)
        }

        val methods = GmmpReflectionPolicy.callableMethods(source.javaClass)
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

        for (method in methods) {
            subscribeOnce(source, method, expectedRows, timeoutMs)?.let {
                return Result(
                    rows = it,
                    boundary = source.javaClass.name + "." + method.name +
                        "(" + method.parameterTypes.single().name + ")"
                )
            }
        }
        return null
    }

    private fun blockingList(
        source: Any,
        expectedRows: Int,
        timeoutMs: Long
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
            val rows = (returned as? List<*>)
                ?.filterNotNull()
                ?.map { it }
                ?.takeIf { it.size == expectedRows }
                ?: continue
            return rows to (
                source.javaClass.name + "." + method.name +
                    "():blocking-object"
                )
        }
        return null
    }

    private fun directList(
        source: Any,
        expectedRows: Int
    ): List<Any>? {
        if (source is List<*>) {
            return source.filterNotNull()
                .takeIf { it.size == expectedRows }
                ?.map { it }
        }
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
            if (rows?.size == expectedRows) return rows
        }
        return null
    }

    private fun subscribeOnce(
        source: Any,
        method: Method,
        expectedRows: Int,
        timeoutMs: Long
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

            args.orEmpty().forEach { value ->
                when (value) {
                    is Throwable -> {
                        failure.compareAndSet(null, value)
                        latch.countDown()
                    }
                    is List<*> -> {
                        val rows = value.filterNotNull().map { it }
                        if (rows.size == expectedRows) {
                            result.compareAndSet(null, rows)
                            latch.countDown()
                        }
                    }
                    null -> Unit
                    else -> {
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

                        // GMMP 4.2.1 d85.W1()/X1() can expose the queue as a
                        // stream of native entity rows rather than one
                        // List<row>. Accumulate only homogeneous objects with
                        // a queue-entity-like numeric shape. The caller still
                        // performs the authoritative queue_id/song_id/
                        // queue_position correlation against the read-only
                        // Cursor before ANY writer is eligible.
                        val entityShape =
                            queueEntityShapeCache.computeIfAbsent(
                                value.javaClass
                            ) { looksLikeQueueEntity(value) }
                        if (entityShape) {
                            val expectedClass = streamedRowClass.get()
                            if (expectedClass == null) {
                                streamedRowClass.compareAndSet(
                                    null, value.javaClass
                                )
                            }
                            if (streamedRowClass.get() == value.javaClass) {
                                synchronized(streamedRows) {
                                    if (streamedRows.none { it === value }) {
                                        streamedRows.add(value)
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
        return result.get()
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
