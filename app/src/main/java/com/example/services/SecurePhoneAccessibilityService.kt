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
import com.example.utils.Logger

class SecurePhoneAccessibilityService : AccessibilityService() {
  companion object {
    private const val TAG = "A11yWatcher"
  }

  private var volumeUpCounter = 0
  private val mainHandler = Handler(Looper.getMainLooper())
  private val volumeUpReset = Runnable { volumeUpCounter = 0; Logger.d(TAG, "volumeUpCounter reset") }

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

  private fun getPrefs() = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
  private fun now() = System.currentTimeMillis()

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
    if (!isPowerDialog) return

    Logger.w(TAG, "Power dialog detected — setting power press flag")

    if (AlarmHelper.isSirenActive) {
      Logger.w(TAG, "Siren active — collapsing power dialog")
      collapseSystemDialogs()
      return
    }

    val prefs = getPrefs()
    val t = now()
    prefs.edit().putLong(Constants.PREFS_POWER_PRESS_TIME, t).apply()
    val volUpTime = prefs.getLong(Constants.PREFS_VOL_UP_PRESS_TIME, 0)
    if (t - volUpTime in 1..2000) {
      Logger.w(TAG, "Volume Up + Power combo via power dialog — EMERGENCY!")
      prefs.edit().remove(Constants.PREFS_POWER_PRESS_TIME).remove(Constants.PREFS_VOL_UP_PRESS_TIME).apply()
      sendEmergencyBroadcast("volume_up_power_combo")
    }
  }

  private fun sendEmergencyBroadcast(reason: String) {
    val intent = Intent(Constants.ACTION_EMERGENCY_TRIGGERED).apply {
      putExtra("reason", reason)
    }
    sendBroadcast(intent)
    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    performGlobalAction(GLOBAL_ACTION_BACK)
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

  override fun onInterrupt() {
    Logger.d(TAG, "onInterrupt")
  }

  override fun onKeyEvent(event: KeyEvent?): Boolean {
    if (event == null) return false
    val prefs = getPrefs()
    val protectionActive = prefs.getBoolean("protection_active", false)
    Logger.d(TAG, "onKeyEvent keyCode=${event.keyCode} action=${event.action} isArmed=${AlarmHelper.isArmed} prefsActive=$protectionActive sirenActive=${AlarmHelper.isSirenActive}")
    if (!AlarmHelper.isArmed && !protectionActive) return super.onKeyEvent(event)

    when (event.keyCode) {

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        if (event.action == KeyEvent.ACTION_DOWN && AlarmHelper.isSirenActive) {
          Logger.d(TAG, "Volume down pressed during siren — sending shake broadcast")
          sendBroadcast(Intent(Constants.ACTION_ALARM_SHAKE))
        }
        return false
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        if (event.action == KeyEvent.ACTION_DOWN) {
          val t = now()
          prefs.edit().putLong(Constants.PREFS_VOL_UP_PRESS_TIME, t).apply()
          val powerTime = prefs.getLong(Constants.PREFS_POWER_PRESS_TIME, 0)
          if (t - powerTime in 1..2000) {
            Logger.w(TAG, "Power + Volume Up combo detected — EMERGENCY!")
            prefs.edit().remove(Constants.PREFS_POWER_PRESS_TIME).remove(Constants.PREFS_VOL_UP_PRESS_TIME).apply()
            sendEmergencyBroadcast("power_volume_up_combo")
            return true
          }
          if (AlarmHelper.isSirenActive) {
            volumeUpCounter++
            mainHandler.removeCallbacks(volumeUpReset)
            mainHandler.postDelayed(volumeUpReset, 3000L)
            Logger.d(TAG, "Volume up press #$volumeUpCounter")
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
        }
        return false
      }
    }
    return super.onKeyEvent(event)
  }
}
