#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


resolver = r'''package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Small semantic reflection helper for Playlist Link.
 *
 * Obfuscated GMMP names are accepted only as fast-path evidence. Every member
 * returned here must still satisfy the supplied structural predicate. Fallback
 * without a preferred name is allowed only when the structural candidate is
 * unique; ambiguity fails closed.
 */
internal object PlaylistBridgeReflectionResolver {
    fun loadClass(
        loader: ClassLoader,
        candidateNames: List<String>,
        description: String,
        predicate: (Class<*>) -> Boolean = { true }
    ): Class<*> {
        val rejected = arrayListOf<String>()
        candidateNames.distinct().forEach { name ->
            val type = runCatching { loader.loadClass(name) }.getOrNull()
                ?: return@forEach
            if (runCatching { predicate(type) }.getOrDefault(false)) {
                return type
            }
            rejected += name
        }
        throw IllegalStateException(
            "Could not resolve $description from ${candidateNames.joinToString()}" +
                if (rejected.isEmpty()) "" else " (shape rejected: ${rejected.joinToString()})"
        )
    }

    fun optionalClass(
        loader: ClassLoader,
        candidateNames: List<String>,
        predicate: (Class<*>) -> Boolean = { true }
    ): Class<*>? = candidateNames.distinct().firstNotNullOfOrNull { name ->
        runCatching { loader.loadClass(name) }.getOrNull()?.takeIf {
            runCatching { predicate(it) }.getOrDefault(false)
        }
    }

    fun methods(type: Class<*>): List<Method> {
        val out = linkedMapOf<String, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.forEach { method ->
                val key = method.name + "(" +
                    method.parameterTypes.joinToString(",") { it.name } + ")" +
                    ":" + method.returnType.name
                out.putIfAbsent(key, method)
            }
            current = current.superclass
        }
        type.methods.forEach { method ->
            val key = method.name + "(" +
                method.parameterTypes.joinToString(",") { it.name } + ")" +
                ":" + method.returnType.name
            out.putIfAbsent(key, method)
        }
        return out.values.toList()
    }

    fun fields(type: Class<*>): List<Field> {
        val out = linkedMapOf<String, Field>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.forEach { field ->
                out.putIfAbsent(current.name + "#" + field.name, field)
            }
            current = current.superclass
        }
        return out.values.toList()
    }

    fun method(
        type: Class<*>,
        preferredNames: List<String>,
        description: String,
        predicate: (Method) -> Boolean
    ): Method {
        val candidates = methods(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { it.name }
        }
        return candidates.single().apply { isAccessible = true }
    }

    fun optionalMethod(
        type: Class<*>,
        preferredNames: List<String>,
        predicate: (Method) -> Boolean
    ): Method? {
        val candidates = methods(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        return candidates.singleOrNull()?.apply { isAccessible = true }
    }

    fun field(
        type: Class<*>,
        preferredNames: List<String>,
        description: String,
        predicate: (Field) -> Boolean
    ): Field {
        val candidates = fields(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { it.name + ":" + it.type.name }
        }
        return candidates.single().apply { isAccessible = true }
    }

    fun optionalField(
        type: Class<*>,
        preferredNames: List<String>,
        predicate: (Field) -> Boolean
    ): Field? {
        val candidates = fields(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        return candidates.singleOrNull()?.apply { isAccessible = true }
    }

    fun constructor(
        type: Class<*>,
        description: String,
        predicate: (Constructor<*>) -> Boolean
    ): Constructor<*> {
        val candidates = type.declaredConstructors.filter(predicate)
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { ctor ->
                    ctor.parameterTypes.joinToString(",", prefix = "(", postfix = ")") { it.name }
                }
        }
        return candidates.single().apply { isAccessible = true }
    }
}
'''
write("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolver.kt", resolver)

resolver_test = r'''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistBridgeReflectionResolverTest {
    private class MethodFixture {
        @Suppress("unused") fun preferred(value: String): String = value
        @Suppress("unused") fun fallback(value: Int): Int = value
    }

    private class AmbiguousFixture {
        @Suppress("unused") fun first(value: Int): Int = value
        @Suppress("unused") fun second(value: Int): Int = value
    }

    private class FieldFixture {
        @Suppress("unused") private var wanted: String = "x"
        @Suppress("unused") private var other: Int = 1
    }

    @Test
    fun preferredNameStillRequiresMatchingShape() {
        val method = PlaylistBridgeReflectionResolver.method(
            MethodFixture::class.java,
            listOf("preferred"),
            "test method"
        ) { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                candidate.returnType == String::class.java
        }
        assertEquals("preferred", method.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousStructuralFallbackFailsClosed() {
        PlaylistBridgeReflectionResolver.method(
            AmbiguousFixture::class.java,
            emptyList(),
            "ambiguous method"
        ) { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(Integer.TYPE)) &&
                candidate.returnType == Integer.TYPE
        }
    }

    @Test
    fun optionalAmbiguityReturnsNullInsteadOfGuessing() {
        val method = PlaylistBridgeReflectionResolver.optionalMethod(
            AmbiguousFixture::class.java,
            emptyList()
        ) { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(Integer.TYPE)) &&
                candidate.returnType == Integer.TYPE
        }
        assertNull(method)
    }

    @Test
    fun preferredFieldIsShapeChecked() {
        val field = PlaylistBridgeReflectionResolver.field(
            FieldFixture::class.java,
            listOf("wanted"),
            "test field"
        ) { it.type == String::class.java }
        assertEquals("wanted", field.name)
        assertSame(String::class.java, field.type)
    }
}
'''
write("app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolverTest.kt", resolver_test)

controller_path = ROOT / "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt"
controller = controller_path.read_text(encoding="utf-8")

