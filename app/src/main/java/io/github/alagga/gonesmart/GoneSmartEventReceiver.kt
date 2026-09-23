package io.github.alagga.gonesmart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GoneSmartEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GoneSmartRuntimeContract.ACTION_RUNTIME_EVENT) {
            return
        }

        val mode = intent.getStringExtra(GoneSmartRuntimeContract.EXTRA_MODE)
            ?: GoneSmartRuntimeContract.MODE_NONE
        val message = intent.getStringExtra(GoneSmartRuntimeContract.EXTRA_MESSAGE)
            ?: return
        val appendEvent = intent.getBooleanExtra(
            GoneSmartRuntimeContract.EXTRA_EVENT,
            true
        )

        GoneSmartEventStore.record(
            context = context,
            mode = mode,
            message = message,
            appendEvent = appendEvent,
            eventOnly = intent.getBooleanExtra(
                GoneSmartRuntimeContract.EXTRA_EVENT_ONLY,
                false
            ),
            category = intent.getStringExtra(
                GoneSmartRuntimeContract.EXTRA_CATEGORY
            )
        )
    }
}
