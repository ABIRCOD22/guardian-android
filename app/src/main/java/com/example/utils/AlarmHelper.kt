package com.example.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.services.SirenService

object AlarmHelper {
  @Volatile
  var isSirenActive = false
    private set

  var isArmed by mutableStateOf(false)
  var monitoringEnabled by mutableStateOf(false)

  @Volatile
  var sirenService: SirenService? = null

  fun startSiren(context: Context) {
    isSirenActive = true
    SirenService.start(context)
  }

  fun stopSiren() {
    isSirenActive = false
    sirenService?.stopSirenNow()
    sirenService = null
  }

  fun stopSiren(context: Context) {
    isSirenActive = false
    sirenService?.stopSirenNow()
    try {
      val intent = android.content.Intent(context, SirenService::class.java).apply {
        action = Constants.ACTION_SILENCE_SIREN
      }
      context.startService(intent)
    } catch (_: Exception) {}
    sirenService = null
  }
}
