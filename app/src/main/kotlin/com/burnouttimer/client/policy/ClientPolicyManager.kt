package com.burnouttimer.client.policy

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.burnouttimer.client.admin.ClientAdminReceiver
import com.burnouttimer.client.admin.PolicyAlarmReceiver
import com.burnouttimer.policy.DevicePolicy
import com.burnouttimer.policy.PolicyQrCodec
import java.util.UUID

class ClientPolicyManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val devicePolicyManager = appContext.getSystemService(DevicePolicyManager::class.java)
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    val clientId: String
        get() {
            val current = preferences.getString(KEY_CLIENT_ID, null)
            if (current != null) return current
            val generated = UUID.randomUUID().toString()
            check(preferences.edit().putString(KEY_CLIENT_ID, generated).commit()) {
                "No se pudo guardar el identificador de este cliente."
            }
            return generated
        }

    fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(appContext.packageName)

    fun hasTrustedAdmin(): Boolean = preferences.contains(KEY_ADMIN_PUBLIC_KEY)

    fun hasActivePolicy(): Boolean = activePolicy() != null

    fun currentMessage(): String? = preferences.getString(KEY_CURRENT_MESSAGE, null)

    fun activePolicyWindow(): Pair<Long, Long>? =
        activePolicy()?.let { it.startsAtEpochMillis to it.endsAtEpochMillis }

    fun isExactAlarmAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun trustAdmin(adminKeyQr: String) {
        val publicKey = PolicyQrCodec.parseAndPinAdminKey(adminKeyQr)
        val existingKey = trustedAdminKey()
        require(existingKey == null || existingKey.contentEquals(publicKey)) {
            "Este cliente ya está vinculado a otra clave de administración. No se reemplazó."
        }
        check(
            preferences.edit()
                .putString(KEY_ADMIN_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
                .remove(KEY_LAST_ERROR)
                .commit()
        ) { "No se pudo guardar la clave de administración." }
    }

    fun importPolicy(policyQr: String): String {
        check(isDeviceOwner()) {
            "Este dispositivo no está aprovisionado como Device Owner. No se aplicó ninguna restricción."
        }
        check(isExactAlarmAllowed()) {
            "Android no permite alarmas exactas. Activa el permiso y vuelve a importar la política para garantizar su caducidad."
        }
        val trustedKey = checkNotNull(trustedAdminKey()) { "Primero vincula la clave QR de administración." }
        val policy = PolicyQrCodec.verify(policyQr, trustedKey, clientId)
        check(policy.endsAtEpochMillis > System.currentTimeMillis()) { "La política ya ha caducado." }
        check(policy.startsAtEpochMillis > System.currentTimeMillis()) {
            "La hora de inicio debe ser futura. Genera una política nueva."
        }
        val existingPolicy = activePolicy()
        check(existingPolicy == null || existingPolicy.endsAtEpochMillis <= System.currentTimeMillis()) {
            "Ya existe una política activa o programada. Espera a que caduque antes de importar otra."
        }
        existingPolicy?.let(::expirePolicy)
        check(activePolicy() == null) {
            "No se sustituyó la política anterior porque todavía no se han reactivado todas sus apps."
        }
        check(policy.suspendedPackages.none(::isProtectedPackage)) {
            "La política incluye el propio cliente o una función esencial protegida del sistema."
        }
        schedule(policy)
        check(
            preferences.edit()
                .putString(KEY_ACTIVE_POLICY_QR, policyQr)
                .remove(KEY_LAST_ERROR)
                .commit()
        ) { "No se pudo guardar la política." }
        return "Política válida. Inicio: ${policy.startsAtEpochMillis}; fin: ${policy.endsAtEpochMillis}."
    }

    fun handleAlarm(action: String) {
        val policy = activePolicy() ?: return
        val now = System.currentTimeMillis()
        when {
            action == PolicyAlarmReceiver.ACTION_REMINDER && now < policy.startsAtEpochMillis ->
                showPolicyNotification(
                    "Aviso de sesión programada",
                    policy.startMessage,
                    NOTIFICATION_REMINDER_ID
                )
            now >= policy.endsAtEpochMillis -> {
                if (expirePolicy(policy)) {
                    showPolicyNotification(
                        "Sesión finalizada",
                        policy.endMessage,
                        NOTIFICATION_END_ID
                    )
                }
            }
            now >= policy.startsAtEpochMillis && action == PolicyAlarmReceiver.ACTION_START -> {
                activatePolicy(policy)
                preferences.edit().putString(KEY_CURRENT_MESSAGE, policy.startMessage).apply()
                showPolicyNotification(
                    "Sesión iniciada",
                    policy.startMessage,
                    NOTIFICATION_START_ID
                )
                scheduleEnd(policy.endsAtEpochMillis)
            }
            now < policy.startsAtEpochMillis -> schedule(policy)
            action == PolicyAlarmReceiver.ACTION_END -> scheduleEnd(policy.endsAtEpochMillis)
        }
    }

    fun restoreAfterBoot() {
        val policy = activePolicy() ?: return
        if (System.currentTimeMillis() >= policy.endsAtEpochMillis) {
            if (expirePolicy(policy)) {
                preferences.edit().putString(KEY_CURRENT_MESSAGE, policy.endMessage).apply()
                showPolicyNotification(
                    "Sesión finalizada",
                    policy.endMessage,
                    NOTIFICATION_END_ID
                )
            }
            return
        }
        check(isDeviceOwner()) { "Device Owner status was lost; scheduled restrictions were not restored." }
        schedule(policy)
        if (System.currentTimeMillis() >= policy.startsAtEpochMillis) {
            activatePolicy(policy)
            preferences.edit().putString(KEY_CURRENT_MESSAGE, policy.startMessage).apply()
        }
    }

    fun isManagedKioskActive(): Boolean {
        if (!isDeviceOwner()) return false
        val policy = activePolicy() ?: return false
        val now = System.currentTimeMillis()
        return policy.enableKiosk && now >= policy.startsAtEpochMillis && now < policy.endsAtEpochMillis
    }

    fun lastError(): String? = preferences.getString(KEY_LAST_ERROR, null)

    fun recordFailure(message: String) {
        preferences.edit().putString(KEY_LAST_ERROR, message).apply()
    }

    private fun activatePolicy(policy: DevicePolicy) {
        check(isDeviceOwner()) { "Device Owner ya no está activo; no se aplicó la política." }
        val admin = ClientAdminReceiver.componentName(appContext)
        val missingPackages = policy.suspendedPackages.filterNot(::isInstalledPackage)
        val installedPackages = policy.suspendedPackages.filter(::isInstalledPackage)
        val rejected = if (installedPackages.isEmpty()) {
            emptyList()
        } else {
            devicePolicyManager.setPackagesSuspended(admin, installedPackages.toTypedArray(), true).toList()
        }
        if (policy.enableKiosk) {
            val kioskPackages = (listOf(appContext.packageName) + ESSENTIAL_CALL_PACKAGES.filter(::isInstalledPackage))
                .distinct()
            devicePolicyManager.setLockTaskPackages(admin, kioskPackages.toTypedArray())
            setKioskHome(admin)
            setKioskFeatures(admin)
        } else {
            devicePolicyManager.setLockTaskPackages(admin, emptyArray())
        }
        val failures = (missingPackages + rejected).distinct()
        val error = if (failures.isNotEmpty()) {
            "No se pudieron suspender estos paquetes: ${failures.joinToString()}"
        } else {
            null
        }
        preferences.edit().putString(KEY_LAST_ERROR, error).apply()
    }

    private fun expirePolicy(policy: DevicePolicy): Boolean {
        val admin = ClientAdminReceiver.componentName(appContext)
        val suspendedPackages = policy.suspendedPackages.filter(::isInstalledPackage)
        val failedToResume = if (suspendedPackages.isEmpty()) {
            emptyList()
        } else {
            devicePolicyManager.setPackagesSuspended(admin, suspendedPackages.toTypedArray(), false).toList()
        }
        if (failedToResume.isNotEmpty()) {
            val message = "No se pudieron reactivar estos paquetes: ${failedToResume.joinToString()}"
            recordFailure(message)
            scheduleEnd(System.currentTimeMillis() + RETRY_DELAY_MILLIS)
            return false
        }
        devicePolicyManager.setLockTaskPackages(admin, emptyArray())
        restoreHome(admin)
        restoreLockTaskFeatures(admin)
        check(preferences.edit().remove(KEY_ACTIVE_POLICY_QR).remove(KEY_LAST_ERROR).commit()) {
            "No se pudo eliminar la política caducada."
        }
        return true
    }

    private fun activePolicy(): DevicePolicy? {
        val policyQr = preferences.getString(KEY_ACTIVE_POLICY_QR, null) ?: return null
        return try {
            PolicyQrCodec.verify(policyQr, checkNotNull(trustedAdminKey()), clientId)
        } catch (exception: IllegalArgumentException) {
            recordFailure(exception.message ?: "La política almacenada no es válida.")
            null
        }
    }

    private fun trustedAdminKey(): ByteArray? {
        val encoded = preferences.getString(KEY_ADMIN_PUBLIC_KEY, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    private fun schedule(policy: DevicePolicy) {
        scheduleAlarm(PolicyAlarmReceiver.ACTION_START, policy.startsAtEpochMillis, START_REQUEST_CODE)
        scheduleEnd(policy.endsAtEpochMillis)
        if (policy.alertBeforeStartMinutes == 0) {
            alarmManager.cancel(pendingIntent(PolicyAlarmReceiver.ACTION_REMINDER, REMINDER_REQUEST_CODE))
        } else {
            val alertTime = policy.startsAtEpochMillis - policy.alertBeforeStartMinutes * 60_000L
            if (alertTime > System.currentTimeMillis()) {
                scheduleAlarm(PolicyAlarmReceiver.ACTION_REMINDER, alertTime, REMINDER_REQUEST_CODE)
            }
        }
    }

    private fun scheduleEnd(time: Long) =
        scheduleAlarm(PolicyAlarmReceiver.ACTION_END, time, END_REQUEST_CODE)

    private fun scheduleAlarm(action: String, time: Long, requestCode: Int) {
        val pendingIntent = pendingIntent(action, requestCode)
        if (isExactAlarmAllowed()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
                return
            } catch (exception: SecurityException) {
                recordFailure(
                    exception.message
                        ?: "Se revocó el permiso de alarmas exactas; se usará un aviso con posible retraso."
                )
            }
        } else {
            recordFailure("Se revocó el permiso de alarmas exactas; se usará un aviso con posible retraso.")
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, PolicyAlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setKioskFeatures(admin: android.content.ComponentName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!preferences.contains(KEY_ORIGINAL_LOCK_TASK_FEATURES)) {
                check(
                    preferences.edit()
                        .putInt(KEY_ORIGINAL_LOCK_TASK_FEATURES, devicePolicyManager.getLockTaskFeatures(admin))
                        .commit()
                ) { "No se pudo guardar la configuración original del modo kiosk." }
            }
            devicePolicyManager.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            )
        }
    }

    private fun setKioskHome(admin: ComponentName) {
        val homeActivity = ComponentName(appContext, KioskHomeActivity::class.java)
        if (!preferences.contains(KEY_ORIGINAL_HOME_CAPTURED)) {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val currentHome = appContext.packageManager.resolveActivity(
                homeIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.let { ComponentName(it.packageName, it.name) }
            val originalHome = currentHome?.takeUnless { it == homeActivity }?.flattenToString()
            check(
                preferences.edit()
                    .putBoolean(KEY_ORIGINAL_HOME_CAPTURED, true)
                    .putString(KEY_ORIGINAL_HOME_COMPONENT, originalHome)
                    .commit()
            ) { "No se pudo guardar el lanzador original para restaurarlo al terminar." }
        }
        appContext.packageManager.setComponentEnabledSetting(
            homeActivity,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        devicePolicyManager.addPersistentPreferredActivity(admin, homeFilter(), homeActivity)
    }

    private fun restoreHome(admin: ComponentName) {
        if (!preferences.getBoolean(KEY_ORIGINAL_HOME_CAPTURED, false)) return
        val packageManager = appContext.packageManager
        devicePolicyManager.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
        val originalHome = preferences.getString(KEY_ORIGINAL_HOME_COMPONENT, null)
            ?.let { ComponentName.unflattenFromString(it) }
        if (originalHome != null && isInstalledPackage(originalHome.packageName)) {
            devicePolicyManager.addPersistentPreferredActivity(admin, homeFilter(), originalHome)
        }
        packageManager.setComponentEnabledSetting(
            ComponentName(appContext, KioskHomeActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        check(
            preferences.edit()
                .remove(KEY_ORIGINAL_HOME_CAPTURED)
                .remove(KEY_ORIGINAL_HOME_COMPONENT)
                .commit()
        ) { "No se pudo terminar la restauración del lanzador original." }
    }

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    private fun restoreLockTaskFeatures(admin: android.content.ComponentName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val original = preferences.getInt(KEY_ORIGINAL_LOCK_TASK_FEATURES, -1)
            if (original >= 0) {
                devicePolicyManager.setLockTaskFeatures(admin, original)
                preferences.edit().remove(KEY_ORIGINAL_LOCK_TASK_FEATURES).apply()
            }
        }
    }

    private fun showPolicyNotification(title: String, message: String, id: Int) {
        preferences.edit().putString(KEY_CURRENT_MESSAGE, message).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            recordFailure("Activa las notificaciones de Burnout Timer para recibir las alertas y mensajes.")
            return
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    POLICY_CHANNEL_ID,
                    "Alertas y mensajes de sesión",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val openClient = Intent(appContext, com.burnouttimer.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            id,
            openClient,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            NotificationManagerCompat.from(appContext).notify(
                id,
                NotificationCompat.Builder(appContext, POLICY_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            )
        } catch (exception: SecurityException) {
            recordFailure(exception.message ?: "Android bloqueó la notificación de la política.")
        }
    }

    private fun isInstalledPackage(packageName: String): Boolean =
        try {
            appContext.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }

    private fun isProtectedPackage(packageName: String): Boolean {
        if (packageName == appContext.packageName || packageName in DevicePolicy.PROTECTED_PACKAGES) {
            return true
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val currentHomePackage = appContext.packageManager.resolveActivity(
            homeIntent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        return packageName == currentHomePackage
    }

    companion object {
        private const val PREFERENCES = "client_policy"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ADMIN_PUBLIC_KEY = "admin_public_key"
        private const val KEY_ACTIVE_POLICY_QR = "active_policy_qr"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_CURRENT_MESSAGE = "current_message"
        private const val KEY_ORIGINAL_LOCK_TASK_FEATURES = "original_lock_task_features"
        private const val KEY_ORIGINAL_HOME_CAPTURED = "original_home_captured"
        private const val KEY_ORIGINAL_HOME_COMPONENT = "original_home_component"
        private const val START_REQUEST_CODE = 8610
        private const val END_REQUEST_CODE = 8611
        private const val REMINDER_REQUEST_CODE = 8612
        private const val RETRY_DELAY_MILLIS = 60_000L
        private const val POLICY_CHANNEL_ID = "admin_policy_alerts"
        private const val NOTIFICATION_REMINDER_ID = 8613
        private const val NOTIFICATION_START_ID = 8614
        private const val NOTIFICATION_END_ID = 8615
        const val ACTION_REMINDER = "com.burnouttimer.client.action.POLICY_REMINDER"
        private val ESSENTIAL_CALL_PACKAGES = listOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.android.incallui",
            "com.android.emergency"
        )
    }
}
