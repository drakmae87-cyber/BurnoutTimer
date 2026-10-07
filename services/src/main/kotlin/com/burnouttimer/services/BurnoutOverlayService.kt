package com.burnouttimer.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
    private val lifecycleOwner = OverlayLifecycleOwner()
    private var timerJob: Job? = null
    private var overlayView: ComposeView? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lifecycleOwner.start()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> {
                val durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L)
                if (durationMillis !in MIN_SESSION_MILLIS..MAX_SESSION_MILLIS) {
                    Log.w(TAG, "Rejected session with an invalid duration.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Preparing your focus session."))
                timerJob?.cancel()
                timerJob = serviceScope.launch {
                    val endTime = System.currentTimeMillis() + durationMillis
                    sessionPreferences.setSessionState(SessionState(isActive = true, endsAtEpochMillis = endTime))
                    runTimer(endTime)
                }
            }
            ACTION_RESTORE, null -> {
                startForeground(NOTIFICATION_ID, buildNotification("Restoring your focus session."))
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
            updateNotification("Focus session: $remainingMinutes min remaining")
            delay(minOf(60_000L, remaining))
        }
        sessionPreferences.setSessionState(SessionState())
        updateNotification("Session complete. You can dismiss this message or start another session.")
        showCompletionOverlay()
    }

    private fun showCompletionOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            updateNotification("Session complete. Open the app to dismiss this message.")
            return
        }
        if (overlayView != null) return

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Your focus session is complete.")
                            Text("Take a breath, then choose what feels right for you.")
                            Button(onClick = { stopSession() }, modifier = Modifier.padding(top = 16.dp)) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            windowManager?.addView(view, layoutParams)
            overlayView = view
        } catch (exception: WindowManager.BadTokenException) {
            Log.e(TAG, "Unable to display the optional completion overlay.", exception)
            updateNotification("Session complete. Open the app to dismiss this message.")
        } catch (exception: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked before display.", exception)
            updateNotification("Session complete. Open the app to dismiss this message.")
        }
    }

    private fun stopSession() {
        timerJob?.cancel()
        serviceScope.launch {
            sessionPreferences.setSessionState(SessionState())
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Overlay view was already detached.", exception)
            }
            overlayView = null
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
                "End session",
                notificationAction(ACTION_STOP)
            )
            .build()

    private fun updateNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification permission is unavailable; the timer remains active.", exception)
        }
    }

    private fun notificationAction(action: String) =
        android.app.PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, BurnoutOverlayService::class.java).setAction(action),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Focus timer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        removeOverlay()
        lifecycleOwner.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        fun start() {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        const val TAG = "BurnoutTimerService"
        const val CHANNEL_ID = "focus_timer"
        const val NOTIFICATION_ID = 41
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
