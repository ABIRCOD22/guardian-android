package com.example.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.resume

object LocationHelper {
  private const val TAG = "LocationHelper"
  private var fusedClient: FusedLocationProviderClient? = null

  private var periodicJob: Job? = null
  private val _latestLocation = MutableStateFlow<DeviceLocation?>(null)
  val latestLocation: StateFlow<DeviceLocation?> = _latestLocation

  private fun getClient(context: Context): FusedLocationProviderClient {
    if (fusedClient == null) {
      fusedClient = LocationServices.getFusedLocationProviderClient(context)
      Logger.d(TAG, "FusedLocationProviderClient initialized")
    }
    return fusedClient!!
  }

  fun hasLocationPermission(context: Context): Boolean {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    Logger.d(TAG, "hasLocationPermission=$granted")
    return granted
  }

  fun getLastKnownLocation(context: Context): DeviceLocation? {
    if (!hasLocationPermission(context)) {
      Logger.w(TAG, "getLastKnownLocation — no location permission")
      return null
    }
    Logger.d(TAG, "getLastKnownLocation — querying providers")
    return try {
      val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
        Logger.w(TAG, "getLastKnownLocation — LocationManager is null")
        return null
      }
      val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
      var bestLocation: Location? = null
      for (provider in providers) {
        try {
          val loc = locationManager.getLastKnownLocation(provider)
          if (loc != null) {
            Logger.d(TAG, "Provider $provider returned lat=${loc.latitude} lng=${loc.longitude} accuracy=${loc.accuracy}")
            if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
              bestLocation = loc
            }
          }
        } catch (e: SecurityException) {
          Logger.e(TAG, "SecurityException accessing provider $provider", e)
        }
      }
      if (bestLocation != null) {
        Logger.i(TAG, "getLastKnownLocation result: lat=${bestLocation.latitude} lng=${bestLocation.longitude}")
        DeviceLocation(
          latitude = bestLocation.latitude,
          longitude = bestLocation.longitude,
          accuracy = bestLocation.accuracy,
          timestamp = bestLocation.time
        )
      } else {
        Logger.w(TAG, "getLastKnownLocation — no location from any provider")
        null
      }
    } catch (e: Exception) {
      Logger.e(TAG, "getLastKnownLocation failed", e)
      null
    }
  }

  suspend fun getCurrentLocation(context: Context): DeviceLocation? {
    if (!hasLocationPermission(context)) {
      Logger.w(TAG, "getCurrentLocation — no location permission")
      return null
    }
    Logger.i(TAG, "getCurrentLocation — requesting fused location (HIGH_ACCURACY)")
    return try {
      val client = getClient(context)
      val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
        .setWaitForAccurateLocation(true)
        .setMinUpdateIntervalMillis(1000)
        .build()

      suspendCancellableCoroutine { continuation ->
        val callback = object : LocationCallback() {
          override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
              Logger.i(TAG, "getCurrentLocation received: lat=${loc.latitude} lng=${loc.longitude} accuracy=${loc.accuracy}")
              continuation.resume(
                DeviceLocation(
                  latitude = loc.latitude,
                  longitude = loc.longitude,
                  accuracy = loc.accuracy,
                  timestamp = loc.time
                )
              )
            } ?: run {
              Logger.w(TAG, "getCurrentLocation — result had no lastLocation")
              continuation.resume(null)
            }
            client.removeLocationUpdates(this)
          }
        }
        try {
          client.requestLocationUpdates(request, callback, null)
          continuation.invokeOnCancellation {
            Logger.d(TAG, "getCurrentLocation continuation cancelled — removing updates")
            client.removeLocationUpdates(callback)
          }
        } catch (e: SecurityException) {
          Logger.e(TAG, "SecurityException requesting location updates", e)
          continuation.resume(null)
        }
      }
    } catch (e: Exception) {
      Logger.e(TAG, "getCurrentLocation failed — falling back to getLastKnownLocation", e)
      getLastKnownLocation(context)
    }
  }

  fun isGpsEnabled(context: Context): Boolean {
    return try {
      val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
      val enabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
      Logger.d(TAG, "isGpsEnabled=$enabled")
      enabled
    } catch (e: Exception) {
      Logger.e(TAG, "isGpsEnabled check failed", e)
      false
    }
  }

  fun startPeriodicUpdates(context: Context, scope: CoroutineScope, intervalMs: Long = 300_000L) {
    stopPeriodicUpdates()
    Logger.i(TAG, "startPeriodicUpdates — interval=${intervalMs}ms")
    periodicJob = scope.launch(Dispatchers.IO) {
      while (true) {
        val loc = getCurrentLocation(context)
        if (loc != null) {
          _latestLocation.value = loc
          Logger.i(TAG, "Periodic location update: lat=${loc.latitude} lng=${loc.longitude}")
          FirestoreSync.updateDeviceLocation(loc)
        } else {
          Logger.w(TAG, "Periodic location update — no location available")
        }
        delay(intervalMs)
      }
    }
  }

  fun stopPeriodicUpdates() {
    Logger.i(TAG, "stopPeriodicUpdates")
    periodicJob?.cancel()
    periodicJob = null
  }

  fun getLocationUrl(location: DeviceLocation?): String {
    if (location == null) {
      Logger.d(TAG, "getLocationUrl — null location, returning empty")
      return ""
    }
    val url = "https://www.google.com/maps?q=${location.latitude},${location.longitude}"
    Logger.d(TAG, "getLocationUrl: $url")
    return url
  }
}
