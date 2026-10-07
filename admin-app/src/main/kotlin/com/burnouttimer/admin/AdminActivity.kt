package com.burnouttimer.admin

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.burnouttimer.policy.DevicePolicy
import com.burnouttimer.policy.PolicyQrCodec
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AdminScreen()
                }
            }
        }
    }
}

@Composable
private fun AdminScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scannedClientId by remember { mutableStateOf("") }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val clientId = result.contents.trim()
                .takeIf { it.startsWith("$CLIENT_QR_HEADER\n") }
                ?.substringAfter('\n')
            if (clientId != null && clientId.matches(Regex("[A-Za-z0-9_-]{8,64}"))) {
                scannedClientId = clientId
            } else {
                Toast.makeText(context, "El QR no es una invitación válida de cliente.", Toast.LENGTH_LONG).show()
            }
        }
    }
    var clientId by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("60") }
    var alertBeforeMinutes by remember { mutableStateOf("10") }
    var suspendedPackages by remember { mutableStateOf("") }
    var startMessage by remember { mutableStateOf(DevicePolicy.DEFAULT_START_MESSAGE) }
    var endMessage by remember { mutableStateOf(DevicePolicy.DEFAULT_END_MESSAGE) }
    var startsAt by remember { mutableLongStateOf(defaultStart()) }
    var kioskEnabled by remember { mutableStateOf(true) }
    var qrText by remember { mutableStateOf<String?>(null) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val adminKeyQr = remember { PolicyQrCodec.adminKeyQr() }
    val adminFingerprint = remember {
        PolicyQrCodec.fingerprint(PolicyQrCodec.parseAndPinAdminKey(adminKeyQr))
    }
    val currentClientId = clientId.ifBlank { scannedClientId }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Burnout Timer · Administración", style = MaterialTheme.typography.headlineMedium)
        Text(
            "La política se firma en este dispositivo y se transfiere por QR. No hay servidor ni control remoto."
        )
        Text("Huella de esta administración: $adminFingerprint")
        QrImage(adminKeyQr, contentDescription = "QR para vincular esta administración")
        Text("En el cliente, escanea primero este QR para confiar en esta clave de administración.")

        OutlinedButton(onClick = {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Escanea la invitación QR del dispositivo cliente")
                    .setBeepEnabled(false)
            )
        }) {
            Text("Vincular dispositivo cliente por QR")
        }
        OutlinedTextField(
            value = currentClientId,
            onValueChange = {
                clientId = it.filter { char -> char.isLetterOrDigit() || char == '-' || char == '_' }.take(64)
            },
            label = { Text("Identificador del cliente") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = duration,
            onValueChange = { duration = it.filter { char -> char in '0'..'9' }.take(4) },
            label = { Text("Duración del modo de enfoque (minutos, máximo 1440)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Control de sesiones", style = MaterialTheme.typography.titleLarge)
        Text("Inicio: ${formatDate(startsAt)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val calendar = Calendar.getInstance().apply { timeInMillis = startsAt }
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        startsAt = Calendar.getInstance().apply {
                            timeInMillis = startsAt
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                        }.timeInMillis
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            }) { Text("Fecha") }
            OutlinedButton(onClick = {
                val calendar = Calendar.getInstance().apply { timeInMillis = startsAt }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        startsAt = Calendar.getInstance().apply {
                            timeInMillis = startsAt
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            }) { Text("Hora") }
        }
        OutlinedTextField(
            value = suspendedPackages,
            onValueChange = { suspendedPackages = it.take(1_500) },
            label = { Text("Paquetes que se suspenderán (uno por línea)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Escribe identificadores Android como com.example.social. No incluyas Teléfono, Ajustes, System UI ni la app Cliente."
        )
        Text("Alertas y mensajes", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = alertBeforeMinutes,
            onValueChange = { alertBeforeMinutes = it.filter { char -> char in '0'..'9' }.take(2) },
            label = { Text("Aviso antes del inicio (minutos; 0 desactiva el aviso previo)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = startMessage,
            onValueChange = { startMessage = it.take(DevicePolicy.MAX_MESSAGE_LENGTH) },
            label = { Text("Mensaje al iniciar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = endMessage,
            onValueChange = { endMessage = it.take(DevicePolicy.MAX_MESSAGE_LENGTH) },
            label = { Text("Mensaje al finalizar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Las alertas y mensajes se muestran localmente en Cliente. No se envía confirmación a Administración sin conexión."
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bloquear el Cliente en kiosk durante el horario")
            Spacer(Modifier.weight(1f))
            Switch(checked = kioskEnabled, onCheckedChange = { kioskEnabled = it })
        }
        Text(
            "Programa el bloqueo y su salida automática. Kiosk requiere Device Owner; las llamadas de emergencia siguen disponibles. Android no puede encender un teléfono apagado. Cada política dura como máximo 24 horas."
        )
        Button(
            enabled = currentClientId.matches(Regex("[A-Za-z0-9_-]{8,64}")) &&
                duration.toIntOrNull()?.let { it in 1..DevicePolicy.MAX_DURATION_MINUTES } == true &&
                alertBeforeMinutes.toIntOrNull()?.let { it in 0..DevicePolicy.MAX_ALERT_LEAD_MINUTES } == true &&
                startsAt > System.currentTimeMillis(),
            onClick = {
                try {
                    val packages = suspendedPackages.lineSequence()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .toSet()
                    val policy = DevicePolicy(
                        clientId = currentClientId,
                        startsAtEpochMillis = startsAt,
                        durationMinutes = duration.toInt(),
                        suspendedPackages = packages,
                        enableKiosk = kioskEnabled,
                        alertBeforeStartMinutes = alertBeforeMinutes.toInt(),
                        startMessage = startMessage.trim(),
                        endMessage = endMessage.trim()
                    )
                    qrText = PolicyQrCodec.sign(policy)
                    validationMessage = null
                } catch (exception: IllegalArgumentException) {
                    validationMessage = exception.message ?: "La política no es válida."
                    qrText = null
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Firmar y mostrar política QR")
        }
        validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        qrText?.let { signedPolicy ->
            Text("Escanea esta política desde el cliente: ${signedPolicy.length} caracteres")
            QrImage(signedPolicy, contentDescription = "Política firmada para el dispositivo cliente")
            Text("La política incluye controles temporales, alertas y mensajes firmados. Cliente verifica la clave vinculada y el ID del dispositivo.")
        }
    }
}

@Composable
private fun QrImage(value: String, contentDescription: String) {
    val bitmap = remember(value) {
        try {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 720, 720)
            Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 720) {
                    for (y in 0 until 720) {
                        setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
            }
        } catch (_: WriterException) {
            null
        }
    }
    if (bitmap == null) {
        Text("El contenido excede la capacidad de un QR. Reduce la lista de paquetes.")
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxWidth().height(280.dp)
        )
    }
}

private fun defaultStart(): Long = Calendar.getInstance().apply {
    add(Calendar.MINUTE, 15)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatDate(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("es"))
        .format(Date(time))

private const val CLIENT_QR_HEADER = "BURNOUT-CLIENT-1"
