package com.burnouttimer

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.database.sqlite.SQLiteException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnouttimer.data.local.ScheduledSessionDao
import com.burnouttimer.data.local.ScheduledSessionEntity
import com.burnouttimer.domain.model.ScheduledSession
import com.burnouttimer.domain.model.SessionState
import com.burnouttimer.domain.repository.SessionPreferences
import com.burnouttimer.services.BurnoutOverlayService
import com.burnouttimer.client.policy.ClientPolicyActivity
import com.burnouttimer.client.policy.ClientPolicyManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val exactAlarmsAllowed = mutableStateOf(false)
    private val managedKioskActive = mutableStateOf(false)
    private val latestPolicyMessage = mutableStateOf<String?>(null)
    private val policyEndTime = mutableLongStateOf(0L)
    private val policyError = mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Sin notificaciones, los recordatorios programados no podrán avisarte.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshSystemState()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FocusTimerScreen(
                        exactAlarmsAllowed = exactAlarmsAllowed.value,
                        managedKioskActive = managedKioskActive.value,
                        latestPolicyMessage = latestPolicyMessage.value,
                        policyEndTime = policyEndTime.longValue,
                        policyError = policyError.value,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val policyManager = ClientPolicyManager(this)
        try {
            policyManager.restoreAfterBoot()
        } catch (exception: SecurityException) {
            policyManager.recordFailure(exception.message ?: "Android rechazó restaurar el control programado.")
        } catch (exception: IllegalStateException) {
            policyManager.recordFailure(exception.message ?: "No se pudo restaurar el control programado.")
        }
        refreshSystemState()
        startManagedKioskIfNeeded()
    }

    private fun refreshSystemState() {
        exactAlarmsAllowed.value = exactAlarmsAvailable(this)
        val policyManager = ClientPolicyManager(this)
        managedKioskActive.value = policyManager.isManagedKioskActive()
        latestPolicyMessage.value = policyManager.currentMessage()
        policyEndTime.longValue = policyManager.activePolicyWindow()?.second ?: 0L
        policyError.value = policyManager.lastError()
    }

    private fun startManagedKioskIfNeeded() {
        val policyManager = ClientPolicyManager(this)
        if (!policyManager.isManagedKioskActive()) return
        val state = getSystemService(android.app.ActivityManager::class.java)?.lockTaskModeState
        if (state == android.app.ActivityManager.LOCK_TASK_MODE_LOCKED) return
        try {
            startLockTask()
        } catch (exception: SecurityException) {
            policyManager.recordFailure(exception.message ?: "Android no pudo iniciar el modo kiosk.")
            refreshSystemState()
        } catch (exception: IllegalStateException) {
            policyManager.recordFailure(exception.message ?: "Android no pudo iniciar el modo kiosk.")
            refreshSystemState()
        }
    }
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val preferences: SessionPreferences,
    private val scheduledSessionDao: ScheduledSessionDao,
    private val scheduledSessionAlarms: ScheduledSessionAlarms
) : ViewModel() {
    val session = preferences.sessionState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SessionState()
    )
    val scheduledSessions = scheduledSessionDao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    fun scheduleSession(session: ScheduledSession, onResult: (ScheduleResult) -> Unit) {
        viewModelScope.launch {
            val sessionId = try {
                withContext(Dispatchers.IO) {
                    scheduledSessionDao.insertIfNoOverlap(
                        ScheduledSessionEntity(
                            title = session.title,
                            startsAtEpochMillis = session.startsAtEpochMillis,
                            durationMinutes = session.durationMinutes
                        )
                    )
                }
            } catch (exception: SQLiteException) {
                Log.e(TAG, "No se pudo guardar el evento programado.", exception)
                onResult(ScheduleResult.REMINDER_FAILED)
                return@launch
            }

            if (sessionId <= 0L) {
                onResult(ScheduleResult.OVERLAP)
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    scheduledSessionAlarms.schedule(sessionId, session.startsAtEpochMillis)
                }
            } catch (exception: SecurityException) {
                Log.e(TAG, "Android no permitió programar el recordatorio.", exception)
                withContext(Dispatchers.IO) { scheduledSessionDao.delete(sessionId) }
                onResult(ScheduleResult.REMINDER_FAILED)
                return@launch
            }
            onResult(ScheduleResult.SCHEDULED)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            scheduledSessionAlarms.cancel(sessionId)
            scheduledSessionDao.delete(sessionId)
        }
    }

    private companion object {
        const val TAG = "BurnoutTimerSchedule"
    }
}

