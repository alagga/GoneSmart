package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NativePlaylistCreateBindingTest {
    private val loader = javaClass.classLoader!!

    @Test fun recognizes421WriterAndRootGetterInsteadOfStale420Mappings() {
        assertEquals(
            NativePlaylistCreateBinding.Layout.GMMP_421,
            NativePlaylistCreateBinding.resolveLayout(loader)
        )
    }

    @Test fun resolvesMainAndPickerToTheirExact421NativePreferenceDelegate() {
        val layout = NativePlaylistCreateBinding.Layout.GMMP_421
        for (callbackClass in listOf("sp3", "go3")) {
            val constructor = loader.loadClass(callbackClass).declaredConstructors.single()
            constructor.isAccessible = true
            val callback = constructor.newInstance(
                constructor.parameterTypes[0].getDeclaredConstructor()
                    .apply { isAccessible = true }.newInstance()
            )
            val delegate = NativePlaylistCreateBinding.resolveDelegate(callback, layout)
            assertNotNull(callbackClass, delegate)
            assertEquals("sa4", delegate!!.javaClass.name)
            assertEquals(
                "/storage/emulated/0/gmmp/playlists",
                delegate.javaClass.getDeclaredMethod("getValue")
                    .apply { isAccessible = true }.invoke(delegate)
            )
        }
        assertNull(NativePlaylistCreateBinding.resolveDelegate(Any(), layout))
    }

    @Test fun selectedPickerSourcesResolveByUniqueTypesAndRestore() {
        val callbackClass = loader.loadClass("go3")
        val constructor = callbackClass.declaredConstructors.single()
        constructor.isAccessible = true
        val callback = constructor.newInstance(
            constructor.parameterTypes[0].getDeclaredConstructor()
                .apply { isAccessible = true }.newInstance()
        )
        val triple = NativePlaylistCreateBinding.resolvePickerSelectedList(
            callback, NativePlaylistCreateBinding.Layout.GMMP_421
        )
        assertNotNull(triple)
        val (owner, field, original) = triple!!
        assertEquals("io3", owner.javaClass.name)
        field.set(owner, emptyList<Any>())
        assertEquals(0, (field.get(owner) as List<*>).size)
        field.set(owner, original)
        assertSame(original, field.get(owner))
        assertNull(
            NativePlaylistCreateBinding.resolvePickerSelectedList(
                Any(), NativePlaylistCreateBinding.Layout.GMMP_421
            )
        )
    }

    private class Marker
    private class RenamedField(val opaqueFieldFromNextR8: Marker = Marker())
    private class AmbiguousFields(val first: Marker = Marker(), val second: Marker = Marker())

    @Test fun uniqueRuntimeEdgesSurviveObfuscatedFieldRenameAndRejectAmbiguity() {
        val stable = RenamedField()
        assertSame(
            stable.opaqueFieldFromNextR8,
            NativePlaylistCreateBinding.uniqueTypedMember(stable, Marker::class.java)
        )
        assertNull(
            NativePlaylistCreateBinding.uniqueTypedMember(
                AmbiguousFields(), Marker::class.java
            )
        )
    }

    @Test fun cancellationReturns421NativeKotlinUnitNot420CoroutineContext() {
        val actual = NativePlaylistCreateBinding.nativeUnit(
            loader, NativePlaylistCreateBinding.Layout.GMMP_421
        )
        assertNotNull(actual)
        assertSame(loader.loadClass("ag5").getDeclaredField("a")
            .apply { isAccessible = true }.get(null), actual)
    }
}
