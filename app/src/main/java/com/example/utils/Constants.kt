package com.example.utils

object Constants {
  const val ACTION_ALARM_SHAKE = "com.example.action.ALARM_SHAKE"
  const val ACTION_ALARM_STOPPED = "com.example.action.ALARM_STOPPED"
  const val ACTION_SILENCE_SIREN = "com.example.action.SILENCE_SIREN"
  const val ACTION_EMERGENCY_TRIGGERED = "com.example.action.EMERGENCY_TRIGGERED"
  const val EXTRA_ARM_ONLY = "arm_only"
  const val EXTRA_GRACE_END_TIME = "grace_end_time"
  const val CHANNEL_PROTECTION = "guardian_protection_channel"
  const val CHANNEL_SIREN = "guardian_siren_channel"
  const val MAX_PIN_ATTEMPTS = 5
  const val PREFS_POWER_PRESS_TIME = "power_press_time"
  const val PREFS_VOL_UP_PRESS_TIME = "vol_up_press_time"
}
