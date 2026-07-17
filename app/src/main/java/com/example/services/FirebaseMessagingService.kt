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
      FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
        currentToken = token
      }
    }
  }

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    currentToken = token
    CoroutineScope(Dispatchers.IO).launch {
      FirestoreSync.updateDeviceToken(token)
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    val data = message.data ?: return
    val command = data["command"] ?: return
    val deviceId = data["deviceId"]

    val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
    val registeredDeviceId = prefs.getString("device_id", null)
    if (deviceId != null && registeredDeviceId != null && deviceId != registeredDeviceId) return

    when (command) {
      "disarm" -> handleDisarm(data)
      "siren" -> handleSiren(data)
      "locate" -> handleLocate()
      "emergency" -> handleEmergency(data)
      "ping" -> handlePing()
    }
  }

  private fun handleDisarm(data: Map<String, String>) {
    if (!AlarmHelper.isArmed) return
    AlarmHelper.isArmed = false
    if (AlarmHelper.isSirenActive) {
      AlarmHelper.stopSiren(this)
    }
    getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
      .edit().putBoolean("protection_active", false).apply()
    val stopIntent = Intent(this, ProtectionService::class.java).apply {
      action = ProtectionService.ACTION_STOP
    }
    stopService(stopIntent)

    showNotification("System Disarmed", "Remote disarm command received and executed.")
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getLastKnownLocation(this@FirebaseMessagingService)
      FirestoreSync.reportEvent("disarmed", location)
    }
  }

  private fun handleSiren(data: Map<String, String>) {
    if (!AlarmHelper.isArmed) {
      showNotification("Guardian Alert", "Remote siren trigger received but system is not armed.")
      return
    }
    val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    startActivity(intent)
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getLastKnownLocation(this@FirebaseMessagingService)
      FirestoreSync.reportEvent("remote_siren", location)
    }
  }

  private fun handleLocate() {
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getCurrentLocation(this@FirebaseMessagingService)
      FirestoreSync.reportLocation(location)
      showNotification("Location Reported", "Current device location has been sent to the admin panel.")
    }
  }

  private fun handleEmergency(data: Map<String, String>) {
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getCurrentLocation(this@FirebaseMessagingService)
      val helpMessage = data["message"] ?: "Emergency help requested"
      FirestoreSync.reportEmergency(helpMessage, location)

      if (AlarmHelper.isSirenActive) return@launch
      AlarmHelper.startSiren(this@FirebaseMessagingService)
      val intent = Intent(this@FirebaseMessagingService, AlarmOverlayActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      startActivity(intent)
    }
  }

  private fun handlePing() {
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getLastKnownLocation(this@FirebaseMessagingService)
      FirestoreSync.reportEvent("pong", location)
    }
  }

  private fun showNotification(title: String, body: String) {
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