@Composable
private fun FocusTimerScreen(
    exactAlarmsAllowed: Boolean,
    managedKioskActive: Boolean,
    latestPolicyMessage: String?,
    policyEndTime: Long,
    policyError: String?,
    onRequestNotificationPermission: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val session by viewModel.session.collectAsState()
    val scheduledSessions by viewModel.scheduledSessions.collectAsState()
    val spanish = true
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var durationText by remember { mutableStateOf("25") }
    var eventTitle by remember { mutableStateOf("") }
    var eventDurationText by remember { mutableStateOf("25") }
    var eventStartsAt by remember { mutableLongStateOf(defaultScheduledTime()) }
    var showOverlapMessage by remember { mutableStateOf(false) }

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
        PackageManager.PERMISSION_GRANTED
    val durationMinutes = durationText.toIntOrNull()
    val eventDurationMinutes = eventDurationText.toIntOrNull()

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Burnout Timer",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text("Idioma: Español", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Calendario, eventos y temporizador. El control programado se administra localmente mediante QR."
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (spanish) "Temporizador" else "Focus timer",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            if (session.isActive) {
                                if (spanish) "Sesión activa: $remainingMinutes min restantes"
                                else "Session active: $remainingMinutes min remaining"
                            } else {
                                if (spanish) "Elige una duración de 1 a 1.440 minutos."
                                else "Choose a duration from 1 to 1,440 minutes."
                            }
                        )
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { durationText = it.filter { char -> char in '0'..'9' }.take(4) },
                            label = { Text(if (spanish) "Duración (minutos)" else "Duration (minutes)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !session.isActive,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = !session.isActive &&
                                    durationMinutes != null &&
                                    durationMinutes in 1..1_440,
                                onClick = {
                                    BurnoutOverlayService.start(
                                        context,
                                        durationMinutes!!.toLong() * 60_000L
                                    )
                                }
                            ) {
                                Text(if (spanish) "Iniciar" else "Start")
                            }
                            OutlinedButton(
                                enabled = session.isActive,
                                onClick = { BurnoutOverlayService.stop(context) }
                            ) {
                                Text(if (spanish) "Finalizar" else "End")
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Calendario y eventos", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = eventTitle,
                            onValueChange = { eventTitle = it.take(60) },
                            label = { Text("Nombre del evento") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = eventDurationText,
                            onValueChange = {
                                eventDurationText = it.filter { char -> char in '0'..'9' }.take(4)
                            },
                            label = { Text("Duración (minutos)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            formatEventDate(eventStartsAt, spanish),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                showDatePicker(context, eventStartsAt) { updated ->
                                    eventStartsAt = updated
                                }
                            }) {
                                Text(                                "Elegir fecha")
                            }
                            OutlinedButton(onClick = {
                                showTimePicker(context, eventStartsAt) { updated ->
                                    eventStartsAt = updated
                                }
                            }) {
                                Text(                                "Elegir hora")
                            }
                        }
                        Button(
                            enabled = eventTitle.isNotBlank() &&
                                eventStartsAt > System.currentTimeMillis() &&
                                eventDurationMinutes != null &&
                                eventDurationMinutes in 1..1_440,
                            onClick = {
                                viewModel.scheduleSession(
                                    ScheduledSession(
                                        title = eventTitle.trim(),
                                        startsAtEpochMillis = eventStartsAt,
                                        durationMinutes = eventDurationMinutes!!
                                    )
                                ) { result ->
                                    showOverlapMessage = result == ScheduleResult.OVERLAP
                                    if (result == ScheduleResult.SCHEDULED) {
                                        Toast.makeText(
                                            context,
                                            "Recordatorio programado.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        eventTitle = ""
                                        eventStartsAt = defaultScheduledTime()
                                    } else if (result == ScheduleResult.REMINDER_FAILED) {
                                        Toast.makeText(
                                            context,
                                            "No se pudo guardar el evento o programar su aviso.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        ) {
                            Text("Programar evento")
                        }
                        if (showOverlapMessage) {
                            Text(
                                if (spanish) {
                                    "Ese horario se solapa con otra sesión. Elige una hora diferente."
                                } else {
                                    "That time overlaps another session. Choose a different time."
                                },
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (!exactAlarmsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Text(
                                "Android puede retrasar estos avisos. Permite alarmas exactas para respetar el horario.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }) {
                                Text("Permitir alarmas exactas")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Próximos eventos",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (scheduledSessions.none { it.startsAtEpochMillis > currentTime }) {
                item {
                    Text("No hay eventos próximos.")
                }
            } else {
                items(
                    scheduledSessions.filter { it.startsAtEpochMillis > currentTime },
                    key = { it.id }
                ) { event ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(event.title, style = MaterialTheme.typography.titleMedium)
                                Text(formatEventDate(event.startsAtEpochMillis, spanish))
                                Text(
                                    "Duración: ${event.durationMinutes} min"
                                )
                            }
                            OutlinedButton(onClick = { viewModel.deleteSession(event.id) }) {
                                Text("Eliminar")
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Control programado", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (managedKioskActive) {
                                "Kiosk activo. El acceso se restaurará: ${formatEventDate(policyEndTime, true)}."
                            } else {
                                "Vincula Administración y configura el inicio y fin del modo kiosk."
                            }
                        )
                        latestPolicyMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        policyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!notificationPermissionGranted) {
                            OutlinedButton(onClick = onRequestNotificationPermission) {
                                Text("Permitir alertas y mensajes")
                            }
                        }
                        if (!managedKioskActive) {
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(context, ClientPolicyActivity::class.java))
                            }) {
                                Text("Vincular Administración / importar política QR")
                            }
                        }
                        Text(
                            "El horario programa el bloqueo y su salida automática; no apaga eléctricamente el teléfono ni puede encenderlo si está apagado."
                        )
                    }
                }
            }
        }
    }
}

private fun defaultScheduledTime(): Long =
    Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 1)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun formatEventDate(epochMillis: Long, spanish: Boolean): String {
    val locale = if (spanish) Locale.forLanguageTag("es") else Locale.ENGLISH
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        .format(Date(epochMillis))
}

private fun showDatePicker(
    context: android.content.Context,
    selectedMillis: Long,
    onSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = selectedMillis }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            val selected = Calendar.getInstance().apply {
                timeInMillis = selectedMillis
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
            }
            onSelected(selected.timeInMillis)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun showTimePicker(
    context: android.content.Context,
    selectedMillis: Long,
    onSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = selectedMillis }
    TimePickerDialog(
        context,
        { _, hour, minute ->
            val selected = Calendar.getInstance().apply {
                timeInMillis = selectedMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onSelected(selected.timeInMillis)
        },
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        true
    ).show()
}

private fun exactAlarmsAvailable(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
