package com.burnouttimer.client.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.burnouttimer.client.policy.ClientPolicyManager

class PolicyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                val manager = ClientPolicyManager(context)
                manager.handleAlarm(intent.action.orEmpty())
                if (intent.action == ACTION_START && manager.isManagedKioskActive()) {
                    context.startActivity(
                        Intent(context, com.burnouttimer.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                } else if (intent.action == ACTION_END && !manager.hasActivePolicy()) {
                    context.startActivity(
                        Intent(context, com.burnouttimer.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }
            } catch (exception: SecurityException) {
                Log.e(TAG, "Android rechazó la aplicación de la política programada.", exception)
                ClientPolicyManager(context).recordFailure(
                    exception.message ?: "Android rechazó la política programada."
                )
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "No se pudo aplicar la política programada.", exception)
                ClientPolicyManager(context).recordFailure(
                    exception.message ?: "No se pudo aplicar la política programada."
                )
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_START = "com.burnouttimer.client.action.POLICY_START"
        const val ACTION_END = "com.burnouttimer.client.action.POLICY_END"
        const val ACTION_REMINDER = "com.burnouttimer.client.action.POLICY_REMINDER"
        private const val TAG = "PolicyAlarmReceiver"
    }
}
