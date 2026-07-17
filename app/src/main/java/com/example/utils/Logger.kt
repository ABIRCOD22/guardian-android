package com.example.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.StringWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val value: Int) {
  VERBOSE(2), DEBUG(3), INFO(4), WARN(5), ERROR(6), ASSERT(7);

  companion object {
    fun fromString(s: String): LogLevel = when (s.uppercase()) {
      "V" -> VERBOSE; "D" -> DEBUG; "I" -> INFO
      "W" -> WARN; "E" -> ERROR; "A" -> ASSERT
      else -> DEBUG
    }
  }
}

object Logger {
  private const val MAX_TAG_LENGTH = 23
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
  private val scope = CoroutineScope(Dispatchers.IO)
  private var remoteLoggingEnabled = false
  private var contextRef: Context? = null
  private var deviceName: String = ""
  private var deviceId: String = ""
  private var lastRemoteLogMs = 0L

  fun init(context: Context, enableRemote: Boolean = false) {
    contextRef = context
    remoteLoggingEnabled = enableRemote
    deviceName = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE).getString("display_name", "") ?: ""
    deviceId = FirestoreSync.getDeviceId(context)
    i("Logger", "Logger initialized, remote=$enableRemote, device=$deviceName id=$deviceId")
  }

  fun setDeviceName(name: String) { deviceName = name }

  private fun tag(prefix: String): String {
    return if (prefix.length <= MAX_TAG_LENGTH) prefix else prefix.substring(0, MAX_TAG_LENGTH)
  }

  fun v(prefix: String, msg: String, tr: Throwable? = null) { log(LogLevel.VERBOSE, prefix, msg, tr) }
  fun d(prefix: String, msg: String, tr: Throwable? = null) { log(LogLevel.DEBUG, prefix, msg, tr) }
  fun i(prefix: String, msg: String, tr: Throwable? = null) { log(LogLevel.INFO, prefix, msg, tr) }
  fun w(prefix: String, msg: String, tr: Throwable? = null) { log(LogLevel.WARN, prefix, msg, tr) }
  fun e(prefix: String, msg: String, tr: Throwable? = null) { log(LogLevel.ERROR, prefix, msg, tr) }
  fun wtf(prefix: String, msg: String, tr: Throwable? = null) { log(LogLevel.ASSERT, prefix, msg, tr) }

  private fun log(level: LogLevel, prefix: String, msg: String, tr: Throwable?) {
    val t = tag(prefix)
    val fullMsg = if (tr != null) "$msg\n${stackTraceToString(tr)}" else msg
    when (level) {
      LogLevel.VERBOSE -> Log.v(t, fullMsg)
      LogLevel.DEBUG -> Log.d(t, fullMsg)
      LogLevel.INFO -> Log.i(t, fullMsg)
      LogLevel.WARN -> Log.w(t, fullMsg)
      LogLevel.ERROR -> Log.e(t, fullMsg)
      LogLevel.ASSERT -> Log.wtf(t, fullMsg)
    }
    if (remoteLoggingEnabled && level >= LogLevel.WARN) {
      val now = System.currentTimeMillis()
      if (now - lastRemoteLogMs > 5000L) {
        lastRemoteLogMs = now
        logToFirestore(level, prefix, msg, tr)
      }
    }
  }

  private fun stackTraceToString(tr: Throwable): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    tr.printStackTrace(pw)
    pw.flush()
    return sw.toString()
  }

  private fun logToFirestore(level: LogLevel, tag: String, msg: String, tr: Throwable?) {
    val ctx = contextRef ?: return
    scope.launch {
      try {
        val data = hashMapOf(
          "level" to level.name,
          "tag" to tag,
          "message" to msg,
          "stacktrace" to (tr?.let { stackTraceToString(it) } ?: ""),
          "timestamp" to System.currentTimeMillis(),
          "deviceName" to deviceName,
          "deviceId" to deviceId,
          "appVersion" to "1.0"
        )
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
          .collection("debugLogs")
          .add(data)
      } catch (_: Exception) {}
    }
  }

  fun logLifecycle(prefix: String, msg: String) {
    i(prefix, "[LIFECYCLE] $msg")
  }

  fun logError(prefix: String, msg: String, tr: Throwable? = null) {
    e(prefix, msg, tr)
  }
}
