package com.burnouttimer.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.ForegroundServiceStartNotAllowedException
import android.util.Log
import com.burnouttimer.domain.model.SessionState
import com.burnouttimer.domain.repository.SessionPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var sessionPreferences: SessionPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val state: SessionState = sessionPreferences.sessionState.first()
                if (state.isActive && state.endsAtEpochMillis > System.currentTimeMillis()) {
                    BurnoutOverlayService.restore(context)
                } else if (state.isActive) {
                    sessionPreferences.setSessionState(SessionState())
                }
            } catch (exception: IOException) {
                Log.e(TAG, "Unable to read the saved focus session after boot.", exception)
            } catch (exception: SecurityException) {
                Log.e(TAG, "Android denied restoration of the foreground timer service.", exception)
            } catch (exception: ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Android did not allow the timer service to start after boot.", exception)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Unable to restore the focus session after boot.", exception)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "BurnoutTimerBoot"
    }
}