bindings = r'''    private data class Bindings(
        val loader: ClassLoader,
        val presenterClass: Class<*>,
        val presenterConstructor: Constructor<*>,
        val baseRuleClass: Class<*>,
        val smartRuleClass: Class<*>,
        val smartRuleConstructor: Constructor<*>,
        val ruleSentinel: Field,
        val ruleValue: Field,
        val presenterAddRule: Method,
        val presenterState: Method,
        val presenterRefresh: Method?,
        val presenterView: Field?,
        val stateRules: Method,
        val statePaths: Field?,
        val stateSelectedIndex: Field?,
        val stateDirty: Field?,
        val ruleIdGetter: Method?,
        val ruleIdSetter: Method?,
        val playlistFileConstructor: Constructor<*>,
        val fileModelConstructor: Constructor<*>,
        val playlistRead: Method,
        val playlistEntries: Field,
        val fileModelFile: Field,
        val nativeIn: Method,
        val nativeEquals: Method,
        val uriField: Any,
        val idField: Any,
        val whereGroupConstructor: Constructor<*>,
        val databaseSingleton: Field,
        val playlistDaoGetter: Method,
        val playlistDaoAll: Method,
        val dialogEventConstructor: Constructor<*>,
        val dialogCallbackClass: Class<*>,
        val eventBusGet: Method,
        val eventBusPost: Method,
        val unitValue: Any?,
        val presenterLinkSmartPlaylist: Method,
        val popupMenuConstructor: Constructor<*>,
        val popupMenuGetMenu: Method,
        val popupMenuSetListener: Method,
        val popupMenuShow: Method,
        val popupMenuListenerClass: Class<*>,
        val smartPlaylistConstructor: Constructor<*>,
        val smartPlaylistConstructorArgs: Array<Any?>,
        val smartPlaylistSave: Method,
        val smartPlaylistName: Field,
        val smartPlaylistRules: Field,
        val smartPlaylistMatchAll: Field,
        val groupRuleClass: Class<*>,
        val groupRules: Field,
        val groupMatchAll: Field,
        val chooserConsumerAccept: Method?,
        val ruleLabelFormatter: Method?,
        val evaluationMethod: Method
    )
'''
controller, n = re.subn(
    r"    private data class Bindings\(.*?\n    \)\n(?=\n    private val main)",
    bindings.rstrip(),
    controller,
    count=1,
    flags=re.S,
)
if n != 1:
    raise RuntimeError(f"Bindings replacement failed: {n}")

hook_insert = r'''    internal data class HookTargets(
        val presenterConstructor: Constructor<*>,
        val presenterLinkSmartPlaylist: Method,
        val chooserConsumerAccept: Method?,
        val ruleLabelFormatter: Method?,
        val evaluationMethod: Method
    )

    internal fun hookTargets(): HookTargets? = bindings?.let { native ->
        HookTargets(
            presenterConstructor = native.presenterConstructor,
            presenterLinkSmartPlaylist = native.presenterLinkSmartPlaylist,
            chooserConsumerAccept = native.chooserConsumerAccept,
            ruleLabelFormatter = native.ruleLabelFormatter,
            evaluationMethod = native.evaluationMethod
        )
    }

'''
needle = '''    internal data class PortableSaveToken(
        val originals: List<Pair<Any, String?>>
    )

'''
controller = replace_once(controller, needle, needle + hook_insert, "HookTargets insertion")

# Rule-value/sentinel access must follow resolved fields, never 4.2.0 names.
controller = replace_once(
    controller,
    '''        val value = runCatching {
            findField(rule.javaClass, "q")
                .apply { isAccessible = true }
                .get(rule) as? String
        }.getOrNull()
''',
    '''        val value = runCatching {
            native.ruleValue.get(rule) as? String
        }.getOrNull()
''',
    "rule label value",
)
controller = replace_once(
    controller,
    '''        return runCatching {
            val field = findField(rule.javaClass, "o").apply { isAccessible = true }
                .getInt(rule)
            val value = findField(rule.javaClass, "q").apply { isAccessible = true }
                .get(rule) as? String
            field == -1 && PlaylistBridgeReference.decode(value) != null
        }.getOrDefault(false)
''',
    '''        return runCatching {
            val sentinel = native.ruleSentinel.getInt(rule)
            val value = native.ruleValue.get(rule) as? String
            sentinel == -1 && PlaylistBridgeReference.decode(value) != null
        }.getOrDefault(false)
''',
    "isBridgeRule fields",
)
controller = replace_once(
    controller,
    '''        val value = findField(rule.javaClass, "q").apply { isAccessible = true }
            .get(rule) as? String
''',
    '''        val value = native.ruleValue.get(rule) as? String
''',
    "compile rule value",
)
controller = replace_once(
    controller,
    '''    fun restorePortableSave(token: PortableSaveToken?) {
        token?.originals?.asReversed()?.forEach { (rule, value) ->
            runCatching {
                findField(rule.javaClass, "q")
                    .apply { isAccessible = true }
                    .set(rule, value)
            }
        }
    }
''',
    '''    fun restorePortableSave(token: PortableSaveToken?) {
        val native = bindings ?: return
        token?.originals?.asReversed()?.forEach { (rule, value) ->
            runCatching { native.ruleValue.set(rule, value) }
        }
    }
''',
    "portable restore",
)
controller = replace_once(
    controller,
    '''            val field = findField(rule.javaClass, "q").apply {
                isAccessible = true
            }
            val original = field.get(rule) as? String
''',
    '''            val field = native.ruleValue
            val original = field.get(rule) as? String
''',
    "portable bridge field",
)
controller = replace_once(
    controller,
    '''        val smart = native.smartPlaylistConstructor.newInstance()
''',
    '''        val smart = native.smartPlaylistConstructor.newInstance(
            *native.smartPlaylistConstructorArgs
        )
''',
    "smart compatibility constructor",
)

