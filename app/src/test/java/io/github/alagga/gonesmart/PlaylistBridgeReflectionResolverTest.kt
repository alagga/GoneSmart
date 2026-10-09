package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    private open class RuleBase
    private class RuleLeaf : RuleBase()

    private open class PresenterParent {
        @Suppress("unused") fun S1(value: Any) = Unit
    }

    private class PresenterFixture : PresenterParent() {
        @Suppress("unused") fun Q1(value: RuleBase) = Unit
    }

    private interface QueryFieldMarker

    private class QueryColumn(private val sql: String) : QueryFieldMarker {
        override fun toString(): String = sql
    }

    private class OpaqueQueryColumn(private val sql: String) : QueryFieldMarker {
        override fun toString(): String = "opaque-column"
    }

    private object QueryFieldsFixture {
        @JvmField val a = QueryColumn("song_id")
        @JvmField val b = QueryColumn("track_uri")
    }

    private object ErasedQueryFieldsFixture {
        @JvmField val a: QueryFieldMarker = QueryColumn("song_id")
        @JvmField val b: QueryFieldMarker = QueryColumn("track_uri")
    }

    private object OpaqueQueryFieldsFixture {
        @JvmField val a: QueryFieldMarker = OpaqueQueryColumn("song_id")
        @JvmField val b: QueryFieldMarker = OpaqueQueryColumn("track_uri")
    }

    private object AmbiguousQueryFieldsFixture {
        @JvmField val a = QueryColumn("track_uri")
        @JvmField val b = QueryColumn("track_uri")
    }

    private object WrongHistoricalQueryFieldsFixture {
        @JvmField val a = QueryColumn("song_id")
    }

    private object RemappedQueryFieldsFixture {
        @JvmField val x = QueryColumn("song_id")
        @JvmField val y = QueryColumn("track_uri")
    }

    private class BlindQueryColumn : QueryFieldMarker {
        override fun toString(): String = "opaque-column"
    }

    private class QueryPredicate(private val sql: String) {
        override fun toString(): String = sql
    }

    private class NativePredicateGraph(
        @Suppress("unused") private val field: QueryFieldMarker,
        @Suppress("unused") private val nested: Any? = null
    )

    private object BlindQueryFieldsFixture {
        @JvmField val id: QueryFieldMarker = BlindQueryColumn()
        @JvmField val uri: QueryFieldMarker = BlindQueryColumn()
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
    fun presenterRuleActionIgnoresInheritedBroadDistractors() {
        val method = PlaylistBridgeReflectionResolver.method(
            PresenterFixture::class.java,
            listOf("P1", "Q1"),
            "presenter rule action"
        ) { candidate ->
            PlaylistBridgeReflectionResolver.matchesDeclaredPresenterRuleAction(
                method = candidate,
                presenterClass = PresenterFixture::class.java,
                baseRuleClass = RuleBase::class.java
            )
        }

        assertEquals("Q1", method.name)
        assertSame(PresenterFixture::class.java, method.declaringClass)
        assertSame(RuleBase::class.java, method.parameterTypes.single())
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

    @Test
    fun nativeQueryFieldCanBeRecoveredFromCompiledPredicateGraph() {
        val field = QueryColumn("song_id")
        val root = NativePredicateGraph(
            field = field,
            nested = listOf(field)
        )

        assertSame(
            field,
            PlaylistBridgeReflectionResolver.uniqueInstanceInObjectGraph(
                root = root,
                valueClass = QueryFieldMarker::class.java
            )
        )
    }

    @Test
    fun compiledPredicateGraphAmbiguityFailsClosed() {
        val root = NativePredicateGraph(
            field = QueryColumn("song_id"),
            nested = QueryColumn("track_uri")
        )

        assertNull(
            PlaylistBridgeReflectionResolver.uniqueInstanceInObjectGraph(
                root = root,
                valueClass = QueryFieldMarker::class.java
            )
        )
    }

    @Test
    fun semanticStaticFieldFallbackIgnoresObfuscatedName() {
        val field = PlaylistBridgeReflectionResolver.field(
            QueryFieldsFixture::class.java,
            listOf("URI"),
            "semantic query field"
        ) { candidate ->
            PlaylistBridgeReflectionResolver.matchesStaticFieldSemanticValue(
                field = candidate,
                valueClass = QueryColumn::class.java,
                expectedValue = "track_uri"
            )
        }
        assertEquals("b", field.name)
    }

    @Test
    fun semanticStaticFieldUsesRuntimeValueTypeNotDeclaredFieldType() {
        val field = PlaylistBridgeReflectionResolver.field(
            ErasedQueryFieldsFixture::class.java,
            listOf("URI"),
            "semantic query field"
        ) { candidate ->
            PlaylistBridgeReflectionResolver.matchesStaticFieldSemanticValue(
                field = candidate,
                valueClass = QueryColumn::class.java,
                expectedValue = "track_uri"
            )
        }
        assertEquals("b", field.name)
        assertSame(QueryFieldMarker::class.java, field.type)
    }

    @Test
    fun semanticStaticFieldCanReadOpaqueRuntimeValueGraph() {
        val field = PlaylistBridgeReflectionResolver.field(
            OpaqueQueryFieldsFixture::class.java,
            listOf("URI"),
            "semantic query field"
        ) { candidate ->
            PlaylistBridgeReflectionResolver.matchesStaticFieldSemanticValue(
                field = candidate,
                valueClass = QueryFieldMarker::class.java,
                expectedValue = "track_uri"
            )
        }
        assertEquals("b", field.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun semanticStaticFieldAmbiguityFailsClosed() {
        PlaylistBridgeReflectionResolver.field(
            AmbiguousQueryFieldsFixture::class.java,
            listOf("URI"),
            "semantic query field"
        ) { candidate ->
            PlaylistBridgeReflectionResolver.matchesStaticFieldSemanticValue(
                field = candidate,
                valueClass = QueryColumn::class.java,
                expectedValue = "track_uri"
            )
        }
    }

    @Test
    fun unavailableAndroidFieldMetadataIsSkippedInsteadOfAbortingDiscovery() {
        val fields = PlaylistBridgeReflectionResolver.declaredFieldsSafely(
            QueryFieldsFixture::class.java
        ) {
            throw NoClassDefFoundError("android/view/ScrollFeedbackProvider")
        }
        assertTrue(fields.isEmpty())
    }

    @Test
    fun semanticFieldDiscoveryFallsBackBeyondHistoricalHolderNames() {
        val field = PlaylistBridgeReflectionResolver.resolveStaticFieldBySemanticValue(
            preferredHolders = listOf(WrongHistoricalQueryFieldsFixture::class.java),
            fallbackHolders = sequenceOf(RemappedQueryFieldsFixture::class.java),
            valueClass = QueryColumn::class.java,
            expectedValue = "track_uri",
            description = "remapped query field"
        )
        assertEquals("y", field.name)
        assertSame(RemappedQueryFieldsFixture::class.java, field.declaringClass)
    }

    @Test
    fun semanticFieldDiscoveryMayUseResolvedNativeBuilderAsOpaqueProbe() {
        val field = PlaylistBridgeReflectionResolver.resolveStaticFieldBySemanticValue(
            preferredHolders = emptyList(),
            fallbackHolders = sequenceOf(BlindQueryFieldsFixture::class.java),
            valueClass = QueryFieldMarker::class.java,
            expectedValue = "track_uri",
            description = "opaque query field",
            valueProbe = { value ->
                when {
                    value === BlindQueryFieldsFixture.uri ->
                        QueryPredicate("track_uri IN (?)")
                    value === BlindQueryFieldsFixture.id ->
                        QueryPredicate("song_id IN (?)")
                    else -> null
                }
            }
        )
        assertEquals("uri", field.name)
    }
}
