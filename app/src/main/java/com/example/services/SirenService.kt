package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.utils.AlarmHelper
import com.example.utils.Constants

class SirenService : Service() {
  private var mediaPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private val originalVolumes = IntArray(5) { 0 }
  private val volumeStreams = intArrayOf(
    AudioManager.STREAM_ALARM,
    AudioManager.STREAM_MUSIC,
    AudioManager.STREAM_SYSTEM,
    AudioManager.STREAM_RING,
    AudioManager.STREAM_NOTIFICATION
  )
  private var volumeGuardHandler: Handler? = null
  private var volumeGuardThread: HandlerThread? = null
  private var audioFocusRequest: Any? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    AlarmHelper.sirenService = this
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == Constants.ACTION_SILENCE_SIREN) {
      stopSirenInternal()
      stopSelf()
      return START_NOT_STICKY
    }

    val notification = buildNotification()
    startForeground(NOTIFICATION_ID, notification)
    startSiren()
    return START_STICKY
  }

  private fun startSiren() {
    stopSirenInternal()
    try {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
      volumeStreams.forEachIndexed { i, stream ->
        originalVolumes[i] = audioManager.getStreamVolume(stream)
        audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0)
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ALARM)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build()
          )
          .setAcceptsDelayedFocusGain(false)
          .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
      } else {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      }

      mediaPlayer = MediaPlayer.create(this, R.raw.siren).apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        isLooping = true
        start()
      }

      startVolumeGuard()
    } catch (e: Exception) {
      android.util.Log.e("SirenService", "Failed to start siren", e)
    }

    try {
      vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(
          VibrationEffect.createWaveform(longArrayOf(0, 1000, 500, 1000, 500, 1000), 0)
        )
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000), 0)
      }
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    if (AlarmHelper.sirenService === this) {
      AlarmHelper.sirenService = null
    }
    stopSirenInternal()
    try { stopForeground(true) } catch (_: Exception) {}
    super.onDestroy()
  }

  private fun startVolumeGuard() {
    volumeGuardThread = HandlerThread("SirenVolumeGuard").apply { start() }
    volumeGuardHandler = Handler(volumeGuardThread!!.looper)
    volumeGuardHandler?.post(object : Runnable {
      override fun run() {
        try {
          val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@run
          for (stream in volumeStreams) {
            val max = am.getStreamMaxVolume(stream)
            if (am.getStreamVolume(stream) < max) {
              am.setStreamVolume(stream, max, 0)
            }
          }
        } catch (_: Exception) {}
        if (mediaPlayer != null) {
          volumeGuardHandler?.postDelayed(this, 100)
        }
      }
    })
  }

  private fun stopVolumeGuard() {
    volumeGuardHandler?.removeCallbacksAndMessages(null)
    volumeGuardHandler = null
    volumeGuardThread?.quitSafely()
    volumeGuardThread = null
  }

  fun stopSirenNow() {
    stopSirenInternal()
    try { stopForeground(true) } catch (_: Exception) {}
    stopSelf()
  }

  private fun stopSirenInternal() {
    stopVolumeGuard()
    try {
      mediaPlayer?.apply {
        if (isPlaying) stop()
        release()
      }
      mediaPlayer = null
    } catch (_: Exception) {}

    try {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        audioFocusRequest?.let { request ->
          audioManager?.abandonAudioFocusRequest(request as AudioFocusRequest)
        }
      } else {
        @Suppress("DEPRECATION")
        audioManager?.abandonAudioFocus(null)
      }
      audioFocusRequest = null
    } catch (_: Exception) {}

    try {
      vibrator?.cancel()
      vibrator = null
    } catch (_: Exception) {}

    try {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      volumeStreams.forEachIndexed { i, stream ->
        audioManager.setStreamVolume(stream, originalVolumes[i], 0)
      }
    } catch (_: Exception) {}
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID, "Siren Service", NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Plays alarm siren in background"
        setSound(null, null)
      }
      val nm = getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_lock_lock)
      .setContentTitle("Siren Active")
      .setContentText("Alarm is playing")
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setSilent(true)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "sp_siren_channel"
    private const val NOTIFICATION_ID = 2001

    fun start(context: Context) {
      val intent = Intent(context, SirenService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
