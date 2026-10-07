package com.burnouttimer.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.burnouttimer.domain.model.SessionState
import com.burnouttimer.domain.repository.SessionPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BurnoutOverlayService : Service() {
    @Inject lateinit var sessionPreferences: SessionPreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> {
                val durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L)
                if (durationMillis !in MIN_SESSION_MILLIS..MAX_SESSION_MILLIS) {
                    Log.w(TAG, "Se rechazó una sesión con una duración no válida.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Preparando tu sesión de enfoque."))
                timerJob?.cancel()
                timerJob = serviceScope.launch {
                    val endTime = System.currentTimeMillis() + durationMillis
                    sessionPreferences.setSessionState(
                        SessionState(isActive = true, endsAtEpochMillis = endTime)
                    )
                    runTimer(endTime)
                }
            }
            ACTION_RESTORE, null -> {
                startForeground(NOTIFICATION_ID, buildNotification("Restaurando tu sesión de enfoque."))
                timerJob?.cancel()
                timerJob = serviceScope.launch {
                    val state = sessionPreferences.sessionState.first()
                    if (!state.isActive || state.endsAtEpochMillis <= System.currentTimeMillis()) {
                        stopSession()
                    } else {
                        runTimer(state.endsAtEpochMillis)
                    }
                }
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return if (intent == null || intent.action == ACTION_START || intent.action == ACTION_RESTORE) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private suspend fun runTimer(endTime: Long) {
        while (true) {
            val remaining = endTime - System.currentTimeMillis()
            if (remaining <= 0L) break
            val remainingMinutes = (remaining + 59_999L) / 60_000L
            updateNotification("Sesión de enfoque: quedan $remainingMinutes min")
            delay(minOf(60_000L, remaining))
        }
        sessionPreferences.setSessionState(SessionState())
        showCompletionNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSession() {
        timerJob?.cancel()
        serviceScope.launch {
            sessionPreferences.setSessionState(SessionState())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Burnout Timer")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Finalizar sesión",
                notificationAction(ACTION_STOP)
            )
            .build()

    private fun updateNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (exception: SecurityException) {
            Log.w(TAG, "Android bloqueó la notificación del temporizador.", exception)
        }
    }

    private fun showCompletionNotification() {
        try {
            NotificationManagerCompat.from(this).notify(
                COMPLETION_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Temporizador completado")
                    .setContentText("La sesión terminó. Puedes iniciar otra cuando quieras.")
                    .setAutoCancel(true)
                    .build()
            )
        } catch (exception: SecurityException) {
            Log.w(TAG, "Android bloqueó el aviso de finalización del temporizador.", exception)
        }
    }

    private fun notificationAction(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, BurnoutOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Temporizador",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "BurnoutTimerService"
        const val CHANNEL_ID = "focus_timer"
        const val NOTIFICATION_ID = 41
        private const val COMPLETION_NOTIFICATION_ID = 42
        const val ACTION_START = "com.burnouttimer.action.START"
        const val ACTION_STOP = "com.burnouttimer.action.STOP"
        const val ACTION_RESTORE = "com.burnouttimer.action.RESTORE"
        const val EXTRA_DURATION_MILLIS = "duration_millis"
        const val MIN_SESSION_MILLIS = 60_000L
        const val MAX_SESSION_MILLIS = 24 * 60 * 60 * 1_000L

        fun start(context: Context, durationMillis: Long) {
            val intent = Intent(context, BurnoutOverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DURATION_MILLIS, durationMillis)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun restore(context: Context) {
            val intent = Intent(context, BurnoutOverlayService::class.java).setAction(ACTION_RESTORE)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BurnoutOverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
