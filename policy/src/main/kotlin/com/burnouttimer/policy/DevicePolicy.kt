package com.burnouttimer.policy

import java.util.UUID

data class DevicePolicy(
    val policyId: String = UUID.randomUUID().toString(),
    val clientId: String,
    val startsAtEpochMillis: Long,
    val durationMinutes: Int,
    val suspendedPackages: Set<String>,
    val enableKiosk: Boolean,
    val alertBeforeStartMinutes: Int = 0,
    val startMessage: String = DEFAULT_START_MESSAGE,
    val endMessage: String = DEFAULT_END_MESSAGE
) {
    init {
        require(policyId.length <= 64 && policyId.matches(IDENTIFIER_PATTERN))
        require(clientId.length <= 64 && clientId.matches(IDENTIFIER_PATTERN))
        require(startsAtEpochMillis > 0L)
        require(durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES)
        require(startsAtEpochMillis <= Long.MAX_VALUE - durationMinutes * 60_000L)
        require(suspendedPackages.size <= MAX_SUSPENDED_PACKAGES)
        require(suspendedPackages.all { it.length <= MAX_PACKAGE_NAME_LENGTH && PACKAGE_PATTERN.matches(it) })
        require(suspendedPackages.sumOf(String::length) + suspendedPackages.size <= MAX_TOTAL_PACKAGE_CHARS)
        require(suspendedPackages.none(PROTECTED_PACKAGES::contains))
        require(alertBeforeStartMinutes in 0..MAX_ALERT_LEAD_MINUTES)
        require(startMessage.length <= MAX_MESSAGE_LENGTH && MESSAGE_PATTERN.matches(startMessage))
        require(endMessage.length <= MAX_MESSAGE_LENGTH && MESSAGE_PATTERN.matches(endMessage))
    }

    val endsAtEpochMillis: Long
        get() = startsAtEpochMillis + durationMinutes * 60_000L

    companion object {
        const val MIN_DURATION_MINUTES = 1
        const val MAX_DURATION_MINUTES = 24 * 60
        const val MAX_SUSPENDED_PACKAGES = 20
        const val MAX_PACKAGE_NAME_LENGTH = 128
        const val MAX_TOTAL_PACKAGE_CHARS = 900
        const val MAX_ALERT_LEAD_MINUTES = 60
        const val MAX_MESSAGE_LENGTH = 100
        const val DEFAULT_START_MESSAGE = "Tu sesión programada comienza ahora."
        const val DEFAULT_END_MESSAGE = "La sesión terminó. Las aplicaciones están disponibles."
        val PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.android.incallui",
            "com.android.emergency",
            "com.android.providers.telephony",
            "com.android.cellbroadcastreceiver"
        )
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val MESSAGE_PATTERN = Regex("[^\\r\\n\\u0000-\\u001F]*")
    }
}
