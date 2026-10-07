package com.burnouttimer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnouttimer.domain.model.SessionState
import com.burnouttimer.domain.repository.SessionPreferences
import com.burnouttimer.services.BurnoutOverlayService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val overlayAllowed = mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayAllowed.value = Settings.canDrawOverlays(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FocusTimerScreen(
                        overlayAllowed = overlayAllowed.value,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed.value = Settings.canDrawOverlays(this)
    }
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    preferences: SessionPreferences
) : ViewModel() {
    val session = preferences.sessionState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SessionState()
    )
}

@Composable
private fun FocusTimerScreen(
    overlayAllowed: Boolean,
    onRequestNotificationPermission: () -> Unit,
    viewModel: SessionViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val context = LocalContext.current
    val session by viewModel.session.collectAsState()
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.isActive, session.endsAtEpochMillis) {
        while (session.isActive) {
            currentTime = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val remainingMillis = (session.endsAtEpochMillis - currentTime).coerceAtLeast(0L)
    val remainingMinutes = (remainingMillis + 59_999L) / 60_000L
    val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Burnout Timer", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            if (session.isActive) "Your session is active: $remainingMinutes min remaining."
            else "Start a voluntary focus session."
        )
        Spacer(Modifier.height(24.dp))
        if (!overlayAllowed) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }) {
                Text("Enable optional end-of-session overlay")
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!notificationPermissionGranted) {
            Text(
                "A persistent notification shows the timer and lets you end it at any time.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onRequestNotificationPermission) {
                Text("Allow timer notification")
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !session.isActive,
                onClick = { BurnoutOverlayService.start(context, DEFAULT_SESSION_MILLIS) }
            ) {
                Text("Start 25-minute session")
            }
            OutlinedButton(
                enabled = session.isActive,
                onClick = { BurnoutOverlayService.stop(context) }
            ) {
                Text("End session")
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "The overlay is optional and dismissible. You can always use Android system controls.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private const val DEFAULT_SESSION_MILLIS = 25 * 60 * 1_000L
