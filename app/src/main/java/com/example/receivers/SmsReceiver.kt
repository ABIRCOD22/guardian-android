package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import com.example.ui.alarm.AlarmOverlayActivity

class SmsReceiver : BroadcastReceiver() {
  companion object {
    const val TRIGGER_KEYWORD = "GUARDIAN LOCK"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

    val bundle: Bundle = intent.extras ?: return
    val pdus = bundle.get("pdus") as? Array<*> ?: return

    val messages = pdus.mapNotNull { pdu ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val format = bundle.getString("format")
        SmsMessage.createFromPdu(pdu as ByteArray, format)
      } else {
        @Suppress("DEPRECATION")
        SmsMessage.createFromPdu(pdu as ByteArray)
      }
    }

    val messageBody = messages.joinToString("") { it.messageBody ?: "" }

    if (messageBody.contains(TRIGGER_KEYWORD, ignoreCase = true)) {
      abortBroadcast()
      val alarmIntent = Intent(context, AlarmOverlayActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      context.startActivity(alarmIntent)
    }
  }
}