# Editor state: path-map/dirty/view/id helpers are optional; core add stays native.
controller = replace_once(
    controller,
    '''        @Suppress("UNCHECKED_CAST")
        val paths = findField(state.javaClass, "x")
            .apply { isAccessible = true }
            .get(state) as? MutableMap<Int, String>
        paths?.put(rules.size, choice.path)
''',
    '''        @Suppress("UNCHECKED_CAST")
        val paths = native.statePaths?.get(state) as? MutableMap<Int, String>
        paths?.put(rules.size, choice.path)
''',
    "add state paths",
)
controller = replace_once(
    controller,
    '''        val index = findField(state.javaClass, "y")
            .apply { isAccessible = true }
            .getInt(state)
''',
    '''        val selectedIndex = native.stateSelectedIndex
            ?: throw IllegalStateException("Smart editor selected-index mapping unavailable")
        val index = selectedIndex.getInt(state)
''',
    "replace selected index",
)
controller = replace_once(
    controller,
    '''        runCatching {
            val id = previous.javaClass.getMethod("d").invoke(previous) as Number
            replacement.javaClass
                .getMethod("w", java.lang.Long.TYPE)
                .invoke(replacement, id.toLong())
        }
''',
    '''        runCatching {
            val getter = native.ruleIdGetter ?: return@runCatching
            val setter = native.ruleIdSetter ?: return@runCatching
            val id = getter.invoke(previous) as? Number ?: return@runCatching
            setter.invoke(replacement, id.toLong())
        }
''',
    "rule id copy",
)
controller = replace_once(
    controller,
    '''        findField(state.javaClass, "v")
            .apply { isAccessible = true }
            .setBoolean(state, true)
        @Suppress("UNCHECKED_CAST")
        val paths = findField(state.javaClass, "x")
            .apply { isAccessible = true }
            .get(state) as? MutableMap<Int, String>
        paths?.put(index, choice.path)
        val view = findField(presenter.javaClass, "r")
            .apply { isAccessible = true }
            .get(presenter)
        if (view != null) {
            native.presenterRefresh.invoke(presenter, view)
        }
''',
    '''        native.stateDirty?.setBoolean(state, true)
        @Suppress("UNCHECKED_CAST")
        val paths = native.statePaths?.get(state) as? MutableMap<Int, String>
        paths?.put(index, choice.path)
        val view = native.presenterView?.get(presenter)
        if (view != null && native.presenterRefresh != null) {
            native.presenterRefresh.invoke(presenter, view)
        }
''',
    "replace editor refresh",
)
controller = replace_once(
    controller,
    '''        val index = findField(state.javaClass, "y")
            .apply { isAccessible = true }
            .getInt(state)
        val rules = native.stateRules.invoke(state) as? List<*>
''',
    '''        val indexField = native.stateSelectedIndex ?: return@runCatching null
        val index = indexField.getInt(state)
        val rules = native.stateRules.invoke(state) as? List<*>
''',
    "selectedRule index",
)

# Native Playlist DAO rows: discover path/title from actual runtime String fields.
old_choices = '''        val raw = rows.mapNotNull { row ->
            if (row == null) return@mapNotNull null
            val uri = native.playlistEntityUri.get(row) as? String
                ?: return@mapNotNull null
            val file = playlistFile(uri) ?: return@mapNotNull null
            if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) {
                return@mapNotNull null
            }
            val display = (native.playlistEntityName.get(row) as? String)
                ?.takeUnless(String::isBlank)
                ?: file.nameWithoutExtension
            PlaylistChoice(
                path = canonicalPath(file),
                displayName = display,
                label = display
            )
        }.distinctBy { it.path }
'''
new_choices = '''        val raw = rows.mapNotNull { row ->
            if (row == null) return@mapNotNull null
            val strings = PlaylistBridgeReflectionResolver.fields(row.javaClass)
                .asSequence()
                .filter { it.type == String::class.java }
                .mapNotNull { field ->
                    field.isAccessible = true
                    runCatching { field.get(row) as? String }.getOrNull()
                }
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            val source = strings.firstNotNullOfOrNull { raw ->
                playlistFile(raw)?.takeIf {
                    it.extension.lowercase() in SUPPORTED_EXTENSIONS
                }?.let { raw to it }
            } ?: return@mapNotNull null
            val uri = source.first
            val file = source.second
            val display = strings.firstOrNull { value ->
                value != uri &&
                    !value.startsWith("file://", ignoreCase = true) &&
                    !value.startsWith("content://", ignoreCase = true) &&
                    !value.contains(File.separatorChar)
            } ?: file.nameWithoutExtension
            PlaylistChoice(
                path = canonicalPath(file),
                displayName = display,
                label = display
            )
        }.distinctBy { it.path }
'''
controller = replace_once(controller, old_choices, new_choices, "dynamic playlist rows")

# Replace diagnostics with both 4.2.1 and historical fast-path candidates.
controller, n = re.subn(
    r"    private fun diagnoseCompatibilityBindings\(\n        loader: ClassLoader\n    \) \{.*?\n    \}\n\n    fun capturePresenter",
    r'''    private fun diagnoseCompatibilityBindings(
        loader: ClassLoader
    ) {
        listOf("ct4", "ft4", "as4", "ds4", "ls2", "os2").forEach { name ->
            runCatching { loader.loadClass(name) }.getOrNull()?.let { type ->
                Log.w(
                    TAG,
                    "BRIDGE MAPPING | candidate=$name" +
                        " | constructors=" + GmmpReflectionDiagnostics.constructors(type) +
                        " | methods=" + GmmpReflectionDiagnostics.methods(
                            type = type,
                            limit = 24
                        ) { true }
                )
            }
        }
    }

    fun capturePresenter''',
    controller,
    count=1,
    flags=re.S,
)
if n != 1:
    raise RuntimeError(f"diagnostics replacement failed: {n}")

