package com.burnouttimer.policy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

object PolicyQrCodec {
    private const val POLICY_HEADER = "BURNOUT-POLICY-1"
    private const val ADMIN_KEY_HEADER = "BURNOUT-ADMIN-KEY-1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val KEY_ALIAS = "burnout_timer_parent_admin_signing_key"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    const val MAX_QR_LENGTH = 2_200
    private const val LEGACY_FIELD_COUNT = 6
    private const val CURRENT_FIELD_COUNT = 9

    fun adminKeyQr(): String = "$ADMIN_KEY_HEADER\n${encode(getOrCreateAdminKeyPair().public.encoded)}"

    fun parseAndPinAdminKey(qrText: String): ByteArray {
        require(qrText.length <= MAX_QR_LENGTH) { "Admin QR is too large." }
        val fields = qrText.trim().split('\n')
        require(fields.size == 2 && fields[0] == ADMIN_KEY_HEADER) { "This is not an admin pairing QR." }
        val publicKey = decode(fields[1])
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        return publicKey
    }

    fun sign(policy: DevicePolicy): String {
        return sign(policy, getOrCreateAdminKeyPair())
    }

    internal fun sign(policy: DevicePolicy, keyPair: KeyPair): String {
        val payload = canonicalPayload(policy)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(payload.toByteArray(Charsets.UTF_8))
            sign()
        }
        val qr = listOf(
            POLICY_HEADER,
            encode(payload.toByteArray(Charsets.UTF_8)),
            encode(keyPair.public.encoded),
            encode(signature)
        ).joinToString("\n")
        require(qr.length <= MAX_QR_LENGTH) {
            "La política supera el tamaño seguro del QR. Reduce la lista de apps o acorta los mensajes."
        }
        return qr
    }

    fun verify(qrText: String, trustedAdminPublicKey: ByteArray, expectedClientId: String): DevicePolicy {
        require(qrText.length <= MAX_QR_LENGTH) { "Policy QR is too large." }
        val fields = qrText.trim().split('\n')
        require(fields.size == 4 && fields[0] == POLICY_HEADER) { "This is not a signed policy QR." }
        val embeddedPublicKey = decode(fields[2])
        require(embeddedPublicKey.contentEquals(trustedAdminPublicKey)) {
            "Policy was not signed by the paired admin."
        }
        val payload = decode(fields[1]).toString(Charsets.UTF_8)
        val signatureIsValid = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(trustedAdminPublicKey)))
            update(payload.toByteArray(Charsets.UTF_8))
            verify(decode(fields[3]))
        }
        require(signatureIsValid) { "Policy signature is invalid." }
        val policy = parsePayload(payload)
        require(policy.clientId == expectedClientId) { "Policy belongs to another client device." }
        return policy
    }

    fun fingerprint(publicKey: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
            .take(8)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString("-")

    private fun getOrCreateAdminKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
        if (privateKey != null && publicKey != null) return KeyPair(publicKey, privateKey)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun canonicalPayload(policy: DevicePolicy): String = listOf(
        policy.policyId,
        policy.clientId,
        policy.startsAtEpochMillis.toString(),
        policy.durationMinutes.toString(),
        if (policy.enableKiosk) "1" else "0",
        policy.suspendedPackages.sorted().joinToString(","),
        policy.alertBeforeStartMinutes.toString(),
        policy.startMessage,
        policy.endMessage
    ).joinToString("\n")

    private fun parsePayload(payload: String): DevicePolicy {
        val fields = payload.split('\n')
        require(fields.size == LEGACY_FIELD_COUNT || fields.size == CURRENT_FIELD_COUNT) {
            "Policy payload is malformed."
        }
        return DevicePolicy(
            policyId = fields[0],
            clientId = fields[1],
            startsAtEpochMillis = fields[2].toLong(),
            durationMinutes = fields[3].toInt(),
            enableKiosk = when (fields[4]) {
                "0" -> false
                "1" -> true
                else -> error("Kiosk flag is malformed.")
            },
            suspendedPackages = fields[5].takeIf(String::isNotEmpty)
                ?.split(',')
                ?.toSet()
                ?: emptySet(),
            alertBeforeStartMinutes = fields.getOrNull(6)?.toInt()
                ?: 0,
            startMessage = fields.getOrNull(7)
                ?: DevicePolicy.DEFAULT_START_MESSAGE,
            endMessage = fields.getOrNull(8)
                ?: DevicePolicy.DEFAULT_END_MESSAGE
        )
    }

    private fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)
}
