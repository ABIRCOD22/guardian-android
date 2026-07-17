package com.example.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ui.alarm.AlarmOverlayActivity
import com.example.utils.AlarmHelper
import com.example.utils.Constants

class SecurePhoneAccessibilityService : AccessibilityService() {
  private var volumeUpCounter = 0
  private val mainHandler = Handler(Looper.getMainLooper())
  private val volumeUpReset = Runnable { volumeUpCounter = 0 }

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
    serviceInfo = serviceInfo?.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val packageName = event.packageName?.toString() ?: return
    val className = event.className?.toString() ?: ""

    if (AlarmHelper.isSirenActive &&
      (className.contains("pinned", ignoreCase = true) ||
       className.contains("locktask", ignoreCase = true) ||
       className.contains("screenpinn", ignoreCase = true) ||
       event.text.any { it.contains("pinned", ignoreCase = true) })) {
      Log.w("A11yWatcher", "Screen pinning dialog — auto-approving")
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

    Log.w("A11yWatcher", "Detected power dialog — package: $packageName class: $className")

    if (AlarmHelper.isSirenActive) {
      collapseSystemDialogs()
      return
    }

    if (getSharedPreferences("guardian_prefs", MODE_PRIVATE)
        .getBoolean("protection_active", false)) {
      Log.w("A11yWatcher", "Power-off attempt while protected — EMERGENCY!")
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
            Log.w("A11yWatcher", "Auto-clicked pinning confirm: \"$text\"")
            node.recycle()
            return
          }
          node.recycle()
        }
      }
    } catch (e: Exception) {
      Log.e("A11yWatcher", "dismissScreenPinningDialog failed", e)
    } finally {
      root?.recycle()
    }
  }

  private fun collapseSystemDialogs() {
    performGlobalAction(GLOBAL_ACTION_BACK)
    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    mainHandler.postDelayed({ reassertAlarmOverlay() }, 150)
  }

  private fun reassertAlarmOverlay() {
    val intent = Intent(this, AlarmOverlayActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    try {
      startActivity(intent)
    } catch (e: Exception) {
      Log.e("A11yWatcher", "reassertAlarmOverlay failed", e)
    }
  }

  override fun onInterrupt() {}

  override fun onKeyEvent(event: KeyEvent?): Boolean {
    if (event == null) return false
    if (!AlarmHelper.isSirenActive) return super.onKeyEvent(event)

    when (event.keyCode) {
      KeyEvent.KEYCODE_POWER -> {
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            if (event.repeatCount == 0) {
              volumeUpCounter = 0
              mainHandler.removeCallbacks(volumeUpReset)
            }
          }
        }
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        if (event.action == KeyEvent.ACTION_DOWN) {
          sendBroadcast(Intent(Constants.ACTION_ALARM_SHAKE))
        }
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        if (event.action == KeyEvent.ACTION_DOWN) {
          volumeUpCounter++
          mainHandler.removeCallbacks(volumeUpReset)
          mainHandler.postDelayed(volumeUpReset, 3000L)
          if (volumeUpCounter >= 3) {
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
