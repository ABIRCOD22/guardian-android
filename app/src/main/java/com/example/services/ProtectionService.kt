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
import com.example.utils.FirestoreSync
import com.example.utils.LocationHelper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.example.utils.Logger

class ProtectionService : Service() {
  companion object {
    private const val TAG = "ProtectionService"
    const val ACTION_START = "com.example.action.START_PROTECTION"
    const val ACTION_STOP = "com.example.action.STOP_PROTECTION"
  }

  private var powerReceiver: BroadcastReceiver? = null
  private var usbReceiver: BroadcastReceiver? = null
  private var simReceiver: BroadcastReceiver? = null
  private var screenReceiver: BroadcastReceiver? = null
  private var sensorManager: SensorManager? = null
  private var proximitySensor: Sensor? = null
  private var wasProximityNear = false
  private var ready = false
  private var powerPressCount = 0
  private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private val powerPressReset = Runnable { powerPressCount = 0 }

  private val proximityListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      if (!isProtectionActive()) return
      val distance = event.values[0]
      val maxRange = proximitySensor?.maximumRange ?: return
      val isNear = distance < maxRange * 0.5f
      if (wasProximityNear && !isNear) {
        Logger.i(TAG, "Proximity sensor triggered alarm (near->far transition)")
        triggerAlarm("proximity")
      }
      wasProximityNear = isNear
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
      Logger.d(TAG, "Proximity accuracy changed to $accuracy")
    }
  }

  override fun onCreate() {
    super.onCreate()
    Logger.logLifecycle(TAG, "onCreate")
    createNotificationChannel()
    registerPowerReceiver()
    registerUsbReceiver()
    registerSimReceiver()
    registerScreenReceiver()
    registerProximitySensor()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      ready = true
      Logger.i(TAG, "Ready flag set to true after 2s delay")
    }, 2000)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Logger.i(TAG, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
    when (intent?.action) {
      ACTION_STOP -> {
        Logger.i(TAG, "ACTION_STOP received — stopping foreground service")
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
    Logger.w(TAG, "onTaskRemoved — restarting service")
    val restartIntent = Intent(this, ProtectionService::class.java).apply {
      action = ACTION_START
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(restartIntent)
      } else {
        startService(restartIntent)
      }
      Logger.i(TAG, "Restart intent sent successfully")
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to restart service on task removed", e)
    }
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
    val active = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
      .getBoolean("protection_active", false)
    Logger.d(TAG, "isProtectionActive=$active")
    return active
  }

  private fun registerPowerReceiver() {
    powerReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return
        Logger.d(TAG, "Power receiver: action=${intent.action} ready=$ready monitoringEnabled=${AlarmHelper.monitoringEnabled}")
        if (!ready || !isProtectionActive() || !AlarmHelper.monitoringEnabled) return
        if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
          Logger.w(TAG, "Power disconnected — triggering alarm")
          triggerAlarm("power_disconnected")
        } else if (intent.action == Intent.ACTION_POWER_CONNECTED) {
          Logger.i(TAG, "Power connected (no action required)")
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
        Logger.d(TAG, "USB receiver: action=${intent.action} ready=$ready monitoringEnabled=${AlarmHelper.monitoringEnabled}")
        if (!ready || !isProtectionActive() || !AlarmHelper.monitoringEnabled) return
        if (intent.action == "android.hardware.usb.action.USB_STATE") {
          val connected = intent.getBooleanExtra("connected", false)
          Logger.i(TAG, "USB state changed — connected=$connected")
          if (connected) {
            Logger.w(TAG, "USB connected — triggering alarm")
            triggerAlarm("usb_connected")
          }
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
        Logger.d(TAG, "SIM receiver: action=${intent.action} ready=$ready monitoringEnabled=${AlarmHelper.monitoringEnabled}")
        if (!ready || !isProtectionActive() || !AlarmHelper.monitoringEnabled) return
        if (intent.action == "android.intent.action.SIM_STATE_CHANGED") {
          val state = intent.getStringExtra("ss") ?: return
          Logger.i(TAG, "SIM state changed — state=$state")
          if (state == "ABSENT" || state == "NOT_READY") {
            Logger.w(TAG, "SIM state=$state — triggering alarm")
            triggerAlarm("sim_removed")
          }
        }
      }
    }
    val filter = IntentFilter("android.intent.action.SIM_STATE_CHANGED")
    registerReceiver(simReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
  }

  private fun registerScreenReceiver() {
    screenReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || !isProtectionActive() || !ready) return
        Logger.d(TAG, "Screen state: ${intent.action} pressCount=${powerPressCount + 1}")
        powerPressCount++
        mainHandler.removeCallbacks(powerPressReset)
        mainHandler.postDelayed(powerPressReset, 2000L)
        if (powerPressCount >= 3) {
          Logger.w(TAG, "Triple power press detected via screen toggles — EMERGENCY!")
          powerPressCount = 0
          mainHandler.removeCallbacks(powerPressReset)
          AlarmHelper.startSiren(this@ProtectionService)
          startActivity(Intent(this@ProtectionService, AlarmOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
          })
          GlobalScope.launch {
            val loc = LocationHelper.getCurrentLocation(this@ProtectionService)
            FirestoreSync.reportEmergencyWithAlarm("Triple power press emergency trigger", loc)
          }
        }
      }
    }
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
    }
    registerReceiver(screenReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
    Logger.i(TAG, "Screen on/off receiver registered for power press detection")
  }

  private fun registerProximitySensor() {
    sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
    proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    if (proximitySensor != null) {
      Logger.i(TAG, "Proximity sensor registered (maxRange=${proximitySensor?.maximumRange})")
      sensorManager?.registerListener(
        proximityListener,
        proximitySensor,
        SensorManager.SENSOR_DELAY_NORMAL
      )
    } else {
      Logger.w(TAG, "No proximity sensor available on this device")
    }
  }

  private fun triggerAlarm(reason: String = "breach") {
    Logger.w(TAG, "triggerAlarm — starting AlarmOverlayActivity (reason=$reason)")
    GlobalScope.launch {
      val loc = LocationHelper.getCurrentLocation(this@ProtectionService)
      FirestoreSync.reportAlarmBreach(reason, loc)
    }
    val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    startActivity(intent)
  }

  override fun onDestroy() {
    Logger.logLifecycle(TAG, "onDestroy — unregistering receivers and sensor listener")
    powerReceiver?.let {
      try { unregisterReceiver(it) } catch (e: Exception) { Logger.e(TAG, "Error unregistering powerReceiver", e) }
    }
    usbReceiver?.let {
      try { unregisterReceiver(it) } catch (e: Exception) { Logger.e(TAG, "Error unregistering usbReceiver", e) }
    }
    simReceiver?.let {
      try { unregisterReceiver(it) } catch (e: Exception) { Logger.e(TAG, "Error unregistering simReceiver", e) }
    }
    screenReceiver?.let {
      try { unregisterReceiver(it) } catch (e: Exception) { Logger.e(TAG, "Error unregistering screenReceiver", e) }
    }
    try { sensorManager?.unregisterListener(proximityListener) } catch (e: Exception) { Logger.e(TAG, "Error unregistering proximityListener", e) }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
