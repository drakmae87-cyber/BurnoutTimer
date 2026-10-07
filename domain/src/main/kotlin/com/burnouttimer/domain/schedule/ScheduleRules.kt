package com.burnouttimer.domain.schedule

import com.burnouttimer.domain.model.ScheduledSession

object ScheduleRules {
    fun hasOverlap(proposed: ScheduledSession, existing: List<ScheduledSession>): Boolean {
        val proposedEnd = endTime(proposed)
        return existing.any { current ->
            proposed.startsAtEpochMillis < endTime(current) &&
                current.startsAtEpochMillis < proposedEnd
        }
    }

    private fun endTime(session: ScheduledSession): Long =
        session.startsAtEpochMillis + session.durationMinutes * 60_000L
}
