package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.services.ProtectionService

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context?, intent: Intent?) {
    if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
      context?.let { ctx ->
        if (ctx.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            .getBoolean("protection_active", false)) {
          val serviceIntent = Intent(ctx, ProtectionService::class.java).apply {
            action = ProtectionService.ACTION_START
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent)
          } else {
            ctx.startService(serviceIntent)
          }
        }
      }
    }
  }
}
