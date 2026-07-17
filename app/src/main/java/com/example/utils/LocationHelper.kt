package com.example.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {
  private var fusedClient: FusedLocationProviderClient? = null

  private fun getClient(context: Context): FusedLocationProviderClient {
    if (fusedClient == null) {
      fusedClient = LocationServices.getFusedLocationProviderClient(context)
    }
    return fusedClient!!
  }

  fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
  }

  fun getLastKnownLocation(context: Context): DeviceLocation? {
    if (!hasLocationPermission(context)) return null
    return try {
      val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
      val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
      var bestLocation: Location? = null
      for (provider in providers) {
        try {
          val loc = locationManager.getLastKnownLocation(provider) ?: continue
          if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
            bestLocation = loc
          }
        } catch (_: SecurityException) {}
      }
      bestLocation?.let {
        DeviceLocation(
          latitude = it.latitude,
          longitude = it.longitude,
          accuracy = it.accuracy,
          timestamp = it.time
        )
      }
    } catch (e: Exception) {
      null
    }
  }

  suspend fun getCurrentLocation(context: Context): DeviceLocation? {
    if (!hasLocationPermission(context)) return null
    return try {
      val client = getClient(context)
      val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
        .setWaitForAccurateLocation(true)
        .setMinUpdateIntervalMillis(1000)
        .build()

      suspendCancellableCoroutine { continuation ->
        val callback = object : com.google.android.gms.location.LocationCallback() {
          override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
            result.lastLocation?.let { loc ->
              continuation.resume(
                DeviceLocation(
                  latitude = loc.latitude,
                  longitude = loc.longitude,
                  accuracy = loc.accuracy,
                  timestamp = loc.time
                )
              )
            } ?: continuation.resume(null)
            client.removeLocationUpdates(this)
          }
        }
        try {
          client.requestLocationUpdates(request, callback, null)
          continuation.invokeOnCancellation {
            client.removeLocationUpdates(callback)
          }
        } catch (e: SecurityException) {
          continuation.resume(null)
        }
      }
    } catch (e: Exception) {
      getLastKnownLocation(context)
    }
  }

  fun getLocationUrl(location: DeviceLocation?): String {
    if (location == null) return ""
    return "https://www.google.com/maps?q=${location.latitude},${location.longitude}"
  }
}
