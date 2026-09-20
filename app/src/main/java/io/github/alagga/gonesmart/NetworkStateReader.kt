package io.github.alagga.gonesmart

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

enum class GoneSmartNetworkState {
    ONLINE,
    OFFLINE,
    UNKNOWN
}

class NetworkStateReader {

    companion object {

        private const val TAG =
            "GoneSmart"
    }

    fun getState(): GoneSmartNetworkState {

        val application =
            getCurrentApplication()
                ?: return GoneSmartNetworkState.UNKNOWN

        return try {

            val connectivityManager =
                application.getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as? ConnectivityManager
                    ?: return GoneSmartNetworkState.UNKNOWN

            val activeNetwork =
                connectivityManager.activeNetwork
                    ?: return GoneSmartNetworkState.OFFLINE

            val capabilities =
                connectivityManager
                    .getNetworkCapabilities(
                        activeNetwork
                    )
                    ?: return GoneSmartNetworkState.OFFLINE

            val hasInternet =
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )

            val validated =
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )

            if (
                hasInternet &&
                validated
            ) {

                GoneSmartNetworkState.ONLINE

            } else {

                GoneSmartNetworkState.OFFLINE
            }

        } catch (securityException: SecurityException) {

            Log.w(
                TAG,
                "Unable to read network state",
                securityException
            )

            GoneSmartNetworkState.UNKNOWN

        } catch (throwable: Throwable) {

            Log.w(
                TAG,
                "Unable to determine network state",
                throwable
            )

            GoneSmartNetworkState.UNKNOWN
        }
    }

    private fun getCurrentApplication(): Application? {

        return try {

            val activityThreadClass =
                Class.forName(
                    "android.app.ActivityThread"
                )

            val currentApplicationMethod =
                activityThreadClass
                    .getDeclaredMethod(
                        "currentApplication"
                    )

            currentApplicationMethod.isAccessible =
                true

            currentApplicationMethod.invoke(
                null
            ) as? Application

        } catch (throwable: Throwable) {

            Log.w(
                TAG,
                "Unable to obtain GMMP Application instance",
                throwable
            )

            null
        }
    }
}