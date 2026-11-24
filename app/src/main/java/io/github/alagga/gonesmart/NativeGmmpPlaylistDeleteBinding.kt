package io.github.alagga.gonesmart

import android.content.Context
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/**
 * Resolves GMMP's ORIGINAL playlist-delete entry point from its
 * Context + List<T> contract.
 *
 * DEX-verified GMMP 4.2.1 normal Playlist deletion:
 *   native ao3 adapter binds yn3 (long ID, path and title), while the
 *   original 4.2.1 Playlist deletion callback constructs sh1(File, Long).
 *   then invokes ry0.b(Context, List<sh1>) (async confirmation event).
 *   The native deletion worker request is ry0.f(Context, List<sh1>)
 *   (references DeletePlaylistFileWorker), enqueued by ry0.a(ArrayList).
 *   GMMP 4.2.1's on-device py0 is NOT a playlist-delete entrypoint.
 *
 * GoneSmart's Select action is the user's confirmation. After durable
 * staging and exact adapter-source verification, follow GMMP's ORIGINAL
 * request-builder/enqueue pair on the background worker, bypassing only the
 * secondary destructive-language confirmation. No direct file/DB deletion.
 *
 * Legacy versions retain the old, separately-verified dialog route.
 */
internal class NativeGmmpPlaylistDeleteBinding private constructor(
    val deleteMethod: Method,
    private val elementType: Class<*>?,
    private val legacyConstructor: Constructor<*>?,
    private val nativeWorkerEnqueue: Method? = null,
    private val nativeWorkerWrapper: Constructor<*>? = null
) {
    val usesVerifiedNativeWorker: Boolean
        get() = nativeWorkerEnqueue != null && nativeWorkerWrapper != null

    fun accepts(model: Any): Boolean =
        if (usesVerifiedNativeWorker) {
            // The independent native Playlist adapter runtime binding
            // verifies the actual ao3 row class as yn3, not xn3 (which
            // belongs to GMMP's unrelated playing-info updater).
            Gmmp421PlaylistModelPolicy.isOriginalPlaylistRow(model.javaClass)
        } else elementType == null || elementType.isInstance(model)

    /**
     * Schedule the actual APK-proven GMMP playlist DeleteFileWorker chain.
     * Call ONLY from a background worker after durable original staging,
     * with plans whose source identities still match the native adapter.
     */
    fun enqueueVerifiedNativeWorker(
        context: Context,
        sources: List<File>
    ) {
        val enqueue = requireNotNull(nativeWorkerEnqueue)
        val wrapper = requireNotNull(nativeWorkerWrapper)
        require(sources.isNotEmpty() && sources.distinct().size == sources.size)
        require(sources.all { it.isFile })
        val nativeItems = ArrayList<Any>(sources.size)
        for (source in sources) {
            // APK-proven native Playlists delete constructs sh1(File,Long)
            // from selected yn3 paths; never pass the yn3 to the worker.
            // The original row's optional Long is null in the File-backed
            // conversion; GMMP resolves indexed playlistIds itself.
            nativeItems += wrapper.newInstance(source, null)
        }
        val requests = deleteMethod.invoke(null, context, nativeItems)
            as? ArrayList<*>
            ?: error("Native playlist worker did not return ArrayList")
        require(requests.isNotEmpty()) {
            "GMMP generated no native DeletePlaylistFileWorker request"
        }
        // Same native enqueue boundary used after ry0.f by fy0.run.
        // If enqueue throws after partial scheduling, caller must RETAIN
        // durable backups and observe/restore the original native index.
        enqueue.invoke(null, requests)
    }

    fun nativeModel(model: Any): Any {
        require(accepts(model)) {
            "GMMP playlist delete model mismatch: " +
                model.javaClass.name + " -> " +
                (elementType?.name ?: "<erased>")
        }
        return model
    }

    fun wrapLegacy(file: File): Any {
        if (elementType == File::class.java) return file
        val ctor = legacyConstructor
            ?: error("GMMP legacy playlist wrapper unavailable")
        val p = ctor.parameterTypes
        return when (p.size) {
            1 -> ctor.newInstance(file)
            2 -> ctor.newInstance(
                file,
                if (p[1] == java.lang.Long.TYPE) 0L else null
            )
            else -> error("Unsupported GMMP playlist wrapper shape")
        }
    }

    companion object {
        private data class Candidate(
            val method: Method,
            val element: Class<*>?
        )

        /** Read-only, bounded mapping evidence for an unmapped GMMP APK. */
        fun diagnosticKnownOwner(loader: ClassLoader): String =
            runCatching {
                val owner = loader.loadClass("py0")
                val shapes = owner.declaredMethods
                    .filter { it.parameterCount in 1..3 }
                    .sortedBy { it.name }
                    .take(16)
                    .joinToString(";") { m ->
                        m.name + "(" +
                            m.parameterTypes.joinToString(",") { it.simpleName } +
                            ")" + if (Modifier.isStatic(m.modifiers)) "[static]"
                                else "[instance]"
                    }
                "py0 methods=" + shapes.ifEmpty { "<none>" }
            }.getOrElse {
                "py0 unavailable=" + it.javaClass.simpleName
            }

        fun resolve(
            loader: ClassLoader,
            sampleModel: Any? = null
        ): NativeGmmpPlaylistDeleteBinding {
            // Explicit APK-proven fast path: required owners, exact
            // signatures, native model conversion and worker are all
            // independently present. No blind invocation for discovery.
            if (sampleModel != null &&
                Gmmp421PlaylistModelPolicy.isOriginalPlaylistRow(
                    sampleModel.javaClass
                )
            ) {
                val verified = runCatching {
                    val owner = loader.loadClass("ry0")
                    val item = loader.loadClass("sh1")
                    val base = loader.loadClass("on1")
                    require(base.isAssignableFrom(item))
                    val pathReader = base.getDeclaredMethod("b")
                    require(pathReader.returnType == String::class.java)
                    val wrapper = item.getDeclaredConstructor(
                        File::class.java, java.lang.Long::class.java
                    ).apply { isAccessible = true }
                    val nativeDialog = owner.getDeclaredMethod(
                        "b", Context::class.java, java.util.List::class.java
                    )
                    require(Modifier.isStatic(nativeDialog.modifiers) &&
                        nativeDialog.returnType == java.lang.Void.TYPE)
                    val createRequests = owner.getDeclaredMethod(
                        "f", Context::class.java, java.util.List::class.java
                    )
                    require(Modifier.isStatic(createRequests.modifiers) &&
                        ArrayList::class.java.isAssignableFrom(
                            createRequests.returnType
                        ))
                    val enqueue = owner.getDeclaredMethod(
                        "a", ArrayList::class.java
                    )
                    require(Modifier.isStatic(enqueue.modifiers) &&
                        enqueue.returnType == java.lang.Void.TYPE)
                    require(
                        loader.loadClass(
                            "gonemad.gmmp.work.delete.DeletePlaylistFileWorker"
                        ) != null
                    )
                    createRequests.isAccessible = true
                    enqueue.isAccessible = true
                    NativeGmmpPlaylistDeleteBinding(
                        deleteMethod = createRequests,
                        elementType = item,
                        legacyConstructor = null,
                        nativeWorkerEnqueue = enqueue,
                        nativeWorkerWrapper = wrapper
                    )
                }.onFailure { error ->
                    throw IllegalStateException(
                        "GMMP 4.2.1 native Playlist worker mapping not verified",
                        error
                    )
                }.getOrThrow()
                return verified
            }
            val deleteType = loader.loadClass("py0")
            val methods = deleteType.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 2 &&
                    Context::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    java.util.List::class.java.isAssignableFrom(
                        it.parameterTypes[1]
                    )
            }
            require(methods.isNotEmpty()) {
                "GMMP playlist delete method unavailable"
            }

            val candidates = methods.map {
                Candidate(it, genericListElementClass(it, 1))
            }

            val chosen = if (sampleModel != null) {
                val modelClass = sampleModel.javaClass
                val typed = candidates.filter { candidate ->
                    candidate.element?.let {
                        it.isAssignableFrom(modelClass) ||
                            modelClass.isAssignableFrom(it)
                    } == true
                }
                typed.singleOrNull()
                    ?: typed.singleOrNull { it.method.name == "b" }
                    ?: candidates.singleOrNull()
                    ?: error(
                        "GMMP playlist delete method is not unique for " +
                            modelClass.name + ": " +
                            candidates.joinToString(",") {
                                it.method.name + "<" +
                                    (it.element?.name ?: "erased") + ">"
                            }
                    )
            } else {
                candidates.singleOrNull { it.method.name == "b" }
                    ?: candidates.singleOrNull()
                    ?: run {
                        // 4.2.0 fallback: identify the List<T> whose T has
                        // the native File[,Long] constructor.
                        val legacy = candidates.filter {
                            it.element?.let(::legacyFactory) != null
                        }
                        legacy.singleOrNull()
                            ?: error(
                                "GMMP playlist delete method is not structurally unique: " +
                                    candidates.joinToString(",") {
                                        it.method.name + "<" +
                                            (it.element?.name ?: "erased") + ">"
                                    }
                            )
                    }
            }

            chosen.method.isAccessible = true
            val ctor = chosen.element?.let(::legacyFactory)
            ctor?.isAccessible = true
            return NativeGmmpPlaylistDeleteBinding(
                chosen.method,
                chosen.element,
                ctor
            )
        }

        private fun legacyFactory(
            type: Class<*>
        ): Constructor<*>? {
            if (type == File::class.java) return null
            val two = type.declaredConstructors.filter {
                val p = it.parameterTypes
                p.size == 2 &&
                    File::class.java.isAssignableFrom(p[0]) &&
                    (p[1] == java.lang.Long::class.java ||
                        p[1] == java.lang.Long.TYPE)
            }
            val one = type.declaredConstructors.filter {
                val p = it.parameterTypes
                p.size == 1 &&
                    File::class.java.isAssignableFrom(p[0])
            }
            return when {
                two.size == 1 -> two.single()
                one.size == 1 -> one.single()
                else -> null
            }
        }

        private fun genericListElementClass(
            method: Method,
            parameterIndex: Int
        ): Class<*>? {
            val type = method.genericParameterTypes
                .getOrNull(parameterIndex) as? ParameterizedType
                ?: return null
            val argument = type.actualTypeArguments.singleOrNull()
                ?: return null
            return when (argument) {
                is Class<*> -> argument
                is ParameterizedType -> argument.rawType as? Class<*>
                else -> null
            }
        }
    }
}
