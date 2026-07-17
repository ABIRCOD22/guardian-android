package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.example.services.SirenService
import com.example.ui.alarm.AlarmOverlayActivity
import com.example.utils.AlarmHelper
import com.example.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ShutdownReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "ShutdownReceiver"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val actionType = intent.action ?: return
    Logger.w(TAG, "onReceive action=$actionType")
    if (actionType != Intent.ACTION_SHUTDOWN && actionType != "android.intent.action.QUICKBOOT_POWEROFF") return

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val wakeLock = pm?.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "Guardian:ShutdownBlock"
    )
    wakeLock?.acquire(10000)
    Logger.i(TAG, "Wake lock acquired (10s)")

    try {
      if (!AlarmHelper.isArmed) {
        Logger.i(TAG, "Device shutting down but not armed — no action")
        return
      }
      Logger.w(TAG, "Device shutting down while armed — EMERGENCY!")

      AlarmHelper.startSiren(context)
      Logger.i(TAG, "Siren started via AlarmHelper")

      val sirenIntent = Intent(context, SirenService::class.java).apply {
        action = "com.example.action.START_SIREN"
      }
      context.startForegroundService(sirenIntent)
      Logger.i(TAG, "SirenService foreground started")

      val alarmIntent = Intent(context, AlarmOverlayActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      context.startActivity(alarmIntent)
      Logger.i(TAG, "AlarmOverlayActivity started")

      val pendingResult = goAsync()
      CoroutineScope(Dispatchers.IO).launch {
        try {
          withTimeout(4000) {
            withContext(Dispatchers.Main) {
              Logger.w(TAG, "Re-asserting alarm overlay during shutdown")
              context.startActivity(alarmIntent)
            }
          }
        } catch (e: Exception) {
          Logger.e(TAG, "Shutdown re-assert failed", e)
        } finally {
          pendingResult.finish()
        }
      }
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to trigger alarm during shutdown", e)
    } finally {
      if (wakeLock?.isHeld == true) {
        try { wakeLock.release(); Logger.d(TAG, "Wake lock released") } catch (e: Exception) { Logger.e(TAG, "Error releasing wake lock", e) }
      }
    }
  }
}
