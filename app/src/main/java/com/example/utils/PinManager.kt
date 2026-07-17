package com.example.utils

import android.content.Context

object PinManager {
  private const val PREFS_NAME = "guardian_prefs"
  private const val KEY_HASH = "pin_hash"
  private const val KEY_SALT = "pin_salt"

  fun savePin(context: Context, pin: String) {
    val salt = CryptoUtils.generateSalt()
    val hash = CryptoUtils.hashWithSalt(pin, salt)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .putString(KEY_HASH, hash)
      .putString(KEY_SALT, salt.joinToString(",") { it.toInt().toString() })
      .apply()
  }

  fun verifyPin(context: Context, pin: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val hash = prefs.getString(KEY_HASH, null) ?: return false
    val saltStr = prefs.getString(KEY_SALT, null) ?: return false
    val salt = saltStr.split(",").map { it.toInt().toByte() }.toByteArray()
    return CryptoUtils.verifyHash(pin, salt, hash)
  }

  fun isPinSet(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.contains(KEY_HASH)
  }
}
