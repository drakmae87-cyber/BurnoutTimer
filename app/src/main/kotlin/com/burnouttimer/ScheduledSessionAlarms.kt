package com.burnouttimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.burnouttimer.data.local.ScheduledSessionDao
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class ScheduleResult {
    SCHEDULED,
    OVERLAP,
    REMINDER_FAILED
}

class ScheduledSessionAlarms(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager
) {
    fun schedule(sessionId: Long, startsAtEpochMillis: Long) {
        val pendingIntent = pendingIntent(sessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    startsAtEpochMillis,
                    pendingIntent
                )
                return
            } catch (exception: SecurityException) {
                Log.w(TAG, "Exact alarm access is unavailable; scheduling an inexact reminder.", exception)
            }
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            startsAtEpochMillis,
            pendingIntent
        )
    }

    fun cancel(sessionId: Long) {
        alarmManager.cancel(pendingIntent(sessionId))
    }

    private fun pendingIntent(sessionId: Long): PendingIntent {
        val intent = Intent(context, ScheduledSessionAlertReceiver::class.java)
            .setAction(ACTION_ALERT)
            .setData(android.net.Uri.parse("burnouttimer://scheduled-session/$sessionId"))
            .putExtra(EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(
            context,
            sessionId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "ScheduledSessionAlarms"
        const val ACTION_ALERT = "com.burnouttimer.action.SCHEDULED_SESSION_ALERT"
        const val EXTRA_SESSION_ID = "scheduled_session_id"
    }
}

@AndroidEntryPoint
class ScheduledSessionAlertReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduledSessionDao: ScheduledSessionDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledSessionAlarms.ACTION_ALERT) return
        val sessionId = intent.getLongExtra(ScheduledSessionAlarms.EXTRA_SESSION_ID, 0L)
        if (sessionId <= 0L) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val session = scheduledSessionDao.getById(sessionId)
                if (session != null && session.isEnabled) {
                    createNotificationChannel(context)
                    val startIntent = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val contentIntent = PendingIntent.getActivity(
                        context,
                        sessionId.toInt(),
                        startIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val formattedTime = DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT,
                        java.util.Locale.forLanguageTag("es")
                    ).format(Date(session.startsAtEpochMillis))
                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("Burnout Timer · Recordatorio")
                        .setContentText("${session.title} · $formattedTime")
                        .setStyle(
                            NotificationCompat.BigTextStyle().bigText(
                                    "Es la hora programada para ${session.title}. Abre la app para iniciar la sesión."
                            )
                        )
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .build()
                    try {
                        NotificationManagerCompat.from(context).notify(sessionId.toInt(), notification)
                    } catch (exception: SecurityException) {
                        Log.w(TAG, "Notification permission is unavailable for this reminder.", exception)
                    }
                }
            } catch (exception: SQLiteException) {
                Log.e(TAG, "Unable to read the scheduled session reminder.", exception)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Recordatorios de sesiones",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private companion object {
        const val TAG = "ScheduledSessionAlert"
        const val CHANNEL_ID = "scheduled_session_alerts"
    }
}

@AndroidEntryPoint
class ScheduledSessionBootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduledSessionDao: ScheduledSessionDao
    @Inject lateinit var scheduledSessionAlarms: ScheduledSessionAlarms

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED
            )
        ) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                scheduledSessionDao.getUpcoming(System.currentTimeMillis()).forEach { session ->
                    scheduledSessionAlarms.schedule(session.id, session.startsAtEpochMillis)
                }
            } catch (exception: SQLiteException) {
                Log.e(TAG, "Unable to restore scheduled-session reminders.", exception)
            } catch (exception: SecurityException) {
                Log.e(TAG, "Android denied access while restoring session reminders.", exception)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "ScheduledSessionBoot"
    }
}