create_bindings = r'''    private fun createBindings(loader: ClassLoader): Bindings {
        val r = PlaylistBridgeReflectionResolver

        // GMMP 4.2.1 shifted this family of R8 names by three letters on the
        // tested build (for example ws4 -> ts4). Names are fast paths only;
        // every class/member is shape-validated below.
        val smartRuleClass = r.loadClass(
            loader,
            listOf("ct4", "ft4"),
            "Playlist Link leaf Smart rule"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterTypes.contentEquals(
                    arrayOf(
                        Integer.TYPE,
                        Integer.TYPE,
                        String::class.java,
                        Integer.TYPE
                    )
                )
            }
        }
        val smartRuleConstructor = r.constructor(
            smartRuleClass,
            "Playlist Link leaf-rule constructor"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(
                    Integer.TYPE,
                    Integer.TYPE,
                    String::class.java,
                    Integer.TYPE
                )
            )
        }
        val baseRuleClass = smartRuleClass.superclass
            ?: throw IllegalStateException("Playlist Link leaf rule has no base class")
        val probeValue = "gonesmart-playlist-v2:binding-probe"
        val probeRule = smartRuleConstructor.newInstance(-1, 0, probeValue, 0)
        val ruleValue = r.field(
            smartRuleClass,
            listOf("q"),
            "Playlist Link rule value"
        ) { field ->
            field.type == String::class.java &&
                runCatching { field.isAccessible = true; field.get(probeRule) == probeValue }
                    .getOrDefault(false)
        }
        val ruleSentinel = r.field(
            smartRuleClass,
            listOf("o"),
            "Playlist Link rule sentinel"
        ) { field ->
            field.type == Integer.TYPE &&
                runCatching { field.isAccessible = true; field.getInt(probeRule) == -1 }
                    .getOrDefault(false)
        }

        val evaluationMethod = r.method(
            smartRuleClass,
            listOf("z"),
            "Playlist Link rule evaluator"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 2 &&
                java.util.LinkedHashSet::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                (method.parameterTypes[1] == java.lang.Integer::class.java ||
                    method.parameterTypes[1] == Integer.TYPE) &&
                method.returnType != java.lang.Void.TYPE
        }
        val predicateClass = evaluationMethod.returnType

        val presenterClass = r.loadClass(
            loader,
            listOf("as4", "ds4"),
            "Smart editor presenter"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterTypes.contentEquals(
                    arrayOf(Context::class.java, android.os.Bundle::class.java)
                )
            }
        }
        val presenterConstructor = r.constructor(
            presenterClass,
            "Smart editor presenter constructor"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(Context::class.java, android.os.Bundle::class.java)
            )
        }
        val presenterAddRule = r.method(
            presenterClass,
            listOf("P1"),
            "Smart editor add-rule action"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(smartRuleClass)
        }
        val presenterState = r.method(
            presenterClass,
            listOf("V1"),
            "Smart editor state accessor"
        ) { method ->
            method.parameterCount == 0 &&
                method.returnType != java.lang.Void.TYPE &&
                r.methods(method.returnType).any { candidate ->
                    candidate.parameterCount == 0 &&
                        java.util.List::class.java.isAssignableFrom(candidate.returnType)
                }
        }
        val stateClass = presenterState.returnType
        val stateRules = r.method(
            stateClass,
            listOf("b"),
            "Smart editor rule-list accessor"
        ) { method ->
            method.parameterCount == 0 &&
                java.util.List::class.java.isAssignableFrom(method.returnType)
        }
        val statePaths = r.optionalField(stateClass, listOf("x")) { field ->
            java.util.Map::class.java.isAssignableFrom(field.type)
        }
        val stateSelectedIndex = r.optionalField(stateClass, listOf("y")) {
            it.type == Integer.TYPE
        }
        val stateDirty = r.optionalField(stateClass, listOf("v")) {
            it.type == java.lang.Boolean.TYPE
        }
        val presenterView = r.optionalField(presenterClass, listOf("r")) { field ->
            !field.type.isPrimitive &&
                !Context::class.java.isAssignableFrom(field.type)
        }
        val presenterRefresh = r.optionalMethod(
            presenterClass,
            listOf("Z1")
        ) { method ->
            method.parameterCount == 1 &&
                method.returnType == java.lang.Void.TYPE &&
                (presenterView == null ||
                    method.parameterTypes[0].isAssignableFrom(presenterView.type) ||
                    presenterView.type.isAssignableFrom(method.parameterTypes[0]))
        }
        val presenterLinkSmartPlaylist = r.method(
            presenterClass,
            listOf("g2"),
            "native linked-Smart-Playlist action"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))
        }
        val ruleIdGetter = r.optionalMethod(smartRuleClass, listOf("d")) { method ->
            method.parameterCount == 0 &&
                (Number::class.java.isAssignableFrom(method.returnType) ||
                    method.returnType == java.lang.Long.TYPE)
        }
        val ruleIdSetter = r.optionalMethod(smartRuleClass, listOf("w")) { method ->
            method.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))
        }

        val parserClass = r.loadClass(
            loader,
            listOf("ep3", "hp3"),
            "native playlist parser"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterCount == 1 &&
                    ctor.parameterTypes[0].declaredConstructors.any { modelCtor ->
                        modelCtor.parameterTypes.contentEquals(
                            arrayOf(File::class.java, java.lang.Long::class.java)
                        )
                    }
            }
        }
        val playlistFileConstructor = r.constructor(
            parserClass,
            "native playlist parser constructor"
        ) { ctor ->
            ctor.parameterCount == 1 &&
                ctor.parameterTypes[0].declaredConstructors.any { modelCtor ->
                    modelCtor.parameterTypes.contentEquals(
                        arrayOf(File::class.java, java.lang.Long::class.java)
                    )
                }
        }
        val fileModelClass = playlistFileConstructor.parameterTypes[0]
        val fileModelConstructor = r.constructor(
            fileModelClass,
            "native playlist entry/file model constructor"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(File::class.java, java.lang.Long::class.java)
            )
        }
        val playlistRead = r.method(
            parserClass,
            listOf("c"),
            "native playlist read action"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(
                    arrayOf(parserClass, String::class.java, Integer.TYPE)
                )
        }
        val playlistEntries = r.field(
            parserClass,
            listOf("r"),
            "native parsed playlist entries"
        ) { field -> java.util.Collection::class.java.isAssignableFrom(field.type) }
        val fileModelFile = r.field(
            fileModelClass,
            listOf("a"),
            "native playlist entry file"
        ) { field -> File::class.java.isAssignableFrom(field.type) }

        val searchHelperClass = r.loadClass(
            loader,
            listOf("lt0", "ot0"),
            "native Smart query helper"
        ) { type ->
            r.methods(type).any { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 2 &&
                    java.util.List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                    (method.returnType == predicateClass ||
                        predicateClass.isAssignableFrom(method.returnType))
            }
        }
        val nativeIn = r.method(
            searchHelperClass,
            listOf("t"),
            "native IN predicate builder"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 2 &&
                java.util.List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                (method.returnType == predicateClass ||
                    predicateClass.isAssignableFrom(method.returnType))
        }
        val queryFieldClass = nativeIn.parameterTypes[0]
        val nativeEquals = r.method(
            searchHelperClass,
            listOf("p"),
            "native equality predicate builder"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == queryFieldClass &&
                !java.util.List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                (method.returnType == predicateClass ||
                    predicateClass.isAssignableFrom(method.returnType))
        }
        val trackFieldClass = r.loadClass(
            loader,
            listOf("w75", "z75"),
            "native track query fields"
        ) { type ->
            runCatching {
                val uri = type.getDeclaredField("URI")
                val id = type.getDeclaredField("ID")
                java.lang.reflect.Modifier.isStatic(uri.modifiers) &&
                    java.lang.reflect.Modifier.isStatic(id.modifiers) &&
                    queryFieldClass.isAssignableFrom(uri.type) &&
                    queryFieldClass.isAssignableFrom(id.type)
            }.getOrDefault(false)
        }
        val uriField = trackFieldClass.getDeclaredField("URI").apply {
            isAccessible = true
        }.get(null)
        val idField = trackFieldClass.getDeclaredField("ID").apply {
            isAccessible = true
        }.get(null)

        val whereGroupClass = sequenceOf(
            predicateClass,
            r.optionalClass(loader, listOf("ww3", "zw3")) { candidate ->
                predicateClass.isAssignableFrom(candidate) ||
                    candidate.isAssignableFrom(predicateClass)
            }
        ).filterNotNull().firstOrNull { candidate ->
            candidate.declaredConstructors.any { ctor ->
                ctor.parameterTypes.contentEquals(
                    arrayOf(java.util.List::class.java, String::class.java)
                )
            }
        } ?: throw IllegalStateException("Native OR predicate group is unavailable")
        val whereGroupConstructor = r.constructor(
            whereGroupClass,
            "native OR predicate group"
        ) { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(java.util.List::class.java, String::class.java)
            )
        }

        val dbClass = loader.loadClass("gonemad.gmmp.data.database.GMDatabase")
        val playlistDaoClass = r.loadClass(
            loader,
            listOf("ho3", "ko3"),
            "native Playlist DAO"
        ) { type ->
            r.methods(type).any { method ->
                method.parameterCount == 0 &&
                    java.util.List::class.java.isAssignableFrom(method.returnType)
            }
        }
        val databaseSingleton = r.field(
            dbClass,
            listOf("l"),
            "GMDatabase singleton"
        ) { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                dbClass.isAssignableFrom(field.type)
        }
        val playlistDaoGetter = r.method(
            dbClass,
            listOf("E"),
            "GMDatabase Playlist DAO accessor"
        ) { method ->
            method.parameterCount == 0 &&
                playlistDaoClass.isAssignableFrom(method.returnType)
        }
        val playlistDaoAll = r.method(
            playlistDaoClass,
            listOf("G1"),
            "native Playlist DAO list reader"
        ) { method ->
            method.parameterCount == 0 &&
                java.util.List::class.java.isAssignableFrom(method.returnType)
        }

        val dialogEventClass = r.loadClass(
            loader,
            listOf("wn4", "zn4"),
            "native list-dialog event"
        ) { type ->
            type.declaredConstructors.any { ctor ->
                ctor.parameterCount == 3 &&
                    ctor.parameterTypes[0] == String::class.java &&
                    java.util.List::class.java.isAssignableFrom(ctor.parameterTypes[1]) &&
                    ctor.parameterTypes[2].isInterface
            }
        }
        val dialogEventConstructor = r.constructor(
            dialogEventClass,
            "native list-dialog event constructor"
        ) { ctor ->
            ctor.parameterCount == 3 &&
                ctor.parameterTypes[0] == String::class.java &&
                java.util.List::class.java.isAssignableFrom(ctor.parameterTypes[1]) &&
                ctor.parameterTypes[2].isInterface
        }
        val dialogCallbackClass = dialogEventConstructor.parameterTypes[2]
        val eventBusClass = r.loadClass(
            loader,
            listOf("dc1", "gc1"),
            "native event bus"
        ) { type ->
            r.methods(type).any { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    type.isAssignableFrom(method.returnType)
            }
        }
        val eventBusGet = r.method(
            eventBusClass,
            listOf("b"),
            "native event-bus singleton accessor"
        ) { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                eventBusClass.isAssignableFrom(method.returnType)
        }
        val eventBusPost = r.method(
            eventBusClass,
            listOf("f"),
            "native event-bus post"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(dialogEventClass)
        }
        val unitClass = r.loadClass(
            loader,
            listOf("rf5", "uf5"),
            "Kotlin Unit runtime value"
        ) { type ->
            r.fields(type).any { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    type.isAssignableFrom(field.type)
            }
        }
        val unitField = r.field(
            unitClass,
            listOf("a"),
            "Kotlin Unit singleton"
        ) { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                unitClass.isAssignableFrom(field.type)
        }

        val popupMenuClass = loader.loadClass("androidx.appcompat.widget.PopupMenu")
        val popupMenuListenerClass = loader.loadClass(
            "androidx.appcompat.widget.PopupMenu\$OnMenuItemClickListener"
        )
        val popupMenuConstructor = popupMenuClass.getDeclaredConstructor(
            Context::class.java,
            View::class.java
        ).apply { isAccessible = true }
        val popupMenuGetMenu = popupMenuClass.getDeclaredMethod("getMenu").apply {
            isAccessible = true
        }
        val popupMenuSetListener = popupMenuClass.getDeclaredMethod(
            "setOnMenuItemClickListener",
            popupMenuListenerClass
        ).apply { isAccessible = true }
        val popupMenuShow = popupMenuClass.getDeclaredMethod("show").apply {
            isAccessible = true
        }

        val smartPlaylistClass = r.loadClass(
            loader,
            listOf("ts4", "ws4"),
            "native Smart-Playlist model"
        ) { type ->
            r.methods(type).any { method ->
                method.parameterTypes.contentEquals(arrayOf(File::class.java)) &&
                    (method.returnType == java.lang.Boolean.TYPE ||
                        method.returnType == java.lang.Boolean::class.java)
            } &&
                r.fields(type).any { java.util.List::class.java.isAssignableFrom(it.type) } &&
                r.fields(type).any { it.type == java.lang.Boolean.TYPE }
        }
        val smartPlaylistConstructor = smartPlaylistClass.declaredConstructors
            .firstOrNull { it.parameterCount == 0 }
            ?: smartPlaylistClass.declaredConstructors.singleOrNull { ctor ->
                ctor.parameterCount == 6 &&
                    ctor.parameterTypes[1] == Integer.TYPE &&
                    ctor.parameterTypes[2] == Integer.TYPE &&
                    ctor.parameterTypes[3] == Integer.TYPE &&
                    ctor.parameterTypes[5] == Integer.TYPE
            }
            ?: throw IllegalStateException(
                "Native Smart-Playlist constructor shape is ambiguous"
            )
        smartPlaylistConstructor.isAccessible = true
        val smartPlaylistConstructorArgs: Array<Any?> =
            if (smartPlaylistConstructor.parameterCount == 0) {
                emptyArray()
            } else {
                arrayOf(null, 0, 0, 0, null, 255)
            }
        val smartPlaylistSave = r.method(
            smartPlaylistClass,
            listOf("t"),
            "native Smart-Playlist writer"
        ) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(File::class.java)) &&
                (method.returnType == java.lang.Boolean.TYPE ||
                    method.returnType == java.lang.Boolean::class.java)
        }
        val smartPlaylistName = r.field(
            smartPlaylistClass,
            listOf("o"),
            "Smart-Playlist name"
        ) { it.type == String::class.java }
        val smartPlaylistRules = r.field(
            smartPlaylistClass,
            listOf("u"),
            "Smart-Playlist rule list"
        ) { java.util.List::class.java.isAssignableFrom(it.type) }
        val smartPlaylistMatchAll = r.field(
            smartPlaylistClass,
            listOf("s"),
            "Smart-Playlist match-all flag"
        ) { it.type == java.lang.Boolean.TYPE }

        val groupRuleClass = r.loadClass(
            loader,
            listOf("gt4", "jt4"),
            "Smart-rule group"
        ) { type ->
            type != baseRuleClass &&
                baseRuleClass.isAssignableFrom(type) &&
                r.fields(type).any { java.util.List::class.java.isAssignableFrom(it.type) } &&
                r.fields(type).any { it.type == java.lang.Boolean.TYPE }
        }
        val groupRules = r.field(
            groupRuleClass,
            listOf("o"),
            "Smart-rule group children"
        ) { java.util.List::class.java.isAssignableFrom(it.type) }
        val groupMatchAll = r.field(
            groupRuleClass,
            listOf("p"),
            "Smart-rule group match-all flag"
        ) { it.type == java.lang.Boolean.TYPE }

        val chooserConsumerAccept = r.optionalClass(
            loader,
            listOf(presenterClass.name + "\$g")
        )?.let { consumerClass ->
            r.optionalMethod(consumerClass, listOf("accept")) { method ->
                method.parameterCount == 1 && method.name == "accept"
            }
        }
        val ruleLabelFormatter = r.optionalClass(
            loader,
            listOf("ls2", "os2")
        )?.let { formatterClass ->
            r.optionalMethod(formatterClass, listOf("U")) { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0].isAssignableFrom(smartRuleClass) &&
                    method.returnType == String::class.java
            }
        }

        Log.i(
            TAG,
            "BRIDGE MAPPING | presenter=${presenterClass.name}" +
                " | leaf=${smartRuleClass.name}" +
                " | smart=${smartPlaylistClass.name}" +
                " | parser=${parserClass.name}" +
                " | dao=${playlistDaoClass.name}" +
                " | predicate=${predicateClass.name}"
        )

        return Bindings(
            loader = loader,
            presenterClass = presenterClass,
            presenterConstructor = presenterConstructor,
            baseRuleClass = baseRuleClass,
            smartRuleClass = smartRuleClass,
            smartRuleConstructor = smartRuleConstructor,
            ruleSentinel = ruleSentinel,
            ruleValue = ruleValue,
            presenterAddRule = presenterAddRule,
            presenterState = presenterState,
            presenterRefresh = presenterRefresh,
            presenterView = presenterView,
            stateRules = stateRules,
            statePaths = statePaths,
            stateSelectedIndex = stateSelectedIndex,
            stateDirty = stateDirty,
            ruleIdGetter = ruleIdGetter,
            ruleIdSetter = ruleIdSetter,
            playlistFileConstructor = playlistFileConstructor,
            fileModelConstructor = fileModelConstructor,
            playlistRead = playlistRead,
            playlistEntries = playlistEntries,
            fileModelFile = fileModelFile,
            nativeIn = nativeIn,
            nativeEquals = nativeEquals,
            uriField = uriField,
            idField = idField,
            whereGroupConstructor = whereGroupConstructor,
            databaseSingleton = databaseSingleton,
            playlistDaoGetter = playlistDaoGetter,
            playlistDaoAll = playlistDaoAll,
            dialogEventConstructor = dialogEventConstructor,
            dialogCallbackClass = dialogCallbackClass,
            eventBusGet = eventBusGet,
            eventBusPost = eventBusPost,
            unitValue = unitField.get(null),
            presenterLinkSmartPlaylist = presenterLinkSmartPlaylist,
            popupMenuConstructor = popupMenuConstructor,
            popupMenuGetMenu = popupMenuGetMenu,
            popupMenuSetListener = popupMenuSetListener,
            popupMenuShow = popupMenuShow,
            popupMenuListenerClass = popupMenuListenerClass,
            smartPlaylistConstructor = smartPlaylistConstructor,
            smartPlaylistConstructorArgs = smartPlaylistConstructorArgs,
            smartPlaylistSave = smartPlaylistSave,
            smartPlaylistName = smartPlaylistName,
            smartPlaylistRules = smartPlaylistRules,
            smartPlaylistMatchAll = smartPlaylistMatchAll,
            groupRuleClass = groupRuleClass,
            groupRules = groupRules,
            groupMatchAll = groupMatchAll,
            chooserConsumerAccept = chooserConsumerAccept,
            ruleLabelFormatter = ruleLabelFormatter,
            evaluationMethod = evaluationMethod
        )
    }
'''
controller, n = re.subn(
    r"    private fun createBindings\(loader: ClassLoader\): Bindings \{.*?\n    \}\n\n    private fun menuContext",
    create_bindings.rstrip() + "\n\n    private fun menuContext",
    controller,
    count=1,
    flags=re.S,
)
if n != 1:
    raise RuntimeError(f"createBindings replacement failed: {n}")

