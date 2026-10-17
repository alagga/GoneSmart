package io.github.alagga.gonesmart

/**
 * Durable policy for the relationship between GMMP's normal DSP state and
 * output-specific GoneSmart overlays.
 *
 * The local/no-Bluetooth route is the Standard baseline. Native GMMP edits
 * made while Standard is active become the new baseline. Native edits made
 * while a Bluetooth output is active are session-local only and are discarded
 * when the output changes; they never rewrite Standard or the saved Bluetooth
 * profile.
 */
object AudioProfileRuntimePolicy {
    const val STANDARD_LABEL = "Standard (no Bluetooth)"

    fun isStandardRoute(routeId: String): Boolean =
        routeId == GoneSmartAudioProfiles.LOCAL_ROUTE_ID

    fun shouldUpdateStandardFromNativeObservation(routeId: String): Boolean =
        isStandardRoute(routeId)

    fun isTemporaryNativeEdit(routeId: String): Boolean =
        !isStandardRoute(routeId)
}
