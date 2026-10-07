package com.burnouttimer.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePolicyTest {
    @Test
    fun acceptsFinitePolicyAndCalculatesEndTime() {
        val policy = createPolicy(durationMinutes = 30)

        assertEquals(1_800_001L, policy.endsAtEpochMillis)
    }

    @Test
    fun rejectsInvalidDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(durationMinutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(durationMinutes = 1_441)
        }
    }

    @Test
    fun rejectsMalformedOrExcessivePackageLists() {
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(suspendedPackages = setOf("com.example", "not a package"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(suspendedPackages = (1..21).map { "com.example.app$it" }.toSet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(suspendedPackages = setOf("com.android.phone"))
        }
    }

    @Test
    fun rejectsSessionEndTimeOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(startsAtEpochMillis = Long.MAX_VALUE - 1)
        }
    }

    @Test
    fun validatesKioskFlagThroughSignedModelValue() {
        assertTrue(createPolicy(enableKiosk = true).enableKiosk)
        assertFalse(createPolicy(enableKiosk = false).enableKiosk)
    }

    @Test
    fun rejectsInvalidAlertsAndMultilineMessages() {
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(alertBeforeStartMinutes = 61)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(startMessage = "Línea uno\nLínea dos")
        }
        assertThrows(IllegalArgumentException::class.java) {
            createPolicy(startMessage = "x".repeat(DevicePolicy.MAX_MESSAGE_LENGTH + 1))
        }
    }

    private fun createPolicy(
        startsAtEpochMillis: Long = 1L,
        durationMinutes: Int = 30,
        suspendedPackages: Set<String> = setOf("com.example.app"),
        enableKiosk: Boolean = true,
        alertBeforeStartMinutes: Int = 0,
        startMessage: String = DevicePolicy.DEFAULT_START_MESSAGE
    ) = DevicePolicy(
        clientId = "client_12345678",
        startsAtEpochMillis = startsAtEpochMillis,
        durationMinutes = durationMinutes,
        suspendedPackages = suspendedPackages,
        enableKiosk = enableKiosk,
        alertBeforeStartMinutes = alertBeforeStartMinutes,
        startMessage = startMessage
    )
}
