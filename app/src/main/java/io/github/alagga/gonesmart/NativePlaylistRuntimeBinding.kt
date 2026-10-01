package io.github.alagga.gonesmart

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Runtime-correlated read-only binding for paged native Playlist adapters.
 *
 * The binding is accepted only when the actual Playlist RecyclerView,
 * actually bound holder, visible native title and adapter-position lookup all
 * agree on the same row model. Ambiguous shapes fail open to GMMP's native UI.
 */
internal object NativePlaylistRuntimeBinding {
    data class Row(
        val path: String,
        val title: String,
        val model: Any
    )

    data class Description(
        val adapterClass: String,
        val holderClass: String,
        val modelClass: String,
        val getter: String,
        val holderField: String,
        val pathField: String,
        val titleField: String,
        val samplePathKind: String
    )

    private data class Binding(
        val adapterClass: Class<*>,
        val holderClass: Class<*>,
        val modelClass: Class<*>,
        val getter: Method,
        val holderField: Field,
        val pathField: Field,
        val titleField: Field,
        val idGetter: Method
    )

    private val byAdapter =
        Collections.synchronizedMap(WeakHashMap<Class<*>, Binding>())
    private val byHolder =
        Collections.synchronizedMap(WeakHashMap<Class<*>, Binding>())

    fun isReady(adapter: Any): Boolean =
        synchronized(byAdapter) {
            byAdapter.containsKey(adapter.javaClass)
        }

    fun observeBoundRow(
        adapter: Any,
        holder: Any
    ): Description? {
        val position = runCatching {
            holder.javaClass.methods
                .firstOrNull {
                    (it.name == "getBindingAdapterPosition" ||
                        it.name == "getAdapterPosition") &&
                        it.parameterCount == 0 &&
                        it.returnType == Int::class.javaPrimitiveType
                }
                ?.invoke(holder) as? Int
        }.getOrNull() ?: return null
        val itemView = runCatching {
            generateSequence<Class<*>>(holder.javaClass) { it.superclass }
                .mapNotNull { owner ->
                    runCatching {
                        owner.getDeclaredField("itemView").apply {
                            isAccessible = true
                        }
                    }.getOrNull()
                }
                .firstOrNull()
                ?.get(holder) as? View
        }.getOrNull() ?: return null
        return observeBoundRow(
            adapter = adapter,
            holder = holder,
            adapterPosition = position,
            renderedTitle = visibleNativeTitle(itemView)
        )
    }

    fun observeBoundRow(
        adapter: Any,
        holder: Any,
        adapterPosition: Int,
        renderedTitle: String?
    ): Description? {
        if (adapterPosition < 0) return null
        synchronized(byAdapter) {
            byAdapter[adapter.javaClass]
        }?.let { return describe(it, "cached") }

        val title = renderedTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val holderCandidates = holder.javaClass.declaredFields
            .asSequence()
            .filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
            .mapNotNull { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(holder)
                }.getOrNull() ?: return@mapNotNull null
                val modelClass = value.javaClass
                if (isPlatformType(modelClass)) return@mapNotNull null
                val idGetter = modelClass.methods.singleOrNull {
                    it.name == "getId" &&
                        it.parameterCount == 0 &&
                        (it.returnType == Long::class.javaPrimitiveType ||
                            it.returnType == java.lang.Long::class.java)
                } ?: return@mapNotNull null
                if (directStringFields(modelClass).size < 2) {
                    return@mapNotNull null
                }
                Triple(field, value, idGetter)
            }
            .toList()

        if (holderCandidates.size != 1) return null
        val (holderField, boundModel, idGetter) = holderCandidates.single()
        val modelClass = boundModel.javaClass
        val stringFields = directStringFields(modelClass)

        val titleFields = stringFields.filter { field ->
            readString(field, boundModel)?.let {
                sameText(it, title)
            } == true
        }
        if (titleFields.size != 1) return null
        val titleField = titleFields.single()

        val pathCandidates = stringFields.filter { field ->
            field != titleField &&
                readString(field, boundModel)?.let(::pathKind) != null
        }
        if (pathCandidates.size != 1) return null
        val pathField = pathCandidates.single()
        val samplePath = readString(pathField, boundModel) ?: return null
        val sampleKind = pathKind(samplePath) ?: return null

        val getter = GmmpReflectionPolicy.uniqueConcreteMethod(
            adapter.javaClass
        ) { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.returnType != Any::class.java &&
                method.returnType.isAssignableFrom(modelClass)
        } ?: return null

        val getterModel = runCatching {
            getter.isAccessible = true
            getter.invoke(adapter, adapterPosition)
        }.getOrNull() ?: return null
        if (!modelClass.isInstance(getterModel)) return null

        val sameModel =
            getterModel === boundModel ||
                getterModel == boundModel ||
                sameId(idGetter, getterModel, boundModel)
        if (!sameModel) return null

