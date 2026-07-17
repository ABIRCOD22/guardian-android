package com.example.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.ui.alarm.AlarmOverlayActivity
import com.example.utils.AlarmHelper
import com.example.utils.Constants
import com.example.utils.FirestoreSync
import com.example.utils.LocationHelper
import com.example.utils.Logger
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FirebaseMessagingService : com.google.firebase.messaging.FirebaseMessagingService() {

  companion object {
    private const val TAG = "GuardianFCM"
    private var currentToken: String? = null

    fun getToken(): String? = currentToken

    fun refreshToken() {
      Logger.i(TAG, "refreshToken — requesting new FCM token")
      FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
        currentToken = token
        Logger.i(TAG, "FCM token refreshed: $token")
      }.addOnFailureListener { e ->
        Logger.e(TAG, "Failed to refresh FCM token", e)
      }
    }
  }

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    Logger.i(TAG, "onNewToken — token=$token")
    currentToken = token
    CoroutineScope(Dispatchers.IO).launch {
      FirestoreSync.updateDeviceToken(token)
      Logger.i(TAG, "Device token updated in Firestore")
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    val data = message.data ?: run {
      Logger.w(TAG, "onMessageReceived — no data payload")
      return
    }
    val command = data["command"] ?: run {
      Logger.w(TAG, "onMessageReceived — no 'command' in data")
      return
    }
    val deviceId = data["deviceId"]
    Logger.i(TAG, "onMessageReceived command=$command deviceId=$deviceId")

    val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
    val registeredDeviceId = prefs.getString("device_id", null)
    if (deviceId != null && registeredDeviceId != null && deviceId != registeredDeviceId) {
      Logger.w(TAG, "Device ID mismatch — ignoring message (received=$deviceId, registered=$registeredDeviceId)")
      return
    }

    Logger.i(TAG, "Dispatching command: $command")
    when (command) {
      "disarm" -> handleDisarm(data)
      "siren" -> handleSiren(data)
      "locate" -> handleLocate()
      "emergency" -> handleEmergency(data)
      "ping" -> handlePing()
      else -> Logger.w(TAG, "Unknown command: $command")
    }
  }

  private fun handleDisarm(data: Map<String, String>) {
    Logger.i(TAG, "handleDisarm — isArmed=${AlarmHelper.isArmed}")
    if (!AlarmHelper.isArmed) {
      Logger.w(TAG, "Disarm ignored — system not armed")
      return
    }
    AlarmHelper.isArmed = false
    if (AlarmHelper.isSirenActive) {
      Logger.i(TAG, "Siren active — stopping siren during disarm")
      AlarmHelper.stopSiren(this)
    }
    getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
      .edit().putBoolean("protection_active", false).apply()
    val stopIntent = Intent(this, ProtectionService::class.java).apply {
      action = ProtectionService.ACTION_STOP
    }
    stopService(stopIntent)
    Logger.i(TAG, "ProtectionService stopped, system disarmed")

    showNotification("System Disarmed", "Remote disarm command received and executed.")
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getLastKnownLocation(this@FirebaseMessagingService)
      FirestoreSync.reportEvent("disarmed", location)
      Logger.i(TAG, "Disarm event reported to Firestore")
    }
  }

  private fun handleSiren(data: Map<String, String>) {
    Logger.i(TAG, "handleSiren — isArmed=${AlarmHelper.isArmed}")
    if (!AlarmHelper.isArmed) {
      Logger.i(TAG, "Remote siren trigger received but system is not armed — showing notification")
      showNotification("Guardian Alert", "Remote siren trigger received but system is not armed.")
      return
    }
    val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    startActivity(intent)
    Logger.i(TAG, "AlarmOverlayActivity started for remote siren")
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getLastKnownLocation(this@FirebaseMessagingService)
      FirestoreSync.reportEvent("remote_siren", location)
      Logger.i(TAG, "Remote siren event reported to Firestore")
    }
  }

  private fun handleLocate() {
    Logger.i(TAG, "handleLocate — requesting current location")
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getCurrentLocation(this@FirebaseMessagingService)
      Logger.i(TAG, "Location obtained: lat=${location?.latitude}, lng=${location?.longitude}")
      FirestoreSync.reportLocation(location)
      Logger.i(TAG, "Location reported to Firestore")
      showNotification("Location Reported", "Current device location has been sent to the admin panel.")
    }
  }

  private fun handleEmergency(data: Map<String, String>) {
    Logger.w(TAG, "handleEmergency — processing emergency command")
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getCurrentLocation(this@FirebaseMessagingService)
      val helpMessage = data["message"] ?: "Emergency help requested"
      FirestoreSync.reportEmergency(helpMessage, location)
      Logger.i(TAG, "Emergency reported to Firestore: $helpMessage")

      if (AlarmHelper.isSirenActive) {
        Logger.i(TAG, "Siren already active — skipping duplicate start")
        return@launch
      }
      AlarmHelper.startSiren(this@FirebaseMessagingService)
      val intent = Intent(this@FirebaseMessagingService, AlarmOverlayActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      startActivity(intent)
      Logger.i(TAG, "Siren + AlarmOverlayActivity started for emergency")
    }
  }

  private fun handlePing() {
    Logger.i(TAG, "handlePing — responding with pong")
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getLastKnownLocation(this@FirebaseMessagingService)
      FirestoreSync.reportEvent("pong", location)
      Logger.i(TAG, "Pong event reported to Firestore")
    }
  }

  private fun showNotification(title: String, body: String) {
    Logger.i(TAG, "showNotification — title=\"$title\" body=\"$body\"")
    val channelId = "guardian_fcm_channel"
    val notificationManager = getSystemService(NotificationManager::class.java)

    val intent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val pendingIntent = PendingIntent.getActivity(
      this, 0, intent,
      PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    )

    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val builder = NotificationCompat.Builder(this, channelId)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(body)
      .setAutoCancel(true)
      .setSound(soundUri)
      .setContentIntent(pendingIntent)
      .setPriority(NotificationCompat.PRIORITY_HIGH)

    notificationManager?.createNotificationChannel(
      android.app.NotificationChannel(
        channelId, "Guardian Remote Commands",
        android.app.NotificationManager.IMPORTANCE_HIGH
      )
    )
    notificationManager?.notify(System.currentTimeMillis().toInt(), builder.build())
  }
}
