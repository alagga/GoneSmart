package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfileRuntimePolicyTest {

    @Test
    fun localRouteIsTheOnlyStandardBaseline() {
        assertTrue(AudioProfileRuntimePolicy.isStandardRoute(GoneSmartAudioProfiles.LOCAL_ROUTE_ID))
        assertTrue(
            AudioProfileRuntimePolicy.shouldUpdateStandardFromNativeObservation(
                GoneSmartAudioProfiles.LOCAL_ROUTE_ID
            )
        )
        assertFalse(AudioProfileRuntimePolicy.isTemporaryNativeEdit(GoneSmartAudioProfiles.LOCAL_ROUTE_ID))
    }

    @Test
    fun bluetoothNativeEditsAreTemporaryAndNeverRedefineStandard() {
        val bluetoothRoute = "bt_demo"
        assertFalse(AudioProfileRuntimePolicy.isStandardRoute(bluetoothRoute))
        assertFalse(AudioProfileRuntimePolicy.shouldUpdateStandardFromNativeObservation(bluetoothRoute))
        assertTrue(AudioProfileRuntimePolicy.isTemporaryNativeEdit(bluetoothRoute))
    }
}
