package com.burnouttimer.client.policy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.burnouttimer.MainActivity

class KioskHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = ClientPolicyManager(this)
        try {
            manager.restoreAfterBoot()
        } catch (exception: SecurityException) {
            manager.recordFailure(exception.message ?: "Android rechazó restaurar la política.")
        } catch (exception: IllegalStateException) {
            manager.recordFailure(exception.message ?: "No se pudo restaurar la política.")
        }
        if (manager.isManagedKioskActive()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }
}
