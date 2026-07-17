package com.example.utils

import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.example.utils.Logger
import kotlinx.coroutines.tasks.await

data class DeviceLocation(
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val accuracy: Float = 0f,
  val timestamp: Long = System.currentTimeMillis()
)

object FirestoreSync {
  private const val TAG = "FirestoreSync"
  private val db = FirebaseFirestore.getInstance()

  fun getDeviceId(context: Context): String {
    Logger.d(TAG, "getDeviceId")
    val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
    var deviceId = prefs.getString("device_id", null)
    if (deviceId == null) {
      deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "unknown_${System.currentTimeMillis()}"
      prefs.edit().putString("device_id", deviceId).apply()
      Logger.i(TAG, "New device ID generated: $deviceId")
    }
    return deviceId
  }

  suspend fun updateDeviceToken(token: String) {
    Logger.i(TAG, "updateDeviceToken starting")
    try {
      val data = hashMapOf(
        "fcmToken" to token,
        "lastSeen" to System.currentTimeMillis()
      )
      db.collection("fcmTokens").add(data).await()
      Logger.i(TAG, "Device token updated successfully in fcmTokens collection")
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to update device token", e)
    }
  }

  suspend fun reportEvent(type: String, location: DeviceLocation?) {
    Logger.i(TAG, "reportEvent type=$type lat=${location?.latitude} lng=${location?.longitude}")
    try {
      val event = hashMapOf(
        "type" to type,
        "timestamp" to System.currentTimeMillis(),
        "latitude" to (location?.latitude ?: 0.0),
        "longitude" to (location?.longitude ?: 0.0)
      )
      db.collection("alarmEvents").add(event).await()
      Logger.i(TAG, "Event '$type' reported successfully to alarmEvents")
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to report event '$type'", e)
    }
  }

  suspend fun reportLocation(location: DeviceLocation?) {
    Logger.i(TAG, "reportLocation lat=${location?.latitude} lng=${location?.longitude}")
    if (location == null) {
      Logger.w(TAG, "reportLocation called with null location — skipping")
      return
    }
    try {
      val data = hashMapOf(
        "latitude" to location.latitude,
        "longitude" to location.longitude,
        "accuracy" to location.accuracy.toDouble(),
        "timestamp" to location.timestamp
      )
      db.collection("locations").document("latest")
        .set(data, SetOptions.merge())
        .await()
      Logger.d(TAG, "Latest location document updated")
      db.collection("locations").add(data).await()
      Logger.i(TAG, "Location reported successfully to locations collection")
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to report location", e)
    }
  }

  suspend fun reportEmergency(message: String, location: DeviceLocation?) {
    Logger.w(TAG, "reportEmergency message=\"$message\" lat=${location?.latitude} lng=${location?.longitude}")
    try {
      val data = hashMapOf(
        "type" to "emergency",
        "message" to message,
        "timestamp" to System.currentTimeMillis(),
        "latitude" to (location?.latitude ?: 0.0),
        "longitude" to (location?.longitude ?: 0.0),
        "status" to "active"
      )
      db.collection("emergencies").add(data).await()
      Logger.i(TAG, "Emergency reported successfully to emergencies collection")
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to report emergency", e)
    }
  }

  suspend fun updateAlarmStatus(context: Context, armed: Boolean, location: DeviceLocation?) {
    Logger.i(TAG, "updateAlarmStatus armed=$armed lat=${location?.latitude} lng=${location?.longitude}")
    try {
      val deviceId = getDeviceId(context)
      val data = hashMapOf(
        "armed" to armed,
        "deviceId" to deviceId,
        "timestamp" to System.currentTimeMillis(),
        "latitude" to (location?.latitude ?: 0.0),
        "longitude" to (location?.longitude ?: 0.0)
      )
      db.collection("status").document("alarm")
        .set(data, SetOptions.merge())
        .await()
      Logger.d(TAG, "Alarm status document updated in status/alarm")
      reportEvent(if (armed) "armed" else "disarmed", location)
      Logger.i(TAG, "Alarm status update complete")
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to update alarm status", e)
    }
  }
}
