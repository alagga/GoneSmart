package io.github.alagga.gonesmart

import android.content.Context

/**
 * Companion-process cache for passively verified GMMP Equalizer center
 * frequencies. The GMMP version is part of the persisted identity so a player
 * update can never inherit an old frequency map as if it had been re-proved.
 */
object GmmpEqualizerBandRuntimeMetadata {
    private const val GMMP_PACKAGE = "gonemad.gmmp"
    private const val PREFS = "gonesmart_eq_band_runtime_metadata"
    private const val KEY_GMMP_VERSION = "gmmp_version"
    private const val KEY_BAND_COUNT = "band_count"
    private const val KEY_FREQUENCIES = "frequencies_hz"

    data class Snapshot(
        val gmmpVersion: String,
        val bandCount: Int,
        val frequenciesHz: List<Double>
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun restore(context: Context) {
        val appContext = context.applicationContext
        val installedVersion = installedGmmpVersion(appContext) ?: run {
            snapshot = null
            return
        }
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedVersion = prefs.getString(KEY_GMMP_VERSION, null)
        val count = prefs.getInt(KEY_BAND_COUNT, 0)
        val encoded = prefs.getString(KEY_FREQUENCIES, null)
        if (storedVersion != installedVersion || encoded == null) {
            snapshot = null
            if (storedVersion != null && storedVersion != installedVersion) prefs.edit().clear().apply()
            return
        }
        val values = encoded.split(',').mapNotNull { token -> token.toDoubleOrNull() }
        val validated = GmmpEqualizerBandPolicy.validatedFrequencies(values, count) ?: run {
            snapshot = null
            prefs.edit().clear().apply()
            return
        }
        snapshot = Snapshot(installedVersion, count, validated)
    }

    fun update(context: Context, gmmpVersion: String, bandCount: Int, frequenciesHz: List<Double>): Boolean {
        if (gmmpVersion.isBlank()) return false
        val appContext = context.applicationContext
        val installedVersion = installedGmmpVersion(appContext) ?: return false
        if (installedVersion != gmmpVersion) return false
        val validated = GmmpEqualizerBandPolicy.validatedFrequencies(frequenciesHz, bandCount) ?: return false
        val normalized = validated.joinToString(",") { AudioProfileField.formatNumber(it) }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GMMP_VERSION, installedVersion)
            .putInt(KEY_BAND_COUNT, bandCount)
            .putString(KEY_FREQUENCIES, normalized)
            .apply()
        snapshot = Snapshot(installedVersion, bandCount, validated)
        return true
    }

    fun frequenciesFor(bandCount: Int): List<Double>? =
        snapshot
            ?.takeIf { it.bandCount == bandCount }
            ?.frequenciesHz
            ?.toList()

    private fun installedGmmpVersion(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(GMMP_PACKAGE, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
