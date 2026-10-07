package com.burnouttimer.client.policy

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.burnouttimer.policy.PolicyQrCodec
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class ClientPolicyActivity : ComponentActivity() {
    private val refreshVersion = mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ClientPolicyScreen(
                        context = this,
                        manager = remember { ClientPolicyManager(applicationContext) },
                        refreshVersion = refreshVersion.intValue,
                        requestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        refreshVersion.intValue++
    }
}

@Composable
private fun ClientPolicyScreen(
    context: Activity,
    manager: ClientPolicyManager,
    refreshVersion: Int,
    requestNotificationPermission: () -> Unit
) {
    var hasTrustedAdmin by remember { mutableStateOf(manager.hasTrustedAdmin()) }
    var isDeviceOwner by remember { mutableStateOf(manager.isDeviceOwner()) }
    var exactAlarmsAllowed by remember { mutableStateOf(manager.isExactAlarmAllowed()) }
    var notificationsAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var lastError by remember { mutableStateOf(manager.lastError()) }
    var message by remember { mutableStateOf<String?>(null) }
    var scanPurpose by remember { mutableStateOf(ScanPurpose.TRUST_ADMIN) }
    val kioskActive = manager.isManagedKioskActive()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val qrText = result.contents ?: return@rememberLauncherForActivityResult
        try {
            when (scanPurpose) {
                ScanPurpose.TRUST_ADMIN -> {
                    manager.trustAdmin(qrText)
                    hasTrustedAdmin = true
                    message = "Clave de administración vinculada."
                }
                ScanPurpose.IMPORT_POLICY -> {
                    message = manager.importPolicy(qrText)
                    lastError = null
                }
            }
        } catch (exception: IllegalArgumentException) {
            message = exception.message ?: "El QR no tiene un formato válido."
        } catch (exception: IllegalStateException) {
            message = exception.message ?: "No se pudo procesar el QR."
        } catch (exception: SecurityException) {
            message = exception.message ?: "Android no permitió programar la política."
        }
    }

    androidx.compose.runtime.LaunchedEffect(refreshVersion) {
        hasTrustedAdmin = manager.hasTrustedAdmin()
        isDeviceOwner = manager.isDeviceOwner()
        exactAlarmsAllowed = manager.isExactAlarmAllowed()
        notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        lastError = manager.lastError()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Control local del dispositivo", style = MaterialTheme.typography.headlineMedium)
        Text("Este cliente no se conecta a servidores. Las políticas se verifican y transfieren localmente por QR.")
        manager.currentMessage()?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text("ID de este dispositivo: ${manager.clientId}")
        QrImage(
            value = "$CLIENT_QR_HEADER\n${manager.clientId}",
            contentDescription = "Invitación QR de este cliente"
        )
        Text("En el teléfono de administración, escanea este QR para vincular el cliente.")
        Text(
            if (kioskActive) {
                "Kiosk temporal activo. El horario termina automáticamente y restaura el acceso."
            } else if (isDeviceOwner) {
                "Estado kiosk: administrado por este dispositivo (Device Owner)."
            } else {
                "Estado kiosk: no aprovisionado. No se suspenderán apps ni se activará el modo kiosk."
            },
            color = if (isDeviceOwner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        manager.activePolicyWindow()?.let { (_, endsAt) ->
            Text("Salida automática programada: ${formatClientDate(endsAt)}")
        }
        if (!kioskActive) {
            Text(
                if (hasTrustedAdmin) "Clave de administración vinculada." else "Falta vincular una clave de administración."
            )
            OutlinedButton(onClick = {
                scanPurpose = ScanPurpose.TRUST_ADMIN
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Escanea el QR de clave del dispositivo de administración")
                        .setBeepEnabled(false)
                )
            }) {
                Text(if (hasTrustedAdmin) "Verificar clave de administración" else "Vincular Administración por QR")
            }
        }
        if (!notificationsAllowed) {
            Text("Activa notificaciones para recibir alertas y mensajes de Administración.")
            OutlinedButton(onClick = requestNotificationPermission) {
                Text("Permitir alertas y mensajes")
            }
        }
        if (!exactAlarmsAllowed) {
            Text(
                "Se requiere el permiso de alarmas exactas para iniciar y terminar la política a la hora prevista."
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }) {
                Text("Permitir alarmas exactas en Ajustes")
            }
        }
        if (!kioskActive) {
            Button(
                enabled = hasTrustedAdmin && isDeviceOwner && exactAlarmsAllowed,
                onClick = {
                    scanPurpose = ScanPurpose.IMPORT_POLICY
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Escanea la política firmada que muestra Administración")
                            .setBeepEnabled(false)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Escanear e importar política")
            }
        }
        message?.let { Text(it) }
        lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        if (!kioskActive) {
            Text(
                "El modo kiosk requiere que un administrador aprovisione previamente este dispositivo como Device Owner. "
                    + "El aprovisionamiento es local y requiere acceso físico o ADB durante la configuración inicial."
            )
            OutlinedButton(onClick = { context.finish() }) {
                Text("Volver")
            }
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
        Text("No se pudo generar el código QR.")
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxWidth().height(280.dp)
        )
    }
}

private enum class ScanPurpose {
    TRUST_ADMIN,
    IMPORT_POLICY
}

private fun formatClientDate(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
        java.util.Locale.forLanguageTag("es")
    ).format(java.util.Date(epochMillis))

private const val CLIENT_QR_HEADER = "BURNOUT-CLIENT-1"
