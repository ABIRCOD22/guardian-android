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

      // Also update the user document with latest location
      val ctx = contextRef
      if (ctx != null) {
        val deviceId = getDeviceId(ctx)
        val userLoc = hashMapOf<String, Any>(
          "lastLatitude" to location.latitude,
          "lastLongitude" to location.longitude,
          "lastActive" to System.currentTimeMillis()
        )
        db.collection("users").document(deviceId)
          .set(userLoc, SetOptions.merge())
          .await()
        Logger.d(TAG, "User document location updated")
      }
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to report location", e)
    }
  }

  // Lightweight location write — ONLY to user doc (1 write, for admin map)
  suspend fun updateDeviceLocation(location: DeviceLocation) {
    Logger.d(TAG, "updateDeviceLocation lat=${location.latitude} lng=${location.longitude}")
    try {
      val ctx = contextRef ?: return
      val deviceId = getDeviceId(ctx)
      val data = hashMapOf<String, Any>(
        "lastLatitude" to location.latitude,
        "lastLongitude" to location.longitude,
        "lastActive" to System.currentTimeMillis()
      )
      db.collection("users").document(deviceId)
        .set(data, SetOptions.merge())
        .await()
      Logger.d(TAG, "User doc location updated (1 write)")
    } catch (e: Exception) {
      Logger.e(TAG, "updateDeviceLocation failed", e)
    }
  }

  // Report an alarm breach — sets alarmActive=true on user doc so admin panel shows danger
  suspend fun reportAlarmBreach(reason: String, location: DeviceLocation?) {
    Logger.w(TAG, "reportAlarmBreach reason=$reason")
    try {
      val ctx = contextRef ?: return
      val deviceId = getDeviceId(ctx)
      val data = hashMapOf<String, Any>(
        "alarmActive" to true,
        "lastActive" to System.currentTimeMillis(),
        "breachReason" to reason
      )
      if (location != null) {
        data["lastLatitude"] = location.latitude
        data["lastLongitude"] = location.longitude
      }
      db.collection("users").document(deviceId)
        .set(data, SetOptions.merge())
        .await()
      Logger.i(TAG, "Alarm breach reported to user doc (alarmActive=true)")

      // Also add to emergencies collection
      val emergency = hashMapOf(
        "type" to "breach",
        "message" to reason,
        "timestamp" to System.currentTimeMillis(),
        "latitude" to (location?.latitude ?: 0.0),
        "longitude" to (location?.longitude ?: 0.0),
        "deviceId" to deviceId,
        "status" to "active"
      )
      db.collection("emergencies").add(emergency).await()
      Logger.i(TAG, "Emergency added to emergencies collection")
    } catch (e: Exception) {
      Logger.e(TAG, "reportAlarmBreach failed", e)
    }
  }

  // Context reference for reportLocation
  private var contextRef: Context? = null
  fun setContext(context: Context) { contextRef = context }

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

  suspend fun saveUserProfile(context: android.content.Context, name: String, email: String, photoUrl: String) {
    try {
      val deviceId = getDeviceId(context)
      val data = hashMapOf(
        "displayName" to name,
        "email" to email,
        "photoUrl" to photoUrl,
        "deviceModel" to android.os.Build.MODEL,
        "osVersion" to android.os.Build.VERSION.RELEASE,
        "lastActive" to System.currentTimeMillis(),
        "fcmToken" to (com.example.services.FirebaseMessagingService.getToken() ?: ""),
        "shieldActive" to com.example.utils.AlarmHelper.isArmed,
        "settings.isProtectionActive" to com.example.utils.AlarmHelper.isArmed
      )
      db.collection("users").document(deviceId)
        .set(data, com.google.firebase.firestore.SetOptions.merge())
        .await()
      Logger.i("FirestoreSync", "User profile saved/updated for $name ($email)")
    } catch (e: Exception) {
      Logger.e("FirestoreSync", "Failed to save user profile", e)
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

      // Update user doc — alarmActive stays false on arm (only breaches set it true)
      val userData = hashMapOf(
        "shieldActive" to armed,
        "settings.isProtectionActive" to armed,
        "alarmActive" to false,
        "lastActive" to System.currentTimeMillis()
      )
      if (location != null) {
        userData["lastLatitude"] = location.latitude
        userData["lastLongitude"] = location.longitude
      }
      db.collection("users").document(deviceId)
        .set(userData, SetOptions.merge())
        .await()
      Logger.d(TAG, "User document shieldActive updated to $armed")

      reportEvent(if (armed) "armed" else "disarmed", location)
      Logger.i(TAG, "Alarm status update complete")
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to update alarm status", e)
    }
  }
}
