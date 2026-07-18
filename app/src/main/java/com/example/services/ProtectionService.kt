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
  private var emergencyTriggerReceiver: BroadcastReceiver? = null
  private var alarmStoppedReceiver: BroadcastReceiver? = null
  private var sensorManager: SensorManager? = null
  private var proximitySensor: Sensor? = null
  private var wasProximityNear = false
  private var ready = false
  private var powerPressCount = 0
  private var pendingEmergencyRunnable: Runnable? = null
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
        triggerAlarmWithGracePeriod("proximity")
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
    registerEmergencyTriggerReceiver()
    registerAlarmStoppedReceiver()
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
          triggerAlarmWithGracePeriod("power_disconnected")
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
            triggerAlarmWithGracePeriod("usb_connected")
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
            triggerAlarmWithGracePeriod("sim_removed")
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
        if (intent == null || !isProtectionActive()) return
        powerPressCount++
        Logger.d(TAG, "Screen state: ${intent.action} pressCount=$powerPressCount")
        mainHandler.removeCallbacks(powerPressReset)
        mainHandler.postDelayed(powerPressReset, 3000L)
        if (powerPressCount >= 2) {
          Logger.w(TAG, "Rapid power presses detected via screen toggles — EMERGENCY!")
          powerPressCount = 0
          mainHandler.removeCallbacks(powerPressReset)
          triggerAlarmWithGracePeriod("rapid_power_press")
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

  private fun registerEmergencyTriggerReceiver() {
    emergencyTriggerReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val reason = intent.getStringExtra("reason") ?: "emergency_trigger"
        Logger.w(TAG, "Emergency trigger broadcast received — reason=$reason")
        triggerAlarmWithGracePeriod(reason)
      }
    }
    val filter = IntentFilter(Constants.ACTION_EMERGENCY_TRIGGERED)
    registerReceiver(emergencyTriggerReceiver, filter,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
  }

  private fun registerAlarmStoppedReceiver() {
    alarmStoppedReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Constants.ACTION_ALARM_STOPPED) {
          Logger.w(TAG, "Alarm stopped — cancelling pending emergency")
          cancelPendingEmergency()
        }
      }
    }
    registerReceiver(alarmStoppedReceiver, IntentFilter(Constants.ACTION_ALARM_STOPPED),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)
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

  private fun triggerAlarmWithGracePeriod(reason: String) {
    Logger.w(TAG, "triggerAlarmWithGracePeriod — starting siren + overlay (reason=$reason)")
    // Cancel any previous pending emergency runnable without stopping current siren
    pendingEmergencyRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingEmergencyRunnable = null
    // Start siren and show overlay
    AlarmHelper.startSiren(this)
    startActivity(Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    })
    // Schedule emergency Firestore write in 15s
    val runnable = Runnable {
      Logger.w(TAG, "Grace period expired (15s) — writing emergency to Firestore")
      GlobalScope.launch {
        val loc = LocationHelper.getCurrentLocation(this@ProtectionService)
        FirestoreSync.reportEmergencyWithAlarm(reason, loc)
      }
      pendingEmergencyRunnable = null
    }
    pendingEmergencyRunnable = runnable
    mainHandler.postDelayed(runnable, 15000L)
  }

  private fun cancelPendingEmergency() {
    pendingEmergencyRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingEmergencyRunnable = null
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
    emergencyTriggerReceiver?.let {
      try { unregisterReceiver(it) } catch (e: Exception) { Logger.e(TAG, "Error unregistering emergencyTriggerReceiver", e) }
    }
    alarmStoppedReceiver?.let {
      try { unregisterReceiver(it) } catch (e: Exception) { Logger.e(TAG, "Error unregistering alarmStoppedReceiver", e) }
    }
    try { sensorManager?.unregisterListener(proximityListener) } catch (e: Exception) { Logger.e(TAG, "Error unregistering proximityListener", e) }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
