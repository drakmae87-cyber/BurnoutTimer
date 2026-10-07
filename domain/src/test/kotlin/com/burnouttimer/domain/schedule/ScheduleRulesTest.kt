package com.burnouttimer.domain.schedule

import com.burnouttimer.domain.model.ScheduledSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRulesTest {
    private val first = ScheduledSession(
        title = "Study",
        startsAtEpochMillis = 1_000_000L,
        durationMinutes = 30
    )

    @Test
    fun rejectsOverlappingSessions() {
        val proposed = ScheduledSession(
            title = "Break",
            startsAtEpochMillis = 1_000_000L + 29 * 60_000L,
            durationMinutes = 10
        )

        assertTrue(ScheduleRules.hasOverlap(proposed, listOf(first)))
    }

    @Test
    fun allowsSessionsThatMeetAtTheBoundary() {
        val proposed = ScheduledSession(
            title = "Break",
            startsAtEpochMillis = 1_000_000L + 30 * 60_000L,
            durationMinutes = 10
        )

        assertFalse(ScheduleRules.hasOverlap(proposed, listOf(first)))
    }
}
