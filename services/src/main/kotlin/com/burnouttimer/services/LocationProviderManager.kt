package com.burnouttimer.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationProviderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationManager: LocationManager
) {
    fun locations(): Flow<Location> = callbackFlow {
        val hasFinePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFinePermission && !hasCoarsePermission) {
            close(SecurityException("Location permission has not been granted by the user."))
            return@callbackFlow
        }

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                close(IllegalStateException("No enabled location provider is available."))
                return@callbackFlow
            }
        }
        val listener = LocationListener { location -> trySend(location) }
        try {
            locationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MILLIS, MIN_DISTANCE_METERS, listener, Looper.getMainLooper())
            locationManager.getLastKnownLocation(provider)?.let { trySend(it) }
        } catch (exception: SecurityException) {
            close(exception)
            return@callbackFlow
        } catch (exception: IllegalArgumentException) {
            close(exception)
            return@callbackFlow
        }
        awaitClose { locationManager.removeUpdates(listener) }
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 10_000L
        const val MIN_DISTANCE_METERS = 5.0f
    }
}
