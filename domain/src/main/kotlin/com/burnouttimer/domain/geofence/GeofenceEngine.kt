package com.burnouttimer.domain.geofence

import com.burnouttimer.domain.model.SafeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeofenceEngine {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        require(latitude1 in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(latitude2 in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(longitude1 in -180.0..180.0) { "Longitude must be between -180 and 180." }
        require(longitude2 in -180.0..180.0) { "Longitude must be between -180 and 180." }

        val lat1 = Math.toRadians(latitude1)
        val lat2 = Math.toRadians(latitude2)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(longitude2 - longitude1)
        val a = (sin(deltaLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).let { it * it }
            ).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    fun isOutside(zone: SafeZone, latitude: Double, longitude: Double): Boolean =
        distanceMeters(zone.latitude, zone.longitude, latitude, longitude) > zone.radiusMeters
}
