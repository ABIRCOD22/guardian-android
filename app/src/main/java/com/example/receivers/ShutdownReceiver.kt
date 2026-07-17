package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.services.SirenService
import com.example.ui.alarm.AlarmOverlayActivity
import com.example.utils.AlarmHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ShutdownReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val actionType = intent.action ?: return
    if (actionType != Intent.ACTION_SHUTDOWN && actionType != "android.intent.action.QUICKBOOT_POWEROFF") return

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val wakeLock = pm?.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "Guardian:ShutdownBlock"
    )
    wakeLock?.acquire(10000)

    try {
      if (!AlarmHelper.isArmed) return
      Log.w("ShutdownReceiver", "Device shutting down while armed — EMERGENCY!")

      AlarmHelper.startSiren(context)

      val sirenIntent = Intent(context, SirenService::class.java).apply {
        action = "com.example.action.START_SIREN"
      }
      context.startForegroundService(sirenIntent)

      val alarmIntent = Intent(context, AlarmOverlayActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      context.startActivity(alarmIntent)

      val pendingResult = goAsync()
      CoroutineScope(Dispatchers.IO).launch {
        try {
          withTimeout(4000) {
            withContext(Dispatchers.Main) {
              Log.w("ShutdownReceiver", "Re-asserting alarm overlay during shutdown")
              context.startActivity(alarmIntent)
            }
          }
        } catch (e: Exception) {
          Log.e("ShutdownReceiver", "Shutdown re-assert failed", e)
        } finally {
          pendingResult.finish()
        }
      }
    } catch (e: Exception) {
      Log.e("ShutdownReceiver", "Failed to trigger alarm during shutdown", e)
    } finally {
      if (wakeLock?.isHeld == true) {
        try { wakeLock.release() } catch (_: Exception) {}
      }
    }
  }
}
