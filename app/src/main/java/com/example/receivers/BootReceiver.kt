package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.services.ProtectionService
import com.example.utils.Logger

class BootReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "BootReceiver"
  }

  override fun onReceive(context: Context?, intent: Intent?) {
    Logger.i(TAG, "onReceive action=${intent?.action}")
    if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
      context?.let { ctx ->
        val protectionActive = ctx.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
          .getBoolean("protection_active", false)
        Logger.i(TAG, "Boot completed — protectionActive=$protectionActive")
        if (protectionActive) {
          try {
            val serviceIntent = Intent(ctx, ProtectionService::class.java).apply {
              action = ProtectionService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              ctx.startForegroundService(serviceIntent)
            } else {
              ctx.startService(serviceIntent)
            }
            Logger.i(TAG, "ProtectionService started on boot")
          } catch (e: Exception) {
            Logger.e(TAG, "Failed to start ProtectionService on boot", e)
          }
        }
      } ?: Logger.w(TAG, "Boot completed but context is null")
    }
  }
}
