package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ui.alarm.AlarmOverlayActivity
import com.example.utils.AlarmHelper
import com.example.utils.Constants

class ProtectionService : Service() {
  companion object {
    const val ACTION_START = "com.example.action.START_PROTECTION"
    const val ACTION_STOP = "com.example.action.STOP_PROTECTION"
  }

  private var powerReceiver: BroadcastReceiver? = null
  private var usbReceiver: BroadcastReceiver? = null
  private var simReceiver: BroadcastReceiver? = null
  private var sensorManager: SensorManager? = null
  private var proximitySensor: Sensor? = null
  private var wasProximityNear = false
  private var ready = false

  private val proximityListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      if (!isProtectionActive()) return
      val distance = event.values[0]
      val maxRange = proximitySensor?.maximumRange ?: return
      val isNear = distance < maxRange * 0.5f
      if (wasProximityNear && !isNear) {
        triggerAlarm()
      }
      wasProximityNear = isNear
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
  }

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    registerPowerReceiver()
    registerUsbReceiver()
    registerSimReceiver()
    registerProximitySensor()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ready = true }, 2000)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        val notification = createNotification()
        startForeground(1, notification)
      }
    }
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val restartIntent = Intent(this, ProtectionService::class.java).apply {
      action = ACTION_START
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(restartIntent)
      } else {
        startService(restartIntent)
      }
    } catch (_: Exception) {}
    super.onTaskRemoved(rootIntent)
  }

  private fun createNotification(): Notification {
    return NotificationCompat.Builder(this, Constants.CHANNEL_PROTECTION)
      .setContentTitle("Guardian active")
      .setContentText("Actively monitoring device environment sensors.")
      .setSmallIcon(android.R.drawable.ic_lock_lock)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val serviceChannel = NotificationChannel(
        Constants.CHANNEL_PROTECTION,
        "Guardian — Anti-Theft Monitoring",
        NotificationManager.IMPORTANCE_LOW
      )
      val manager = getSystemService(NotificationManager::class.java)
      manager?.createNotificationChannel(serviceChannel)
    }
  }

  private fun isProtectionActive(): Boolean {
    return getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
      .getBoolean("protection_active", false)
  }

  private fun registerPowerReceiver() {
    powerReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return
        if (!ready || !isProtectionActive() || !AlarmHelper.monitoringEnabled) return
        if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
          triggerAlarm()
        }
      }
    }
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_POWER_DISCONNECTED)
      addAction(Intent.ACTION_POWER_CONNECTED)
    }
    registerReceiver(powerReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
  }

  private fun registerUsbReceiver() {
    usbReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return
        if (!ready || !isProtectionActive() || !AlarmHelper.monitoringEnabled) return
        if (intent.action == "android.hardware.usb.action.USB_STATE") {
          val connected = intent.getBooleanExtra("connected", false)
          if (connected) triggerAlarm()
        }
      }
    }
    val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
    registerReceiver(usbReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
  }

  private fun registerSimReceiver() {
    simReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return
        if (!ready || !isProtectionActive() || !AlarmHelper.monitoringEnabled) return
        if (intent.action == "android.intent.action.SIM_STATE_CHANGED") {
          val state = intent.getStringExtra("ss") ?: return
          if (state == "ABSENT" || state == "NOT_READY") {
            triggerAlarm()
          }
        }
      }
    }
    val filter = IntentFilter("android.intent.action.SIM_STATE_CHANGED")
    registerReceiver(simReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
  }

  private fun registerProximitySensor() {
    sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
    proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    if (proximitySensor != null) {
      sensorManager?.registerListener(
        proximityListener,
        proximitySensor,
        SensorManager.SENSOR_DELAY_NORMAL
      )
    }
  }

  private fun triggerAlarm() {
    val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    startActivity(intent)
  }

  override fun onDestroy() {
    powerReceiver?.let { unregisterReceiver(it) }
    usbReceiver?.let { unregisterReceiver(it) }
    simReceiver?.let { unregisterReceiver(it) }
    sensorManager?.unregisterListener(proximityListener)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