controller_path.write_text(controller, encoding="utf-8")

# GoneSmartModule: hook the exact methods/classes that the validated controller
# binding resolved. Optional cosmetic hooks no longer gate the feature.
module_path = ROOT / "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
module = module_path.read_text(encoding="utf-8")
new_hooks = r'''    private fun installPlaylistBridgeEditorHooks(
        loader: ClassLoader
    ): Int {
        val targets = playlistBridgeController.hookTargets() ?: run {
            playlistBridgeWarn(
                "Smart editor hook targets unavailable",
                IllegalStateException("Playlist Bridge bindings missing")
            )
            return 0
        }
        var installed = 0

        runCatching {
            hook(targets.presenterConstructor).intercept { chain ->
                val context = chain.getArg(0) as? android.content.Context
                val result = chain.proceed()
                playlistBridgeController.capturePresenter(
                    chain.getThisObject(),
                    context
                )
                result
            }
            installed++
        }.onFailure {
            playlistBridgeWarn("Smart editor presenter hook unavailable", it)
        }

        runCatching {
            hook(targets.presenterLinkSmartPlaylist).intercept { chain ->
                val edit = chain.getArg(0) as? Boolean == true
                if (
                    playlistBridgeController.interceptNativeLinkedEditor(
                        chain.getThisObject(),
                        edit
                    )
                ) {
                    null
                } else {
                    chain.proceed()
                }
            }
            installed++
        }.onFailure {
            playlistBridgeWarn("Smart link chooser hook unavailable", it)
        }

        targets.chooserConsumerAccept?.let { method ->
            runCatching {
                hook(method).intercept { chain ->
                    val previous = playlistBridgeSmartChooserTitleDepth.get()
                    playlistBridgeSmartChooserTitleDepth.set(previous + 1)
                    try {
                        chain.proceed()
                    } finally {
                        playlistBridgeSmartChooserTitleDepth.set(previous)
                    }
                }
                installed++
            }.onFailure {
                playlistBridgeWarn("Smart chooser title scope unavailable", it)
            }
        }

        // This native resource accessor is stable outside the remapped Smart
        // editor family. Keep it optional: title polish must never gate Link.
        runCatching {
            val basePresenterClass = loader.loadClass("bx")
            val method = basePresenterClass
                .getDeclaredMethod("K0", Integer.TYPE)
                .apply { isAccessible = true }
            hook(method).intercept { chain ->
                if (playlistBridgeSmartChooserTitleDepth.get() > 0) {
                    val id = chain.getArg(0) as? Int ?: 0
                    val context = playlistBridgeController.currentContext()
                    val resourceName = context?.takeIf {
                        NativeResourceIdPolicy.canResolveEntryName(id)
                    }?.let {
                        runCatching {
                            it.resources.getResourceEntryName(id)
                        }.getOrNull()
                    }
                    if (resourceName == "link_playlist") {
                        playlistBridgeController
                            .nativeSmartPlaylistLinkTitle()
                            ?.let { return@intercept it }
                    }
                }
                chain.proceed()
            }
            installed++
        }.onFailure {
            playlistBridgeWarn("Smart chooser title hook unavailable", it)
        }

        targets.ruleLabelFormatter?.let { method ->
            runCatching {
                hook(method).intercept { chain ->
                    val original = chain.proceed() as? String
                    playlistBridgeController.rewriteNativeSmartPlaylistRuleLabel(
                        chain.getArg(0),
                        original
                    )
                }
                installed++
            }.onFailure {
                playlistBridgeWarn("Smart rule label hook unavailable", it)
            }
        }

        return installed
    }

    private fun installPlaylistBridgeEvaluationHook(
        loader: ClassLoader
    ): Int {
        val method = playlistBridgeController.hookTargets()?.evaluationMethod
            ?: return 0
        return runCatching {
            hook(method).intercept { chain ->
                val rule = chain.getThisObject()
                if (!playlistBridgeController.isBridgeRule(rule) ||
                    !playlistBridgeController.isEnabled()
                ) {
                    return@intercept chain.proceed()
                }

                val started = SystemClock.elapsedRealtimeNanos()
                val result = runCatching {
                    playlistBridgeController.compile(rule)
                }.onFailure {
                    playlistBridgeWarn(
                        "Bridge rule compilation failed; using false predicate",
                        it
                    )
                }.getOrNull()
                    ?: playlistBridgeController.failClosedPredicate()
                    ?: throw IllegalStateException(
                        "Playlist Bridge fail-closed predicate unavailable"
                    )

                playlistBridgeInfo(
                    "RULE COMPILED | result=" + result.javaClass.name +
                        " | elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - started) /
                            1_000_000L)
                )
                result
            }
            1
        }.getOrElse {
            playlistBridgeWarn("Bridge evaluation hook unavailable", it)
            0
        }
    }
'''
module, n = re.subn(
    r"    private fun installPlaylistBridgeEditorHooks\(.*?\n    private fun playlistBridgeInfo",
    new_hooks.rstrip() + "\n\n    private fun playlistBridgeInfo",
    module,
    count=1,
    flags=re.S,
)
if n != 1:
    raise RuntimeError(f"module bridge hook replacement failed: {n}")
