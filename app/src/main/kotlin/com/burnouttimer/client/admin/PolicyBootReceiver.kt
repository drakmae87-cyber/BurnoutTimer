package com.burnouttimer.client.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.burnouttimer.client.policy.ClientPolicyManager

class PolicyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                val manager = ClientPolicyManager(context)
                manager.restoreAfterBoot()
                if (manager.isManagedKioskActive()) {
                    context.startActivity(
                        Intent(context, com.burnouttimer.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }
            } catch (exception: SecurityException) {
                Log.e(TAG, "Android rechazó la restauración de la política.", exception)
                ClientPolicyManager(context).recordFailure(
                    exception.message ?: "Android rechazó la restauración de la política."
                )
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "No se pudo restaurar la política.", exception)
                ClientPolicyManager(context).recordFailure(
                    exception.message ?: "No se pudo restaurar la política."
                )
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "PolicyBootReceiver"
    }
}
