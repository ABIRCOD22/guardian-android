package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import com.example.ui.alarm.AlarmOverlayActivity
import com.example.utils.Logger

class SmsReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "SmsReceiver"
    const val TRIGGER_KEYWORD = "GUARDIAN LOCK"
  }

  override fun onReceive(context: Context, intent: Intent) {
    Logger.i(TAG, "onReceive action=${intent.action}")
    try {
      if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

      val bundle: Bundle = intent.extras ?: run {
        Logger.w(TAG, "No extras bundle in SMS intent")
        return
      }
      val pdus = bundle.get("pdus") as? Array<*> ?: run {
        Logger.w(TAG, "No pdus in SMS bundle")
        return
      }

      val messages = pdus.mapNotNull { pdu ->
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val format = bundle.getString("format")
            SmsMessage.createFromPdu(pdu as ByteArray, format)
          } else {
            @Suppress("DEPRECATION")
            SmsMessage.createFromPdu(pdu as ByteArray)
          }
        } catch (e: Exception) {
          Logger.e(TAG, "Error parsing PDU", e)
          null
        }
      }

      val messageBody = messages.joinToString("") { it.messageBody ?: "" }
      Logger.d(TAG, "SMS received: \"$messageBody\"")

      if (messageBody.contains(TRIGGER_KEYWORD, ignoreCase = true)) {
        Logger.w(TAG, "TRIGGER_KEYWORD matched — aborting broadcast and starting alarm")
        abortBroadcast()
        val alarmIntent = Intent(context, AlarmOverlayActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(alarmIntent)
        Logger.i(TAG, "AlarmOverlayActivity started from SMS trigger")
      }
    } catch (e: Exception) {
      Logger.e(TAG, "Error processing SMS", e)
    }
  }
}
