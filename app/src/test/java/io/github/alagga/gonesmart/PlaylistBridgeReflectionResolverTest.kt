package io.github.alagga.gonesmart

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

    private open class RuleBase
    private class RuleLeaf : RuleBase()

    private open class PresenterParent {
        @Suppress("unused") fun S1(value: Any) = Unit
    }

    private class PresenterFixture : PresenterParent() {
        @Suppress("unused") fun Q1(value: RuleBase) = Unit
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
}
