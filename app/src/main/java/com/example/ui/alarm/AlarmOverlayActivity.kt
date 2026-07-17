package com.example.ui.alarm

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.PinKeypad
import com.example.utils.AlarmHelper
import com.example.utils.Constants
import com.example.utils.PinManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmOverlayActivity : ComponentActivity() {
  private var lockTaskActive = false
  private val focusHandler = Handler(Looper.getMainLooper())
  private val reassertRunnable = Runnable { bringToFrontAndLock() }
  private val lockTaskScope = CoroutineScope(Dispatchers.Main + Job())
  private var lockTaskWatchdog: Job? = null

  private var hiddenVolumeUpCounter = 0
  private val hiddenVolumeUpHandler = Handler(Looper.getMainLooper())
  private val hiddenVolumeUpReset = Runnable { hiddenVolumeUpCounter = 0 }

  private var attemptCount = 0
  private var lockoutUntil = 0L

  private var receiverRegistered = false

  private val alarmReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        Constants.ACTION_ALARM_STOPPED -> {
          if (lockTaskActive) {
            try { stopLockTask() } catch (_: Exception) {}
            lockTaskActive = false
          }
          AlarmHelper.stopSiren()
          focusHandler.removeCallbacks(reassertRunnable)
          finish()
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val armOnly = intent?.getBooleanExtra(Constants.EXTRA_ARM_ONLY, false) ?: false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }

    window?.let {
      it.addFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
        WindowManager.LayoutParams.FLAG_FULLSCREEN
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
          it.decorView?.let { decor ->
            decor.windowInsetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            decor.windowInsetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          }
        } catch (_: Exception) {}
      }
      it.decorView?.setOnSystemUiVisibilityChangeListener { visibility ->
        if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
          hideSystemUI()
        }
      }
      hideSystemUI()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.requestDismissKeyguard(this, null)
      } catch (_: Exception) {}
    }

    if (!armOnly) {
      AlarmHelper.startSiren(this)
      bringToFrontAndLock()
      startLockTaskWatchdog()
    }

    val filter = IntentFilter().apply {
      addAction(Constants.ACTION_ALARM_STOPPED)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(alarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(alarmReceiver, filter)
    }
    receiverRegistered = true

    setContent {
      AlarmLockScreenContent(
        onPinComplete = { pin -> verifyAndUnlock(pin, armOnly) },
        onCancel = { finish() }
      )
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      hideSystemUI()
    } else if (AlarmHelper.isSirenActive) {
      focusHandler.removeCallbacks(reassertRunnable)
      focusHandler.postDelayed(reassertRunnable, 50)
    }
  }

  private fun bringToFrontAndLock() {
    if (!AlarmHelper.isSirenActive) return
    try {
      startLockTask()
      lockTaskActive = true
    } catch (_: Exception) {
      lockTaskActive = false
    }
  }

  private fun startLockTaskWatchdog() {
    lockTaskWatchdog?.cancel()
    lockTaskWatchdog = lockTaskScope.launch {
      while (isActive && AlarmHelper.isSirenActive) {
        try {
          val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
          if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
              startLockTask()
              lockTaskActive = true
            } catch (_: Exception) {
              val i = Intent(this@AlarmOverlayActivity, AlarmOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
              }
              startActivity(i)
            }
          }
        } catch (_: Exception) {}
        delay(2000L)
      }
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (AlarmHelper.isSirenActive) bringToFrontAndLock()
  }

  override fun onPause() {
    super.onPause()
    if (AlarmHelper.isSirenActive && !isFinishing) {
      focusHandler.postDelayed(reassertRunnable, 50)
    }
  }

  override fun onStop() {
    super.onStop()
    if (AlarmHelper.isSirenActive && !isFinishing) {
      focusHandler.postDelayed(reassertRunnable, 50)
    }
  }

  private fun hideSystemUI() {
    window?.decorView?.systemUiVisibility = (
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
      View.SYSTEM_UI_FLAG_FULLSCREEN or
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    )
  }

  private fun verifyAndUnlock(pin: String, armOnly: Boolean = false) {
    val now = System.currentTimeMillis()
    if (now < lockoutUntil) {
      val remaining = (lockoutUntil - now) / 1000
      Toast.makeText(this, "Locked for ${remaining}s", Toast.LENGTH_SHORT).show()
      return
    }
    attemptCount++

    if (PinManager.verifyPin(this, pin)) {
      if (armOnly) {
        AlarmHelper.isArmed = true
        Toast.makeText(this, "System armed", Toast.LENGTH_SHORT).show()
      } else {
        if (lockTaskActive) {
          try { stopLockTask() } catch (_: Exception) {}
          lockTaskActive = false
        }
        AlarmHelper.stopSiren(this)
        sendBroadcast(Intent(Constants.ACTION_ALARM_STOPPED))
        Toast.makeText(this, "Deactivation Successful", Toast.LENGTH_SHORT).show()
      }
      focusHandler.removeCallbacks(reassertRunnable)
      finish()
    } else {
      if (attemptCount >= Constants.MAX_PIN_ATTEMPTS) {
        lockoutUntil = System.currentTimeMillis() + 30_000L
        attemptCount = 0
        Toast.makeText(this, "Too many attempts — locked for 30s", Toast.LENGTH_LONG).show()
        focusHandler.postDelayed({
          lockoutUntil = 0L
          Toast.makeText(this, "You can try PIN again", Toast.LENGTH_SHORT).show()
        }, 30_000L)
      } else {
        Toast.makeText(this, "Access Denied (${attemptCount}/${Constants.MAX_PIN_ATTEMPTS})", Toast.LENGTH_SHORT).show()
      }
    }
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (event?.action == KeyEvent.ACTION_DOWN && AlarmHelper.isSirenActive) {
      when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
          hiddenVolumeUpCounter++
          hiddenVolumeUpHandler.removeCallbacks(hiddenVolumeUpReset)
          hiddenVolumeUpHandler.postDelayed(hiddenVolumeUpReset, 3000L)
          if (hiddenVolumeUpCounter >= 3) {
            hiddenVolumeUpCounter = 0
            hiddenVolumeUpHandler.removeCallbacks(hiddenVolumeUpReset)
            AlarmHelper.stopSiren()
            sendBroadcast(Intent(Constants.ACTION_ALARM_STOPPED))
            Toast.makeText(this, "Siren silenced. Enter PIN.", Toast.LENGTH_LONG).show()
          }
          return true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onBackPressed() {}

  override fun onDestroy() {
    super.onDestroy()
    lockTaskWatchdog?.cancel()
    AlarmHelper.stopSiren()
    focusHandler.removeCallbacks(reassertRunnable)
    if (receiverRegistered) {
      try { unregisterReceiver(alarmReceiver) } catch (_: Exception) {}
    }
  }
}

@Composable
private fun AlarmLockScreenContent(
  onPinComplete: (String) -> Unit,
  onCancel: () -> Unit
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var pinCode by remember { mutableStateOf("") }
  var isLockedError by remember { mutableStateOf(false) }
  var isUnlockedSuccess by remember { mutableStateOf(false) }

  var currentTimeString by remember { mutableStateOf("00:00:00") }
  LaunchedEffect(Unit) {
    while (true) {
      currentTimeString = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
      delay(1000)
    }
  }

  val successBg by animateColorAsState(
    if (isUnlockedSuccess) Color(0xFF003311) else Color(0xFF050507),
    tween(500), label = "successBg"
  )
  val successGlowColor by animateColorAsState(
    if (isUnlockedSuccess) Color(0xFF49FCD9) else Color(0xFFFF5167),
    tween(500), label = "successGlow"
  )
  val topBarBg by animateColorAsState(
    if (isUnlockedSuccess) Color(0xFF49FCD9).copy(alpha = 0.2f) else Color(0xFFFF5167).copy(alpha = 0.2f),
    tween(500), label = "topBarBg"
  )
  val topBarBorder by animateColorAsState(
    if (isUnlockedSuccess) Color(0xFF49FCD9).copy(alpha = 0.5f) else Color(0xFFFF5167).copy(alpha = 0.5f),
    tween(500), label = "topBarBorder"
  )

  val shakeOffset = remember { Animatable(0f) }
  LaunchedEffect(pinCode) {
    if (pinCode.length == 4) {
      delay(300)
      if (PinManager.verifyPin(context, pinCode)) {
        isUnlockedSuccess = true
        delay(800)
        onPinComplete(pinCode)
      } else {
        isLockedError = true
        shakeOffset.animateTo(20f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
        shakeOffset.animateTo(-20f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
        shakeOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        pinCode = ""
        isLockedError = false
      }
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(successBg)) {
    val infiniteTransition = rememberInfiniteTransition(label = "WarningHeartbeat")
    val heartbeatGlow by infiniteTransition.animateFloat(
      initialValue = 0.05f, targetValue = 0.2f,
      animationSpec = infiniteRepeatable(animation = tween(1500, easing = EaseInOutSine), repeatMode = RepeatMode.Reverse),
      label = "heartbeat"
    )

    Box(
      modifier = Modifier.fillMaxSize().background(
        brush = Brush.radialGradient(
          colors = listOf<Color>(successGlowColor.copy(alpha = if (isUnlockedSuccess) 0.3f else heartbeatGlow), Color.Transparent),
          radius = 1200f
        )
      )
    )

    Column(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier.fillMaxWidth().height(32.dp)
          .background(topBarBg)
          .border(0.5.dp, topBarBorder)
          .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
      ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(
            if (isUnlockedSuccess) "!!! ACCESS GRANTED !!!" else "!!! SECURITY BREACH LOCKDOWN ACTIVE !!!",
            color = successGlowColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f)
          )
          Text(currentTimeString, color = successGlowColor, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
      }
    }

    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).offset(x = shakeOffset.value.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          drawCircle(successGlowColor.copy(alpha = 0.15f), radius = size.width / 2, style = Stroke(width = 1.dp.toPx()))
          drawCircle(successGlowColor.copy(alpha = 0.1f), radius = size.width / 2 * 0.85f, style = Stroke(width = 0.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 10f), 0f)))
        }
        Icon(
          Icons.Default.Shield, contentDescription = null,
          tint = successGlowColor,
          modifier = Modifier.size(68.dp).scale(1.1f).shadow(15.dp, CircleShape,
            ambientColor = successGlowColor,
            spotColor = successGlowColor)
        )
      }

      Spacer(Modifier.height(20.dp))
      Text(
        if (isUnlockedSuccess) "GRID ACCESSIBLE" else "DEVICE LOCKED",
        fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp,
        color = successGlowColor,
        textAlign = TextAlign.Center
      )
      Spacer(Modifier.height(14.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 10.dp)) {
        repeat(4) { idx ->
          val filled = idx < pinCode.length
          val dotColor = when {
            isUnlockedSuccess -> Color(0xFF49FCD9)
            isLockedError -> Color(0xFFFF5167)
            filled -> Color(0xFFB6C4FF)
            else -> Color(0xFF434655).copy(alpha = 0.6f)
          }
          val dotBorder = when {
            isUnlockedSuccess -> Color(0xFF49FCD9)
            isLockedError -> Color(0xFFFF5167)
            filled -> Color(0xFFB6C4FF)
            else -> Color(0xFF434655).copy(alpha = 0.4f)
          }
          Box(
            Modifier
              .size(14.dp)
              .clip(CircleShape)
              .background(dotColor)
              .border(1.5.dp, dotBorder, CircleShape)
          )
        }
      }

      if (isLockedError) {
        Spacer(Modifier.height(6.dp))
        Text("INCORRECT PIN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF5167), letterSpacing = 1.sp)
      }

      Spacer(Modifier.height(20.dp))

      PinKeypad(
        keySize = 64.dp,
        keySpacing = 10.dp,
        clearLabel = "CANCEL",
        clearColor = Color(0xFF8D90A1),
        onDigit = { if (pinCode.length < 4) pinCode += it },
        onDelete = { if (pinCode.isNotEmpty()) pinCode = pinCode.dropLast(1) },
        onClear = onCancel,
        modifier = Modifier.padding(horizontal = 8.dp)
      )

      Spacer(Modifier.height(24.dp))
    }
  }
}
