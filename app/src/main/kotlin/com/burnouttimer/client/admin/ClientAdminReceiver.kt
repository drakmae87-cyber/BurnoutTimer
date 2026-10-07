package com.burnouttimer.client.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class ClientAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, ClientAdminReceiver::class.java)
    }
}