module_path.write_text(module, encoding="utf-8")

# Durable documentation: correct the post-release 4.2.1 regression claim and
# record the new resolver rule before device acceptance.
agents_path = ROOT / "AGENTS.md"
agents = agents_path.read_text(encoding="utf-8")
agents = replace_once(
    agents,
    "- Playlist Link is portable/fail-closed. Disabling it leaves old Smart Playlists openable and GoneSmart-only semantics inert.\n",
    "- Playlist Link is portable/fail-closed. Disabling it leaves old Smart Playlists openable and GoneSmart-only semantics inert.\n"
    "- Playlist Link must share semantic/runtime GMMP boundaries with the accepted Playlist/Smart-Playlist stack. Historical 4.2.0 obfuscated names are fast-path evidence only; the leaf rule, presenter, parser, query builder, playlist DAO/model and Smart writer must be shape-validated and ambiguity must fail closed.\n",
    "AGENTS Playlist Link invariant",
)
agents_path.write_text(agents, encoding="utf-8")

playbook_path = ROOT / "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
playbook = playbook_path.read_text(encoding="utf-8")
playbook = playbook.replace(
    "| 4.2.1 | **Accepted / 0.4.0 target** | Smart DJ, Playlist/Smart-Playlist features, Flip, Track Auto-DJ and compatibility/performance cleanup device-accepted. |",
    "| 4.2.1 | **Accepted core target; Playlist Link repair pending re-acceptance** | Core 0.4.x features are accepted. A post-0.4.1 Playlist Link regression exposed stale 4.2.0-only bridge bindings; the 0.4.2 repair candidate uses shape-validated runtime bindings and still requires one device re-acceptance pass. |"
)
playbook = playbook.replace(
    "- Playlist Link;\n",
    "- Playlist Link — post-0.4.1 regression discovered; 0.4.2 repair candidate pending device re-acceptance;\n"
)
playbook_path.write_text(playbook, encoding="utf-8")