        val binding = Binding(
            adapterClass = adapter.javaClass,
            holderClass = holder.javaClass,
            modelClass = modelClass,
            getter = getter,
            holderField = holderField.apply { isAccessible = true },
            pathField = pathField.apply { isAccessible = true },
            titleField = titleField.apply { isAccessible = true },
            idGetter = idGetter.apply { isAccessible = true }
        )
        synchronized(byAdapter) {
            byAdapter[adapter.javaClass] = binding
        }
        synchronized(byHolder) {
            byHolder[holder.javaClass] = binding
        }
        return describe(binding, sampleKind)
    }

    fun readAll(
        adapter: Any,
        expectedRows: Int
    ): List<Row>? {
        if (expectedRows <= 0) return null
        val binding = synchronized(byAdapter) {
            byAdapter[adapter.javaClass]
        } ?: return null

        val rows = ArrayList<Row>(expectedRows)
        val seen = HashSet<String>(expectedRows)
        for (index in 0 until expectedRows) {
            val model = runCatching {
                binding.getter.invoke(adapter, index)
            }.getOrNull() ?: return null
            if (!binding.modelClass.isInstance(model)) return null

            val path = readString(binding.pathField, model)
                ?.takeIf { pathKind(it) != null }
                ?: return null
            val title = readString(binding.titleField, model)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            if (!seen.add(path)) return null
            rows += Row(path, title, model)
        }
        return rows.takeIf { it.size == expectedRows }
    }

    fun boundModel(holder: Any): Any? {
        val binding = synchronized(byHolder) {
            byHolder[holder.javaClass]
        } ?: return null
        return runCatching {
            binding.holderField.get(holder)
        }.getOrNull()?.takeIf {
            binding.modelClass.isInstance(it)
        }
    }

    fun swapBoundModel(holder: Any, model: Any): Any? {
        val binding = synchronized(byHolder) {
            byHolder[holder.javaClass]
        } ?: return null
        if (!binding.modelClass.isInstance(model)) return null
        return runCatching {
            val previous = binding.holderField.get(holder)
            binding.holderField.set(holder, model)
            previous
        }.getOrNull()
    }

    fun restoreBoundModel(holder: Any, model: Any?) {
        val binding = synchronized(byHolder) {
            byHolder[holder.javaClass]
        } ?: return
        runCatching {
            if (model == null || binding.modelClass.isInstance(model)) {
                binding.holderField.set(holder, model)
            }
        }
    }

    fun itemViewOf(holder: Any): View? =
        runCatching {
            generateSequence<Class<*>>(holder.javaClass) { it.superclass }
                .mapNotNull { owner ->
                    runCatching {
                        owner.getDeclaredField("itemView").apply {
                            isAccessible = true
                        }
                    }.getOrNull()
                }
                .firstOrNull()
                ?.get(holder) as? View
        }.getOrNull()

    fun pathOf(model: Any): String? {
        val binding = synchronized(byAdapter) {
            byAdapter.values.firstOrNull {
                it.modelClass == model.javaClass
            }
        } ?: return null
        return readString(binding.pathField, model)
            ?.takeIf { pathKind(it) != null }
    }

    fun titleOf(model: Any): String? {
        val binding = synchronized(byAdapter) {
            byAdapter.values.firstOrNull {
                it.modelClass == model.javaClass
            }
        } ?: return null
        return readString(binding.titleField, model)
            ?.takeIf { it.isNotBlank() }
    }

    private fun describe(
        binding: Binding,
        samplePathKind: String
    ): Description =
        Description(
            adapterClass = binding.adapterClass.name,
            holderClass = binding.holderClass.name,
            modelClass = binding.modelClass.name,
            getter = binding.getter.declaringClass.name + "." +
                binding.getter.name + "(int):" +
                binding.getter.returnType.name,
            holderField = binding.holderField.declaringClass.name + "." +
                binding.holderField.name,
            pathField = binding.pathField.declaringClass.name + "." +
                binding.pathField.name,
            titleField = binding.titleField.declaringClass.name + "." +
                binding.titleField.name,
            samplePathKind = samplePathKind
        )

    private fun directStringFields(type: Class<*>): List<Field> =
        type.declaredFields
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    !it.isSynthetic &&
                    CharSequence::class.java.isAssignableFrom(it.type)
            }
            .onEach { it.isAccessible = true }

    private fun readString(
        field: Field,
        target: Any
    ): String? =
        runCatching {
            (field.get(target) as? CharSequence)
                ?.toString()
                ?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sameId(
        getter: Method,
        first: Any,
        second: Any
    ): Boolean =
        runCatching {
            getter.invoke(first) == getter.invoke(second)
        }.getOrDefault(false)

    private fun sameText(
        left: String,
        right: String
    ): Boolean =
        left.trim().replace(Regex("\\s+"), " ")
            .equals(
                right.trim().replace(Regex("\\s+"), " "),
                ignoreCase = true
            )

    private fun pathKind(raw: String): String? {
        val value = raw.trim()
        return when {
            value.startsWith("/") -> "absolute"
            value.startsWith("file:") -> "uri"
            value.startsWith("content:") -> "uri"
            value.contains("://") -> "uri"
            else -> null
        }
    }

    private fun visibleNativeTitle(root: View): String? {
        val candidates = ArrayList<Pair<String, Float>>()
        fun collect(view: View, depth: Int) {
            if (depth > 7 || candidates.size >= 40 ||
                view.visibility != View.VISIBLE
            ) return
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                if (text.length in 1..250 &&
                    text.any(Char::isLetterOrDigit) &&
                    !text.startsWith("/") &&
                    !text.contains("://")
                ) {
                    candidates += text to view.textSize
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    collect(view.getChildAt(index), depth + 1)
                }
            }
        }
        collect(root, 0)
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun isPlatformType(type: Class<*>): Boolean {
        val name = type.name
        return name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("android.") ||
            name.startsWith("androidx.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("com.google.") ||
            name.startsWith("com.afollestad.")
    }
}
