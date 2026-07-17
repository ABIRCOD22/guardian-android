package com.example.ui.alarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.utils.AlarmHelper
import com.example.utils.Constants
import com.example.utils.FirestoreSync
import com.example.utils.LocationHelper
import kotlinx.coroutines.*

class EmergencyAssistActivity : ComponentActivity() {

  companion object {
    const val EXTRA_MESSAGE = "emergency_message"
    const val EXTRA_TRUSTED_CONTACT = "trusted_contact"
    const val EXTRA_CONTACT_PHONE = "contact_phone"
  }

  private val assistScope = CoroutineScope(Dispatchers.Main + Job())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    window?.addFlags(
      WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
      WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
      WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
      WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
    )
    window?.decorView?.setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_FULLSCREEN or
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    )

    val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Emergency assistance requested"
    val contactName = intent?.getStringExtra(EXTRA_TRUSTED_CONTACT)
    val contactPhone = intent?.getStringExtra(EXTRA_CONTACT_PHONE)

    assistScope.launch {
      val location = LocationHelper.getCurrentLocation(this@EmergencyAssistActivity)
      FirestoreSync.reportEvent("assist_screen_opened", location)
    }

    setContent {
      EmergencyAssistContent(
        message = message,
        contactName = contactName,
        contactPhone = contactPhone,
        onCallContact = { phone -> callContact(phone) },
        onOpenMaps = { openInMaps() },
        onDismiss = { finishAssist() }
      )
    }
  }

  private fun callContact(phone: String?) {
    if (phone.isNullOrBlank()) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
      val intent = Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:$phone") }
      startActivity(intent)
      return
    }
    val intent = Intent(Intent.ACTION_CALL).apply { data = Uri.parse("tel:$phone") }
    startActivity(intent)
  }

  private fun openInMaps() {
    val uri = Uri.parse("https://www.google.com/maps")
    startActivity(Intent(Intent.ACTION_VIEW, uri))
  }

  private fun finishAssist() {
    AlarmHelper.stopSiren(this)
    assistScope.cancel()
    finishAffinity()
  }

  override fun onDestroy() {
    assistScope.cancel()
    super.onDestroy()
  }
}

@Composable
fun EmergencyAssistContent(
  message: String,
  contactName: String?,
  contactPhone: String?,
  onCallContact: (String?) -> Unit,
  onOpenMaps: () -> Unit,
  onDismiss: () -> Unit
) {
  val infiniteTransition = rememberInfiniteTransition()
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1f, targetValue = 1.08f,
    animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutCubic), RepeatMode.Reverse),
    label = "pulse"
  )
  val glowAlpha by infiniteTransition.animateFloat(
    initialValue = 0.6f, targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(600, easing = EaseInOutCubic), RepeatMode.Reverse),
    label = "glow"
  )
  val rotateDeg by infiniteTransition.animateFloat(
    initialValue = -8f, targetValue = 8f,
    animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
    label = "rotate"
  )

  val accentRed = Color(0xFFFF1744)
  val darkBg = Color(0xFF1A0000)

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(darkBg),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(
        modifier = Modifier
          .size(140.dp)
          .scale(pulseScale)
          .clip(CircleShape)
          .background(
            Brush.radialGradient(
              colors = listOf(accentRed.copy(alpha = glowAlpha), accentRed.copy(alpha = 0.2f)),
              radius = 100f
            )
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          Icons.Default.Warning,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(64.dp)
        )
      }

      Spacer(Modifier.height(28.dp))

      Text(
        "EMERGENCY MODE",
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
        color = accentRed,
        letterSpacing = 4.sp
      )

      Spacer(Modifier.height(12.dp))

      Text(
        message,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        color = Color.White.copy(alpha = 0.85f),
        textAlign = TextAlign.Center,
        lineHeight = 22.sp
      )

      Spacer(Modifier.height(8.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        Icon(
          Icons.Default.MyLocation,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.5f),
          modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
          "Location being shared with emergency contacts",
          fontSize = 11.sp,
          color = Color.White.copy(alpha = 0.5f)
        )
      }

      Spacer(Modifier.height(36.dp))

      if (contactPhone != null) {
        EmergencyActionCard(
          icon = Icons.Default.Phone,
          title = if (contactName != null) "Call $contactName" else "Call Emergency Contact",
          subtitle = contactPhone,
          color = Color(0xFF00E676)
        ) {
          onCallContact(contactPhone)
        }
        Spacer(Modifier.height(12.dp))
      }

      EmergencyActionCard(
        icon = Icons.Default.Map,
        title = "Open Google Maps",
        subtitle = "Find nearby help",
        color = Color(0xFF448AFF)
      ) {
        onOpenMaps()
      }

      Spacer(Modifier.height(12.dp))

      EmergencyActionCard(
        icon = Icons.Default.Close,
        title = "Dismiss Emergency",
        subtitle = "Stop sharing location and alerts",
        color = Color(0xFFFF1744).copy(alpha = 0.6f)
      ) {
        onDismiss()
      }
    }
  }
}

@Composable
fun EmergencyActionCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  color: Color,
  onClick: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = Color.White.copy(alpha = 0.06f)
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
      }
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
      }
      Icon(
        Icons.Default.ChevronRight,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.3f),
        modifier = Modifier.size(18.dp)
      )
    }
  }
}

private val EaseInOutCubic: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val EaseInOutSine: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
