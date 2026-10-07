package com.burnouttimer.policy

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

class PolicyQrCodecTest {
    @Test
    fun signedPolicyVerifiesForPairedAdminAndTargetClient() {
        val adminKeyPair = createKeyPair()
        val policy = createPolicy()

        val qr = PolicyQrCodec.sign(policy, adminKeyPair)
        val trustedKey = PolicyQrCodec.parseAndPinAdminKey(
            "BURNOUT-ADMIN-KEY-1\n${java.util.Base64.getEncoder().encodeToString(adminKeyPair.public.encoded)}"
        )

        assertEquals(policy, PolicyQrCodec.verify(qr, trustedKey, policy.clientId))
    }

    @Test
    fun rejectsPolicySignedByDifferentAdmin() {
        val signingKeyPair = createKeyPair()
        val otherAdminKeyPair = createKeyPair()
        val qr = PolicyQrCodec.sign(createPolicy(), signingKeyPair)

        assertThrows(IllegalArgumentException::class.java) {
            PolicyQrCodec.verify(qr, otherAdminKeyPair.public.encoded, "client_12345678")
        }
    }

    @Test
    fun rejectsPolicyForDifferentClient() {
        val adminKeyPair = createKeyPair()
        val policy = createPolicy()
        val qr = PolicyQrCodec.sign(policy, adminKeyPair)

        assertThrows(IllegalArgumentException::class.java) {
            PolicyQrCodec.verify(qr, adminKeyPair.public.encoded, "another_client_123")
        }
    }

    @Test
    fun rejectsModifiedSignature() {
        val adminKeyPair = createKeyPair()
        val policy = createPolicy()
        val fields = PolicyQrCodec.sign(policy, adminKeyPair).split('\n').toMutableList()
        val signature = fields[3].toCharArray()
        signature[8] = if (signature[8] == 'A') 'B' else 'A'
        fields[3] = String(signature)

        assertThrows(IllegalArgumentException::class.java) {
            PolicyQrCodec.verify(fields.joinToString("\n"), adminKeyPair.public.encoded, policy.clientId)
        }
    }

    @Test
    fun acceptsPreviouslyStoredPolicyPayloadsAndUsesSafeDefaults() {
        val adminKeyPair = createKeyPair()
        val payload = listOf(
            "legacy_policy_123",
            "client_12345678",
            "1800000",
            "30",
            "1",
            "com.example.app"
        ).joinToString("\n")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(adminKeyPair.private)
            update(payload.toByteArray(Charsets.UTF_8))
            sign()
        }
        val qr = listOf(
            "BURNOUT-POLICY-1",
            java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8)),
            java.util.Base64.getEncoder().encodeToString(adminKeyPair.public.encoded),
            java.util.Base64.getEncoder().encodeToString(signature)
        ).joinToString("\n")

        val policy = PolicyQrCodec.verify(qr, adminKeyPair.public.encoded, "client_12345678")

        assertEquals(0, policy.alertBeforeStartMinutes)
        assertEquals(DevicePolicy.DEFAULT_START_MESSAGE, policy.startMessage)
        assertEquals(DevicePolicy.DEFAULT_END_MESSAGE, policy.endMessage)
    }

    @Test
    fun maximumSupportedPolicyFitsInOneQrCode() {
        val keyPair = createKeyPair()
        val packages = (1..6).map { index ->
            "com.${"a".repeat(110)}$index"
        }.toSet()
        val policy = DevicePolicy(
            clientId = "client_12345678",
            startsAtEpochMillis = 1_800_000L,
            durationMinutes = 30,
            suspendedPackages = packages,
            enableKiosk = true,
            alertBeforeStartMinutes = 60,
            startMessage = "s".repeat(DevicePolicy.MAX_MESSAGE_LENGTH),
            endMessage = "e".repeat(DevicePolicy.MAX_MESSAGE_LENGTH)
        )

        val qr = PolicyQrCodec.sign(policy, keyPair)
        assertTrue(qr.length <= PolicyQrCodec.MAX_QR_LENGTH)
        MultiFormatWriter().encode(qr, BarcodeFormat.QR_CODE, 720, 720)
    }

    private fun createKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun createPolicy() = DevicePolicy(
        clientId = "client_12345678",
        startsAtEpochMillis = 1_800_000L,
        durationMinutes = 30,
        suspendedPackages = setOf("com.example.app", "com.example.social"),
        enableKiosk = true,
        alertBeforeStartMinutes = 10,
        startMessage = "Comienza la rutina.",
        endMessage = "Terminó la rutina. Ya puedes salir."
    )
}