links_path = ROOT / "docs/SMART_PLAYLIST_LINKS.md"
links = links_path.read_text(encoding="utf-8")
links = re.sub(
    r"\*\*Current status .*?\*\* This is branch acceptance, not a published v0\.4 release or a compatibility claim for other GMMP versions\.\n",
    "**Current status (8 October 2026): the feature remains accepted on GMMP 4.2.0, but a post-v0.4.1 device check found the 4.2.1 path broken by stale 4.2.0-only obfuscation bindings. The `fix/playlist-link-gmmp-4.2.1` / planned v0.4.2 repair replaces those bindings with shape-validated runtime resolution. Device re-acceptance is still pending.**\n",
    links,
    count=1,
)
links = links.replace(
    "The implementation intentionally reuses GMMP's original 4.2.0 internals rather than building a parallel Smart-Playlist evaluator:",
    "The implementation intentionally reuses GMMP's native internals rather than building a parallel Smart-Playlist evaluator. From the 4.2.1 repair onward, obfuscated names are only fast paths and every required editor/rule/parser/query/DAO/writer boundary is validated by runtime shape before use:"
)
links = links.replace(
    "- GMMP **4.2.0** is the tested target; obfuscated internals are version-sensitive.\n",
    "- GMMP **4.2.0** is the previously device-accepted Playlist Link baseline. GMMP **4.2.1** has a v0.4.2 repair candidate pending device re-acceptance.\n"
)
links_path.write_text(links, encoding="utf-8")

completion_path = ROOT / "docs/GMMP_421_COMPLETION.md"
completion = completion_path.read_text(encoding="utf-8")
marker = "## Post-release compatibility corrections\n"
if marker not in completion:
    completion += (
        "\n\n" + marker + "\n"
        "- 8 October 2026: Playlist Link was found broken on the shipping 4.2.1 path because its bridge still depended on the old 4.2.0 obfuscated presenter/rule/parser/query/DAO map. A planned 0.4.2 repair replaces that all-or-nothing name map with shape-validated runtime bindings. This item is **not** re-accepted until the repaired add/save/reopen/evaluate/edit flow is verified on device.\n"
    )
completion_path.write_text(completion, encoding="utf-8")

print("Applied Playlist Link 4.2.1 compatibility repair candidate")
