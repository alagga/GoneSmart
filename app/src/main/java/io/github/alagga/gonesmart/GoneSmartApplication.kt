package io.github.alagga.gonesmart

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class GoneSmartApplication : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
        }
    }
}
