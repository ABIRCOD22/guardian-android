package com.example.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ui.alarm.AlarmOverlayActivity
import com.example.utils.AlarmHelper
import com.example.utils.Constants
import com.example.utils.FirestoreSync
import com.example.utils.LocationHelper
import com.example.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SecurePhoneAccessibilityService : AccessibilityService() {
  companion object {
    private const val TAG = "A11yWatcher"
  }

  private var volumeUpCounter = 0
  private var powerCounter = 0
  private val mainHandler = Handler(Looper.getMainLooper())
  private val volumeUpReset = Runnable { volumeUpCounter = 0; Logger.d(TAG, "volumeUpCounter reset") }
  private val powerReset = Runnable { powerCounter = 0; Logger.d(TAG, "powerCounter reset") }

  private val oemPowerPackages = hashSetOf(
    "com.samsung.android.globalactions",
    "com.android.systemui",
    "com.google.android.systemui",
    "com.oneplus.systemui",
    "com.miui.systemui",
    "com.oppo.systemui",
    "com.vivo.systemui",
    "com.xiaomi.systemui",
    "com.sec.android.android.systemui",
    "com.sec.android.globalactions"
  )

  override fun onServiceConnected() {
    super.onServiceConnected()
    Logger.i(TAG, "onServiceConnected — setting FLAG_REQUEST_FILTER_KEY_EVENTS")
    serviceInfo = serviceInfo?.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val packageName = event.packageName?.toString() ?: return
    val className = event.className?.toString() ?: ""
    Logger.d(TAG, "onAccessibilityEvent pkg=$packageName cls=$className")

    if (AlarmHelper.isSirenActive &&
      (className.contains("pinned", ignoreCase = true) ||
       className.contains("locktask", ignoreCase = true) ||
       className.contains("screenpinn", ignoreCase = true) ||
       event.text.any { it.contains("pinned", ignoreCase = true) })) {
      Logger.w(TAG, "Screen pinning dialog detected — auto-approving")
      dismissScreenPinningDialog()
      return
    }

    val isPowerDialog = (oemPowerPackages.contains(packageName) &&
      (className.contains("globalactions", ignoreCase = true) ||
       className.contains("powerdialog", ignoreCase = true) ||
       className.contains("power_menu", ignoreCase = true) ||
       className.contains("shutdown", ignoreCase = true) ||
       className.contains("restart", ignoreCase = true) ||
       className.contains("poweroff", ignoreCase = true) ||
       className.contains("power_off", ignoreCase = true)))
    Logger.d(TAG, "isPowerDialog=$isPowerDialog (pkg=$packageName matches OEM set=${oemPowerPackages.contains(packageName)})")

    if (!isPowerDialog) return

    Logger.w(TAG, "Detected power dialog — package: $packageName class: $className")

    if (AlarmHelper.isSirenActive) {
      Logger.w(TAG, "Siren active — collapsing power dialog")
      collapseSystemDialogs()
      return
    }

    if (AlarmHelper.isArmed && getSharedPreferences("guardian_prefs", MODE_PRIVATE)
        .getBoolean("protection_active", false)) {
      Logger.w(TAG, "Power-off attempt while armed — EMERGENCY! Starting siren and lock screen")
      AlarmHelper.startSiren(this)
      performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
      performGlobalAction(GLOBAL_ACTION_BACK)
      val lockIntent = Intent(this, AlarmOverlayActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      startActivity(lockIntent)
    }
  }

  private fun dismissScreenPinningDialog() {
    var root: AccessibilityNodeInfo? = null
    try {
      root = rootInActiveWindow ?: return
      val confirmTexts = listOf("Got it", "Got It", "Confirm", "Pin", "pin")
      for (text in confirmTexts) {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
          if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Logger.w(TAG, "Auto-clicked pinning confirm: \"$text\"")
            node.recycle()
            return
          }
          node.recycle()
        }
      }
      Logger.d(TAG, "No clickable pinning confirmation found")
    } catch (e: Exception) {
      Logger.e(TAG, "dismissScreenPinningDialog failed", e)
    } finally {
      root?.recycle()
    }
  }

  private fun collapseSystemDialogs() {
    Logger.w(TAG, "collapseSystemDialogs — sending BACK, LOCK_SCREEN, then reasserting overlay")
    performGlobalAction(GLOBAL_ACTION_BACK)
    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    mainHandler.postDelayed({ reassertAlarmOverlay() }, 150)
  }

  private fun reassertAlarmOverlay() {
    Logger.d(TAG, "reassertAlarmOverlay")
    val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    try {
      startActivity(intent)
      Logger.i(TAG, "AlarmOverlayActivity re-started")
    } catch (e: Exception) {
      Logger.e(TAG, "reassertAlarmOverlay failed", e)
    }
  }

  private fun triggerEmergencySiren() {
    val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
    val protectionActive = prefs.getBoolean("protection_active", false)
    if (!AlarmHelper.isArmed && !protectionActive) {
      Logger.w(TAG, "triggerEmergencySiren called but system is not armed — ignoring")
      return
    }
    Logger.w(TAG, "Triple power press — EMERGENCY TRIGGERED")
    AlarmHelper.startSiren(this)
    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    startActivity(intent)
    CoroutineScope(Dispatchers.IO).launch {
      val location = LocationHelper.getCurrentLocation(this@SecurePhoneAccessibilityService)
      FirestoreSync.reportEmergencyWithAlarm("Triple power press emergency trigger", location)
      Logger.i(TAG, "Emergency reported to Firestore")
    }
  }

  override fun onInterrupt() {
    Logger.d(TAG, "onInterrupt")
  }

  override fun onKeyEvent(event: KeyEvent?): Boolean {
    if (event == null) return false
    val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
    val protectionActive = prefs.getBoolean("protection_active", false)
    Logger.d(TAG, "onKeyEvent keyCode=${event.keyCode} action=${event.action} isArmed=${AlarmHelper.isArmed} prefsActive=$protectionActive sirenActive=${AlarmHelper.isSirenActive}")
    if (!AlarmHelper.isArmed && !protectionActive) return super.onKeyEvent(event)

    when (event.keyCode) {
      KeyEvent.KEYCODE_POWER -> {
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            if (event.repeatCount == 0) {
              volumeUpCounter = 0
              mainHandler.removeCallbacks(volumeUpReset)
              powerCounter++
              mainHandler.removeCallbacks(powerReset)
              mainHandler.postDelayed(powerReset, 2000L)
              Logger.d(TAG, "Power press #$powerCounter")
              if (powerCounter >= 3) {
                Logger.w(TAG, "Triple power press detected — triggering emergency")
                powerCounter = 0
                mainHandler.removeCallbacks(powerReset)
                triggerEmergencySiren()
              }
            }
          }
        }
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        if (event.action == KeyEvent.ACTION_DOWN) {
          Logger.d(TAG, "Volume down pressed during siren — sending shake broadcast")
          sendBroadcast(Intent(Constants.ACTION_ALARM_SHAKE))
        }
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        if (event.action == KeyEvent.ACTION_DOWN) {
          volumeUpCounter++
          mainHandler.removeCallbacks(volumeUpReset)
          mainHandler.postDelayed(volumeUpReset, 3000L)
          Logger.d(TAG, "Volume up press #$volumeUpCounter (needs 3 to silence)")
          if (volumeUpCounter >= 3) {
            Logger.w(TAG, "Triple volume up — silencing siren")
            volumeUpCounter = 0
            mainHandler.removeCallbacks(volumeUpReset)
            AlarmHelper.stopSiren()
            sendBroadcast(Intent(Constants.ACTION_ALARM_STOPPED))
            val i = Intent(this, AlarmOverlayActivity::class.java).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
          }
        }
        return false
      }
    }
    return super.onKeyEvent(event)
  }
}
