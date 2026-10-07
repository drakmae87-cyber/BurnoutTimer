package com.burnouttimer.services

import com.burnouttimer.domain.geofence.GeofenceEngine
import com.burnouttimer.domain.model.SafeZone
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

class NativeGeofenceEngine @Inject constructor(
    private val locationProvider: LocationProviderManager
) {
    fun outOfBoundsAlerts(zone: SafeZone): Flow<Boolean> =
        locationProvider.locations()
            .mapNotNull { location ->
                if (!location.hasAccuracy() || location.accuracy > zone.radiusMeters) {
                    null
                } else {
                    GeofenceEngine.isOutside(zone, location.latitude, location.longitude)
                }
            }
            .distinctUntilChanged()
            .filter { it }
}
