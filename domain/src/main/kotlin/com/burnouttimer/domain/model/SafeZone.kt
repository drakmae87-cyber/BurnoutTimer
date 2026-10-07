package com.burnouttimer.domain.model

data class SafeZone(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180." }
        require(radiusMeters > 0.0 && radiusMeters.isFinite()) { "Radius must be positive and finite." }
    }
}
