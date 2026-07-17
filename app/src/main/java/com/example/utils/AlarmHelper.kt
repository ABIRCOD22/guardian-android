package com.example.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.services.SirenService

object AlarmHelper {
  private const val TAG = "AlarmHelper"

  @Volatile
  var isSirenActive = false
    private set

  var isArmed by mutableStateOf(false)
  var monitoringEnabled by mutableStateOf(false)

  @Volatile
  var sirenService: SirenService? = null

  fun startSiren(context: Context) {
    Logger.i(TAG, "startSiren — isArmed=$isArmed monitoringEnabled=$monitoringEnabled")
    isSirenActive = true
    SirenService.start(context)
    Logger.i(TAG, "SirenService started, isSirenActive=true")
  }

  fun stopSiren() {
    Logger.i(TAG, "stopSiren (no context)")
    isSirenActive = false
    sirenService?.stopSirenNow()
    sirenService = null
    Logger.i(TAG, "Siren stopped, sirenService cleared")
  }

  fun stopSiren(context: Context) {
    Logger.i(TAG, "stopSiren (with context)")
    isSirenActive = false
    sirenService?.stopSirenNow()
    try {
      val intent = android.content.Intent(context, SirenService::class.java).apply {
        action = Constants.ACTION_SILENCE_SIREN
      }
      context.startService(intent)
      Logger.d(TAG, "ACTION_SILENCE_SIREN intent sent")
    } catch (e: Exception) {
      Logger.e(TAG, "Error sending silence siren intent", e)
    }
    sirenService = null
    Logger.i(TAG, "Siren stopped via context, sirenService cleared")
  }
}
