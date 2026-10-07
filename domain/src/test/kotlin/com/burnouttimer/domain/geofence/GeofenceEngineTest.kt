package com.burnouttimer.domain.geofence

import com.burnouttimer.domain.model.SafeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceEngineTest {
    @Test
    fun identicalCoordinatesHaveZeroDistance() {
        assertEquals(0.0, GeofenceEngine.distanceMeters(0.0, 0.0, 0.0, 0.0), 0.001)
    }

    @Test
    fun oneDegreeAtTheEquatorIsApproximately111Kilometers() {
        assertEquals(111_195.0, GeofenceEngine.distanceMeters(0.0, 0.0, 0.0, 1.0), 10.0)
    }

    @Test
    fun antipodalCoordinatesHaveFiniteDistance() {
        assertEquals(
            Math.PI * 6_371_000.0,
            GeofenceEngine.distanceMeters(0.0, 0.0, 0.0, 180.0),
            0.001
        )
    }

    @Test
    fun zoneBoundaryIsInsideAndPointsBeyondItAreOutside() {
        val zone = SafeZone(latitude = 0.0, longitude = 0.0, radiusMeters = 100.0)
        assertFalse(GeofenceEngine.isOutside(zone, 0.0, 0.0))
        assertTrue(GeofenceEngine.isOutside(zone, 0.0, 0.002))
    }
}
