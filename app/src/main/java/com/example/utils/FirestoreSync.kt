package com.example.utils

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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
    val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
    var deviceId = prefs.getString("device_id", null)
    if (deviceId == null) {
      deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "unknown_${System.currentTimeMillis()}"
      prefs.edit().putString("device_id", deviceId).apply()
    }
    return deviceId
  }

  suspend fun updateDeviceToken(token: String) {
    try {
      val data = hashMapOf(
        "fcmToken" to token,
        "lastSeen" to System.currentTimeMillis()
      )
      db.collection("fcmTokens").add(data).await()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update device token", e)
    }
  }

  suspend fun reportEvent(type: String, location: DeviceLocation?) {
    try {
      val event = hashMapOf(
        "type" to type,
        "timestamp" to System.currentTimeMillis(),
        "latitude" to (location?.latitude ?: 0.0),
        "longitude" to (location?.longitude ?: 0.0)
      )
      db.collection("alarmEvents").add(event).await()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to report event", e)
    }
  }

  suspend fun reportLocation(location: DeviceLocation?) {
    if (location == null) return
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
      db.collection("locations").add(data).await()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to report location", e)
    }
  }

  suspend fun reportEmergency(message: String, location: DeviceLocation?) {
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
    } catch (e: Exception) {
      Log.e(TAG, "Failed to report emergency", e)
    }
  }

  suspend fun updateAlarmStatus(context: Context, armed: Boolean, location: DeviceLocation?) {
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
      reportEvent(if (armed) "armed" else "disarmed", location)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update alarm status", e)
    }
  }
}
