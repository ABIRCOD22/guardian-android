package com.example

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import com.example.receivers.SecurePhoneDeviceAdmin
import com.example.services.ProtectionService
import com.example.services.SecurePhoneAccessibilityService
import com.example.utils.AlarmHelper
import com.example.utils.Constants
import com.example.utils.FirestoreSync
import com.example.utils.LocationHelper
import com.example.utils.PermissionManager
import com.example.utils.PinManager
import com.example.ui.components.PinKeypad
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        GuardianApp()
      }
    }
  }
}

enum class GuardianScreen {
  SPLASH,
  ONBOARDING,
  LOGIN,
  PERMISSION_NOTIFICATION,
  PERMISSION_LOCATION,
  PERMISSION_OVERLAY,
  PERMISSION_SMS,
  PERMISSION_ACCESSIBILITY,
  PERMISSION_DEVICE_ADMIN,
  PERMISSION_BATTERY_OPT,
  PERMISSION_COMPLETE,
  HOME,
  PROFILE,
  SETUP_PIN
}

@Composable
fun GuardianApp() {
  var currentScreen by remember { mutableStateOf(GuardianScreen.SPLASH) }
  val context = LocalContext.current
  val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
  var setupComplete by remember { mutableStateOf(prefs.getBoolean("setup_complete", false)) }

  LaunchedEffect(Unit) {
    com.example.services.FirebaseMessagingService.refreshToken()
  }
  val toggleAlarm: () -> Unit = {
    try {
      if (AlarmHelper.isArmed) {
        AlarmHelper.isArmed = false
        prefs.edit().putBoolean("protection_active", false).apply()
        kotlinx.coroutines.MainScope().launch {
          val loc = LocationHelper.getCurrentLocation(context)
          FirestoreSync.updateAlarmStatus(context, false, loc)
        }
        android.widget.Toast.makeText(context, "System disarmed", android.widget.Toast.LENGTH_SHORT).show()
      } else {
        val intent = Intent(context, com.example.ui.alarm.AlarmOverlayActivity::class.java).apply {
          putExtra(Constants.EXTRA_ARM_ONLY, true)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
      }
    } catch (e: Exception) {
      android.widget.Toast.makeText(context, "Arm failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
  }

  LaunchedEffect(AlarmHelper.isArmed) {
    if (AlarmHelper.isArmed) {
      prefs.edit().putBoolean("protection_active", true).apply()
      val serviceIntent = Intent(context, ProtectionService::class.java).apply {
        action = ProtectionService.ACTION_START
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
      } else {
        context.startService(serviceIntent)
      }
      val loc = LocationHelper.getLastKnownLocation(context)
      FirestoreSync.updateAlarmStatus(context, true, loc)
    } else {
      prefs.edit().putBoolean("protection_active", false).apply()
      val stopIntent = Intent(context, ProtectionService::class.java).apply {
        action = ProtectionService.ACTION_STOP
      }
      context.stopService(stopIntent)
    }
  }

  // App Shared States
  var emailInput by remember { mutableStateOf("operative@guardian.net") }
  var passwordInput by remember { mutableStateOf("••••••••") }
  var biometricEnabled by remember { mutableStateOf(true) }
  var twoFactorEnabled by remember { mutableStateOf(false) }
  var alertConfigEnabled by remember { mutableStateOf(true) }
  
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507)) // Void Black
  ) {
    AnimatedContent(
      targetState = currentScreen,
      transitionSpec = {
        fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
      },
      label = "ScreenTransition"
    ) { screen ->
      when (screen) {
        GuardianScreen.SPLASH -> {
          SplashScreen(onTimeout = {
            currentScreen = if (setupComplete) GuardianScreen.HOME else GuardianScreen.ONBOARDING
          })
        }
        GuardianScreen.ONBOARDING -> {
          OnboardingScreen(
            onContinue = { currentScreen = GuardianScreen.LOGIN },
            onSkip = { currentScreen = GuardianScreen.LOGIN }
          )
        }
        GuardianScreen.LOGIN -> {
          LoginScreen(
            email = emailInput,
            password = passwordInput,
            onEmailChange = { emailInput = it },
            onPasswordChange = { passwordInput = it },
            onLoginSuccess = { currentScreen = GuardianScreen.PERMISSION_NOTIFICATION }
          )
        }
        GuardianScreen.PERMISSION_NOTIFICATION -> {
          PermissionNotificationScreen(
            onGranted = { currentScreen = GuardianScreen.PERMISSION_LOCATION },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.LOGIN }
          )
        }
        GuardianScreen.PERMISSION_LOCATION -> {
          PermissionLocationScreen(
            onGranted = { currentScreen = GuardianScreen.PERMISSION_OVERLAY },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.PERMISSION_NOTIFICATION }
          )
        }
        GuardianScreen.PERMISSION_OVERLAY -> {
          PermissionOverlayScreen(
            onGranted = { currentScreen = GuardianScreen.PERMISSION_SMS },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.PERMISSION_LOCATION }
          )
        }
        GuardianScreen.PERMISSION_SMS -> {
          PermissionSmsScreen(
            onGranted = { currentScreen = GuardianScreen.PERMISSION_ACCESSIBILITY },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.PERMISSION_OVERLAY }
          )
        }
        GuardianScreen.PERMISSION_ACCESSIBILITY -> {
          PermissionAccessibilityScreen(
            onGranted = { currentScreen = GuardianScreen.PERMISSION_DEVICE_ADMIN },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.PERMISSION_SMS }
          )
        }
        GuardianScreen.PERMISSION_DEVICE_ADMIN -> {
          PermissionDeviceAdminScreen(
            onGranted = { currentScreen = GuardianScreen.PERMISSION_BATTERY_OPT },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.PERMISSION_ACCESSIBILITY }
          )
        }
        GuardianScreen.PERMISSION_BATTERY_OPT -> {
          PermissionBatteryScreen(
            onGranted = { currentScreen = GuardianScreen.PERMISSION_COMPLETE },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.PERMISSION_DEVICE_ADMIN }
          )
        }
        GuardianScreen.PERMISSION_COMPLETE -> {
          SetupCompleteScreen(
            onContinue = {
              prefs.edit().putBoolean("setup_complete", true).apply()
              setupComplete = true
              currentScreen = GuardianScreen.HOME
            },
            onBack = { currentScreen = GuardianScreen.PERMISSION_BATTERY_OPT }
          )
        }
        GuardianScreen.HOME -> {
          HomeScreen(
            isAlarmActive = AlarmHelper.isArmed,
            onToggleAlarm = toggleAlarm,
            onNavigateToProfile = { currentScreen = GuardianScreen.PROFILE },
            onNavigateToPinSetup = { currentScreen = GuardianScreen.SETUP_PIN }
          )
        }
        GuardianScreen.PROFILE -> {
          ProfileScreen(
            biometricEnabled = biometricEnabled,
            twoFactorEnabled = twoFactorEnabled,
            alertConfigEnabled = alertConfigEnabled,
            onBiometricToggle = { biometricEnabled = it },
            onTwoFactorToggle = { twoFactorEnabled = it },
            onAlertConfigToggle = { alertConfigEnabled = it },
            onLockTriggered = toggleAlarm,
            onLogout = { currentScreen = GuardianScreen.LOGIN },
            onNavigateToHome = { currentScreen = GuardianScreen.HOME }
          )
        }
        GuardianScreen.SETUP_PIN -> {
          PermissionPinSetupScreen(
            onComplete = { currentScreen = GuardianScreen.HOME },
            onSkip = { currentScreen = GuardianScreen.HOME },
            onBack = { currentScreen = GuardianScreen.HOME }
          )
        }
      }
    }


  }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
  LaunchedEffect(Unit) {
    delay(2500)
    onTimeout()
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507)),
    contentAlignment = Alignment.Center
  ) {
    // Ambient back-glow
    Box(
      modifier = Modifier
        .size(300.dp)
        .scale(1.5f)
        .background(
          brush = Brush.radialGradient(
            colors = listOf(Color(0xFF49FCD9).copy(alpha = 0.08f), Color.Transparent),
            radius = 300f
          )
        )
    )

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      // Hexagon base vector shield
      Box(
        modifier = Modifier
          .size(160.dp)
          .padding(bottom = 20.dp),
        contentAlignment = Alignment.Center
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val width = size.width
          val height = size.height
          val center = Offset(width / 2, height / 2)
          val radius = width * 0.4f

          // Outer glowing radar circle
          drawCircle(
            color = Color(0xFF49FCD9).copy(alpha = 0.2f),
            radius = radius * 1.15f,
            style = Stroke(width = 1.dp.toPx())
          )

          // Glowing point light
          drawCircle(
            color = Color(0xFFB6C4FF).copy(alpha = 0.15f),
            radius = radius * 0.9f
          )
        }

        Icon(
          imageVector = Icons.Default.Shield,
          contentDescription = "Guardian Shield",
          modifier = Modifier
            .size(70.dp)
            .scale(1.1f),
          tint = Color(0xFF49FCD9)
        )
      }

      Text(
        text = "GUARDIAN",
        fontSize = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 8.sp,
        textAlign = TextAlign.Center,
        color = Color(0xFFB6C4FF)
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = "ANTI-THEFT INTELLIGENCE",
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
        color = Color(0xFFC3C5D8).copy(alpha = 0.6f)
      )

      Spacer(modifier = Modifier.height(48.dp))

      // Custom Shimmer Loading lines
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(120.dp)
      ) {
        val infiniteTransition = rememberInfiniteTransition(label = "Loading")
        val progress by infiniteTransition.animateFloat(
          initialValue = 0f,
          targetValue = 1f,
          animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
          ),
          label = "progress"
        )

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Color(0xFFE5E1E5).copy(alpha = 0.1f))
        ) {
          Box(
            modifier = Modifier
              .fillMaxHeight()
              .fillMaxWidth(0.4f)
              .align(Alignment.CenterStart)
              .offset(x = (120 * progress).dp - 24.dp)
              .background(Color(0xFF49FCD9))
          )
        }
        Box(
          modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(2.dp)
            .background(Color(0xFFE5E1E5).copy(alpha = 0.1f))
        ) {
          Box(
            modifier = Modifier
              .fillMaxHeight()
              .fillMaxWidth(0.3f)
              .align(Alignment.CenterStart)
              .offset(x = (96 * progress).dp - 20.dp)
              .background(Color(0xFFB6C4FF))
          )
        }
      }
    }

    Text(
      text = "v2.4.0",
      color = Color(0xFFC3C5D8).copy(alpha = 0.4f),
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 32.dp)
    )
  }
}

@Composable
fun OnboardingScreen(onContinue: () -> Unit, onSkip: () -> Unit) {
  val pagerState = rememberPagerState(pageCount = { 3 })
  val coroutineScope = rememberCoroutineScope()

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507))
      .safeDrawingPadding()
  ) {
    Box(
      modifier = Modifier
        .size(400.dp)
        .align(Alignment.Center)
        .scale(1.2f)
        .background(
          brush = Brush.radialGradient(
            colors = listOf(Color(0xFFB6C4FF).copy(alpha = 0.05f), Color.Transparent),
            radius = 400f
          )
        )
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalArrangement = Arrangement.End
    ) {
      Button(
        onClick = onSkip,
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF131316).copy(alpha = 0.5f),
          contentColor = Color(0xFFC3C5D8)
        ),
        border = BorderStroke(0.5.dp, Color(0xFF434655).copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        shape = CircleShape
      ) {
        Text("Skip", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }
    }

    HorizontalPager(
      state = pagerState,
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 24.dp, vertical = 80.dp),
      beyondViewportPageCount = 1
    ) { page ->
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
      ) {
        when (page) {
          0 -> OnboardingIllustration1()
          1 -> OnboardingIllustration2()
          2 -> OnboardingIllustration3()
        }

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(bottom = 24.dp)
        ) {
          Text(
            text = "0${page + 1} / 03",
            color = Color(0xFF49FCD9),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
          )

          Spacer(modifier = Modifier.height(16.dp))

          val (title, desc) = when (page) {
            0 -> "Defense That Never Sleeps" to "Premium military-grade protection for your mobile lifestyle."
            1 -> "Real-Time Threat Detection" to "Instant alerts via SMS and WhatsApp when a breach is detected."
            2 -> "Remote Admin Control" to "Manage and monitor your device fleet from the tactical dashboard."
            else -> "" to ""
          }

          Text(
            text = title,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = Color(0xFFE5E1E5)
          )

          Spacer(modifier = Modifier.height(12.dp))

          Text(
            text = desc,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = Color(0xFFC3C5D8).copy(alpha = 0.7f),
            modifier = Modifier.widthIn(max = 280.dp)
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            repeat(3) { idx ->
              Box(
                modifier = Modifier
                  .size(
                    width = if (idx == pagerState.currentPage) 24.dp else 5.dp,
                    height = 5.dp
                  )
                  .clip(CircleShape)
                  .background(
                    if (idx == pagerState.currentPage) Color(0xFFB6C4FF) else Color(0xFF434655)
                  )
              )
            }
          }

          Button(
            onClick = {
              if (pagerState.currentPage < 2) {
                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
              } else {
                onContinue()
              }
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFFB6C4FF).copy(alpha = 0.08f),
              contentColor = Color(0xFFB6C4FF)
            ),
            border = BorderStroke(1.dp, Color(0xFFB6C4FF).copy(alpha = 0.3f)),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                if (pagerState.currentPage == 2) "Get Started" else "Next",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
              )
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Arrow right icon",
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OnboardingIllustration1() {
  Box(
    modifier = Modifier.size(240.dp),
    contentAlignment = Alignment.Center
  ) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarOrbit")
    val angle by infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(
        animation = tween(12000, easing = LinearEasing)
      ),
      label = "angle"
    )

    Box(
      modifier = Modifier.size(140.dp),
      contentAlignment = Alignment.Center
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val sizePx = size.width
        val cx = sizePx / 2
        val cy = sizePx / 2
        val path = androidx.compose.ui.graphics.Path().apply {
          moveTo(cx, 10f)
          lineTo(sizePx - 10f, sizePx * 0.25f)
          lineTo(sizePx - 10f, sizePx * 0.75f)
          lineTo(cx, sizePx - 10f)
          lineTo(10f, sizePx * 0.75f)
          lineTo(10f, sizePx * 0.25f)
          close()
        }
        drawPath(
          path = path,
          color = Color(0xFF434655),
          style = Stroke(width = 1.dp.toPx())
        )
      }

      Icon(
        imageVector = Icons.Default.AdminPanelSettings,
        contentDescription = "Military Grade Admin icon",
        tint = Color(0xFFB6C4FF),
        modifier = Modifier.size(48.dp)
      )
    }

    Box(
      modifier = Modifier
        .size(220.dp)
        .rotate(angle)
    ) {
      Box(
        modifier = Modifier
          .size(28.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF131316))
          .border(0.5.dp, Color(0xFF49FCD9).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
          .align(Alignment.TopCenter)
          .padding(4.dp),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = "Sec icon",
          tint = Color(0xFF49FCD9),
          modifier = Modifier.size(14.dp)
        )
      }

      Box(
        modifier = Modifier
          .size(28.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF131316))
          .border(0.5.dp, Color(0xFFB6C4FF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
          .align(Alignment.BottomStart)
          .padding(4.dp),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Lock,
          contentDescription = "Encrypted icon",
          tint = Color(0xFFB6C4FF),
          modifier = Modifier.size(14.dp)
        )
      }

      Box(
        modifier = Modifier
          .size(28.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF131316))
          .border(0.5.dp, Color(0xFFFFB3B5).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
          .align(Alignment.BottomEnd)
          .padding(4.dp),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Policy,
          contentDescription = "Policy icon",
          tint = Color(0xFFFFB3B5),
          modifier = Modifier.size(14.dp)
        )
      }
    }
  }
}

@Composable
private fun OnboardingIllustration2() {
  Box(
    modifier = Modifier.size(240.dp),
    contentAlignment = Alignment.Center
  ) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulseRadar")
    val pulse by infiniteTransition.animateFloat(
      initialValue = 0.6f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(1000, easing = EaseInOutSine),
        repeatMode = RepeatMode.Reverse
      ),
      label = "pulse"
    )

    Canvas(modifier = Modifier.size(200.dp)) {
      val cx = size.width / 2
      val cy = size.height / 2
      drawCircle(
        color = Color(0xFF49FCD9).copy(alpha = 0.08f),
        radius = size.width / 2
      )
      drawCircle(
        color = Color(0xFF49FCD9).copy(alpha = 0.15f),
        radius = size.width / 2 * 0.75f,
        style = Stroke(width = 1.dp.toPx())
      )
      drawCircle(
        color = Color(0xFF49FCD9).copy(alpha = 0.25f),
        radius = size.width / 2 * 0.5f,
        style = Stroke(
          width = 1.5.dp.toPx(),
          pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 15f), 0f)
        )
      )
      drawCircle(
        color = Color(0xFF49FCD9).copy(alpha = pulse),
        radius = size.width / 2 * 0.3f * pulse,
        style = Stroke(width = 2.dp.toPx())
      )
    }
  }
}

@Composable
private fun OnboardingIllustration3() {
  Box(
    modifier = Modifier.size(240.dp),
    contentAlignment = Alignment.Center
  ) {
    val infiniteTransition = rememberInfiniteTransition(label = "AdminPulse")
    val pulse by infiniteTransition.animateFloat(
      initialValue = 0.8f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(1200, easing = EaseInOutSine),
        repeatMode = RepeatMode.Reverse
      ),
      label = "pulse"
    )

    Box(
      modifier = Modifier
        .size(160.dp)
        .scale(pulse),
      contentAlignment = Alignment.Center
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
          color = Color(0xFFB6C4FF).copy(alpha = 0.1f),
          radius = size.width / 2
        )
        drawCircle(
          color = Color(0xFFB6C4FF).copy(alpha = 0.2f),
          radius = size.width / 2 * 0.7f,
          style = Stroke(width = 1.dp.toPx())
        )
      }

      Icon(
        imageVector = Icons.Default.AdminPanelSettings,
        contentDescription = "Admin panel icon",
        tint = Color(0xFFB6C4FF),
        modifier = Modifier.size(64.dp)
      )
    }
  }
}

@Composable
fun LoginScreen(
  email: String,
  password: String,
  onEmailChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onLoginSuccess: () -> Unit
) {
  var pwdVisible by remember { mutableStateOf(false) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507))
      .safeDrawingPadding()
  ) {
    // Top pulsing background radial glow
    Box(
      modifier = Modifier
        .size(450.dp)
        .align(Alignment.TopCenter)
        .offset(y = (-150).dp)
        .background(
          brush = Brush.radialGradient(
            colors = listOf(Color(0xFF668AFF).copy(alpha = 0.06f), Color.Transparent),
            radius = 450f
          )
        )
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 24.dp)
        .verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Spacer(modifier = Modifier.height(40.dp))

      // Logo Header
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 12.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Shield,
          contentDescription = "Shield logo",
          tint = Color(0xFF49FCD9),
          modifier = Modifier.size(24.dp)
        )
        Text(
          text = "GUARDIAN",
          fontSize = 18.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 2.sp,
          color = Color(0xFF49FCD9)
        )
      }

      Text(
        text = "Welcome Back.",
        fontSize = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Color(0xFFE5E1E5)
      )

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text = "Authenticate to access the tactical grid.",
        fontSize = 14.sp,
        color = Color(0xFFC3C5D8).copy(alpha = 0.7f),
        textAlign = TextAlign.Center
      )

      Spacer(modifier = Modifier.height(40.dp))

      // Input Form Fields (M3 Filling Glass style)
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // Email Field
        OutlinedTextField(
          value = email,
          onValueChange = onEmailChange,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.Mail,
              contentDescription = "Mail icon",
              tint = Color(0xFF434655)
            )
          },
          placeholder = {
            Text(
              "Email Address",
              color = Color(0xFF8D90A1)
            )
          },
          textStyle = LocalTextStyle.current.copy(color = Color(0xFFE5E1E5)),
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF0C0E1A).copy(alpha = 0.4f),
            unfocusedContainerColor = Color(0xFF0C0E1A).copy(alpha = 0.4f),
            focusedBorderColor = Color(0xFF668AFF),
            unfocusedBorderColor = Color(0xFF434655).copy(alpha = 0.6f)
          ),
          modifier = Modifier.fillMaxWidth()
        )

        // Password Field
        OutlinedTextField(
          value = password,
          onValueChange = onPasswordChange,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = "Lock icon",
              tint = Color(0xFF434655)
            )
          },
          trailingIcon = {
            IconButton(onClick = { pwdVisible = !pwdVisible }) {
              Icon(
                imageVector = if (pwdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "Toggle password",
                tint = Color(0xFF8D90A1)
              )
            }
          },
          placeholder = {
            Text(
              "Password",
              color = Color(0xFF8D90A1)
            )
          },
          textStyle = LocalTextStyle.current.copy(color = Color(0xFFE5E1E5)),
          visualTransformation = if (pwdVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF0C0E1A).copy(alpha = 0.4f),
            unfocusedContainerColor = Color(0xFF0C0E1A).copy(alpha = 0.4f),
            focusedBorderColor = Color(0xFF668AFF),
            unfocusedBorderColor = Color(0xFF434655).copy(alpha = 0.6f)
          ),
          modifier = Modifier.fillMaxWidth()
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
      ) {
        Text(
          text = "Forgot password?",
          color = Color(0xFFB6C4FF),
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clickable { }
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      // Gradient primary button
      Button(
        onClick = onLoginSuccess,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              brush = Brush.linearGradient(
                colors = listOf(Color(0xFF3D6FFF), Color(0xFF4DFFDB))
              )
            ),
          contentAlignment = Alignment.Center
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              "SIGN IN",
              color = Color(0xFF00164F),
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp
            )
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = "Login sign icon",
              tint = Color(0xFF00164F),
              modifier = Modifier.size(16.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      // OR Divider
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .weight(1f)
            .height(1.dp)
            .background(Color(0xFF434655).copy(alpha = 0.4f))
        )
        Text(
          "OR",
          color = Color(0xFFC3C5D8).copy(alpha = 0.6f),
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 16.dp)
        )
        Box(
          modifier = Modifier
            .weight(1f)
            .height(1.dp)
            .background(Color(0xFF434655).copy(alpha = 0.4f))
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      // Google sign-in button
      Button(
        onClick = onLoginSuccess,
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF131316),
          contentColor = Color(0xFFE5E1E5)
        ),
        border = BorderStroke(0.5.dp, Color(0xFF434655)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // Custom Google G logo icon
          Icon(
            imageVector = Icons.Default.Android,
            contentDescription = "G icon",
            tint = Color(0xFFB6C4FF),
            modifier = Modifier.size(18.dp)
          )
          Text(
            "Sign in with Google",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        Text(
          "New here? ",
          color = Color(0xFFC3C5D8).copy(alpha = 0.7f),
          fontSize = 14.sp
        )
        Text(
          "Create account",
          color = Color(0xFF49FCD9),
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.clickable { }
        )
      }

      Spacer(modifier = Modifier.height(40.dp))
    }
  }
}

@Composable
fun PermissionTimeline(currentStep: Int, totalSteps: Int = 6) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    for (i in 1..totalSteps) {
      if (i < currentStep) {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFF49FCD9))
            .border(2.dp, Color(0xFF49FCD9), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "complete",
            tint = Color(0xFF00201A),
            modifier = Modifier.size(12.dp)
          )
        }
      } else if (i == currentStep) {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFF050507))
            .border(1.5.dp, Color(0xFF3D6FFF), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(Color(0xFF49FCD9))
          )
        }
      } else {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFF131316))
            .border(0.5.dp, Color(0xFF434655), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .clip(CircleShape)
              .background(Color(0xFF434655))
          )
        }
      }

      if (i < totalSteps) {
        Box(
          modifier = Modifier
            .weight(if (i < currentStep) 1f else 1.5f)
            .height(1.dp)
            .background(if (i < currentStep) Color(0xFF49FCD9) else Color(0xFF434655))
        )
      }
    }
  }
}

@Composable
fun PermissionStepLayout(
  step: Int,
  totalSteps: Int,
  icon: ImageVector,
  title: String,
  subtitle: String,
  description: String,
  whyText: String,
  permissionLabel: String,
  permissionColor: Color,
  onBack: () -> Unit,
  onSkip: () -> Unit,
  onGrant: () -> Unit,
  grantButtonText: String = "GRANT ACCESS"
) {
  var isExpanded by remember { mutableStateOf(false) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507))
      .safeDrawingPadding()
  ) {
    Box(
      modifier = Modifier
        .size(400.dp)
        .align(Alignment.TopCenter)
        .background(
          brush = Brush.radialGradient(
            colors = listOf(Color(0xFF3D6FFF).copy(alpha = 0.08f), Color.Transparent),
            radius = 400f
          )
        )
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 24.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF131316).copy(alpha = 0.6f))
            .border(0.5.dp, Color(0xFF434655), CircleShape)
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color(0xFFC3C5D8),
            modifier = Modifier.size(18.dp)
          )
        }

        Text(
          text = "Step $step of $totalSteps",
          color = Color(0xFFE5E1E5),
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.width(40.dp))
      }

      Spacer(modifier = Modifier.height(32.dp))

      PermissionTimeline(currentStep = step, totalSteps = totalSteps)

      Spacer(modifier = Modifier.height(48.dp))

      Box(
        modifier = Modifier
          .size(96.dp)
          .clip(RoundedCornerShape(24.dp))
          .background(
            brush = Brush.linearGradient(
              colors = listOf(Color(0xFF3D6FFF), Color(0xFF6B4FFF))
            )
          )
          .padding(1.dp),
        contentAlignment = Alignment.Center
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(23.dp))
            .background(Color(0xFF050507).copy(alpha = 0.8f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = "Permission icon",
            tint = Color(0xFFE5E1E5),
            modifier = Modifier.size(40.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      Text(
        text = subtitle,
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Center,
        color = Color(0xFFE5E1E5)
      )

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = description,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        color = Color(0xFFC3C5D8).copy(alpha = 0.7f),
        modifier = Modifier.widthIn(max = 280.dp)
      )

      Spacer(modifier = Modifier.height(40.dp))

      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131316).copy(alpha = 0.6f)),
        border = BorderStroke(0.5.dp, Color(0xFF434655).copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = Color(0xFFC3C5D8)
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(permissionColor)
            )
            Text(
              permissionLabel,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
              color = Color(0xFFE5E1E5)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131316).copy(alpha = 0.6f)),
        border = BorderStroke(0.5.dp, Color(0xFF434655).copy(alpha = 0.4f)),
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = !isExpanded }
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              "WHY DO WE NEED THIS?",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
              color = Color(0xFFC3C5D8)
            )

            Icon(
              imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
              contentDescription = "Expand icon",
              tint = Color(0xFF8D90A1),
              modifier = Modifier.size(20.dp)
            )
          }

          AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
          ) {
            Column {
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = whyText,
                fontSize = 13.sp,
                color = Color(0xFFC3C5D8).copy(alpha = 0.8f),
                lineHeight = 18.sp
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Button(
          onClick = onGrant,
          colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
          contentPadding = PaddingValues(),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
        ) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                brush = Brush.linearGradient(
                  colors = listOf(Color(0xFF3D6FFF), Color(0xFF4DFFDB))
                )
              ),
            contentAlignment = Alignment.Center
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "grant icon",
                tint = Color(0xFF00164F),
                modifier = Modifier.size(18.dp)
              )
              Text(
                grantButtonText,
                color = Color(0xFF00164F),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
              )
            }
          }
        }

        TextButton(
          onClick = onSkip,
          modifier = Modifier.height(48.dp)
        ) {
          Text(
            "SKIP FOR NOW",
            color = Color(0xFFC3C5D8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
          )
        }
      }
    }
  }
}

@Composable
fun PermissionNotificationScreen(
  onGranted: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  var granted by remember { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    granted = isGranted
    if (isGranted) onGranted()
  }

  PermissionStepLayout(
    step = 2,
    totalSteps = 10,
    icon = Icons.Default.NotificationsActive,
    title = "NOTIFICATIONS",
    subtitle = "PERMISSIONS REQUIRED",
    description = "Guardian relies on critical alerts to keep you informed of tactical updates and security breaches.",
    whyText = "Push notification authorization allows Guardian's hardware alarm systems, remote lockers, and geofencing systems to send background tactical alerts instantly, bypassing standard system dormancy states during critical lockouts.",
    permissionLabel = if (granted) "ACTIVE" else "PENDING",
    permissionColor = if (granted) Color(0xFF49FCD9) else Color(0xFFFF4560),
    onBack = onBack,
    onSkip = onSkip,
    onGrant = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
  )
}

@Composable
fun PermissionLocationScreen(
  onGranted: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  var granted by remember { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    granted = isGranted
    if (isGranted) onGranted()
  }

  PermissionStepLayout(
    step = 3,
    totalSteps = 10,
    icon = Icons.Default.LocationOn,
    title = "LOCATION",
    subtitle = "LOCATION ACCESS REQUIRED",
    description = "Guardian needs precise location access for geofencing, device tracking, and automated security zone alerts.",
    whyText = "Location services enable Guardian to activate geo-fences around your trusted zones, trigger alerts when your device leaves a safe perimeter, and assist in locating your device during theft or loss events.",
    permissionLabel = if (granted) "ACTIVE" else "PENDING",
    permissionColor = if (granted) Color(0xFF49FCD9) else Color(0xFFFF4560),
    onBack = onBack,
    onSkip = onSkip,
    onGrant = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
  )
}

@Composable
fun PermissionOverlayScreen(
  onGranted: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  var granted by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val overlayLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    granted = Settings.canDrawOverlays(context)
    if (granted) onGranted()
  }

  PermissionStepLayout(
    step = 4,
    totalSteps = 10,
    icon = Icons.Default.Visibility,
    title = "OVERLAY",
    subtitle = "OVERLAY PERMISSION REQUIRED",
    description = "Guardian needs overlay access to display urgent security alerts above other applications.",
    whyText = "Overlay permission allows Guardian to draw critical alarm screens and lockout notifications on top of any app, ensuring theft alerts are never missed even when the device is actively in use.",
    permissionLabel = if (granted) "ACTIVE" else "PENDING",
    permissionColor = if (granted) Color(0xFF49FCD9) else Color(0xFFFF4560),
    onBack = onBack,
    onSkip = onSkip,
    onGrant = {
      overlayLauncher.launch(
        Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${context.packageName}")
        )
      )
    }
  )
}

@Composable
fun PermissionSmsScreen(
  onGranted: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  var granted by remember { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { result ->
    granted = result.values.all { it }
    if (granted) onGranted()
  }

  PermissionStepLayout(
    step = 5,
    totalSteps = 10,
    icon = Icons.Default.Sms,
    title = "SMS GUARD",
    subtitle = "SMS GUARD REQUIRED",
    description = "Guardian monitors incoming SMS for remote lock, wipe, and locate commands from your trusted devices.",
    whyText = "SMS Guard enables Guardian to receive and execute remote commands sent via text message. This allows you to lock, locate, or wipe your device from any phone even without an internet connection.",
    permissionLabel = if (granted) "ACTIVE" else "PENDING",
    permissionColor = if (granted) Color(0xFF49FCD9) else Color(0xFFFF4560),
    onBack = onBack,
    onSkip = onSkip,
    onGrant = {
      launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
    }
  )
}

@Composable
fun PermissionAccessibilityScreen(
  onGranted: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  var granted by remember { mutableStateOf(PermissionManager.isAccessibilityServiceEnabled(context, SecurePhoneAccessibilityService::class.java)) }
  val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    granted = PermissionManager.isAccessibilityServiceEnabled(context, SecurePhoneAccessibilityService::class.java)
    if (granted) onGranted()
  }

  PermissionStepLayout(
    step = 6,
    totalSteps = 10,
    icon = Icons.Default.Settings,
    title = "ACCESSIBILITY",
    subtitle = "ACCESSIBILITY SERVICE REQUIRED",
    description = "Guardian needs accessibility access to block power-off attempts and intercept key events during alarm lockdown.",
    whyText = "The accessibility service allows Guardian to detect and dismiss the power menu, intercept the power button to prevent shutdown, and automatically approve screen pinning dialogs — preventing thieves from turning off the device or leaving the alarm screen.",
    permissionLabel = if (granted) "ACTIVE" else "PENDING",
    permissionColor = if (granted) Color(0xFF49FCD9) else Color(0xFFFF4560),
    onBack = onBack,
    onSkip = onSkip,
    onGrant = {
      launcher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
  )
}

@Composable
fun PermissionDeviceAdminScreen(
  onGranted: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  var granted by remember {
    mutableStateOf(
      try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, SecurePhoneDeviceAdmin::class.java)
        dpm.isAdminActive(component)
      } catch (_: Exception) { false }
    )
  }
  val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    granted = try {
      val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
      val component = ComponentName(context, SecurePhoneDeviceAdmin::class.java)
      dpm.isAdminActive(component)
    } catch (_: Exception) { false }
    if (granted) onGranted()
  }

  PermissionStepLayout(
    step = 7,
    totalSteps = 10,
    icon = Icons.Default.Shield,
    title = "DEVICE ADMIN",
    subtitle = "DEVICE ADMIN REQUIRED",
    description = "Guardian requires device administrator privileges to enforce lock screen policies and enable remote wipe capabilities.",
    whyText = "Device admin policies allow Guardian to force-lock the device instantly, reset the unlock password, and remotely wipe data in extreme theft scenarios — ensuring your information never falls into the wrong hands.",
    permissionLabel = if (granted) "ACTIVE" else "PENDING",
    permissionColor = if (granted) Color(0xFF49FCD9) else Color(0xFFFF4560),
    onBack = onBack,
    onSkip = onSkip,
    onGrant = {
      try {
        val component = ComponentName(context, SecurePhoneDeviceAdmin::class.java)
        launcher.launch(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
          putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
          putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Guardian needs device admin to force-lock and wipe the device remotely during theft.")
        })
      } catch (_: Exception) {}
    }
  )
}

@Composable
fun PermissionBatteryScreen(
  onGranted: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  var granted by remember {
    mutableStateOf(
      try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
      } catch (_: Exception) { false }
    )
  }
  val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    granted = try {
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) { false }
    if (granted) onGranted()
  }

  PermissionStepLayout(
    step = 8,
    totalSteps = 10,
    icon = Icons.Default.BatteryFull,
    title = "BATTERY OPT",
    subtitle = "BATTERY OPTIMIZATION REQUIRED",
    description = "Guardian must be exempt from battery optimization to remain active in the background and detect theft triggers.",
    whyText = "Battery optimization can kill Guardian's background protection service, preventing it from monitoring power disconnection, USB events, and SIM removal. Disabling optimization ensures round-the-clock theft protection.",
    permissionLabel = if (granted) "ACTIVE" else "PENDING",
    permissionColor = if (granted) Color(0xFF49FCD9) else Color(0xFFFF4560),
    onBack = onBack,
    onSkip = onSkip,
    onGrant = {
      launcher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = android.net.Uri.parse("package:${context.packageName}")
      })
    }
  )
}

@Composable
fun PermissionPinSetupScreen(
  onComplete: () -> Unit,
  onSkip: () -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  var pin by remember { mutableStateOf("") }
  var confirmPin by remember { mutableStateOf("") }
  var errorMsg by remember { mutableStateOf("") }
  val shakeOffset = remember { Animatable(0f) }
  val coroutineScope = rememberCoroutineScope()
  var stage by remember { mutableStateOf("pin") }

  Box(
    modifier = Modifier.fillMaxSize().background(Color(0xFF050507)).safeDrawingPadding()
  ) {
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF131316).copy(alpha = 0.6f)).border(0.5.dp, Color(0xFF434655), CircleShape)
        ) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFFC3C5D8), modifier = Modifier.size(18.dp))
        }
      }

      Spacer(Modifier.height(24.dp))

      Box(
        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))
          .background(Brush.linearGradient(listOf(Color(0xFF3D6FFF), Color(0xFF6B4FFF))), shape = RoundedCornerShape(20.dp)).padding(1.dp),
        contentAlignment = Alignment.Center
      ) {
        Box(
          modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(19.dp)).background(Color(0xFF050507).copy(alpha = 0.8f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFE5E1E5), modifier = Modifier.size(34.dp))
        }
      }

      Spacer(Modifier.height(20.dp))
      Text("SET YOUR GUARDIAN PIN", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Color(0xFFE5E1E5))
      Spacer(Modifier.height(6.dp))
      Text("Create a 4-digit PIN to unlock the device during alarm lockdown.", fontSize = 13.sp, color = Color(0xFFC3C5D8).copy(alpha = 0.7f), lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))

      Spacer(Modifier.height(24.dp))

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset(x = shakeOffset.value.dp)
      ) {
        Text(
          if (stage == "pin") "ENTER PIN" else "CONFIRM PIN",
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.5.sp,
          color = if (stage == "pin") Color(0xFF49FCD9) else Color(0xFFB6C4FF)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          val currentPin = if (stage == "pin") pin else confirmPin
          repeat(4) { idx ->
            val filled = idx < currentPin.length
            Box(
              Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(
                  if (filled) {
                    if (stage == "pin") Color(0xFF49FCD9) else Color(0xFFB6C4FF)
                  } else Color(0xFF434655).copy(alpha = 0.6f)
                )
                .border(
                  1.5.dp,
                  if (filled) {
                    if (stage == "pin") Color(0xFF49FCD9) else Color(0xFFB6C4FF)
                  } else Color(0xFF434655).copy(alpha = 0.4f),
                  CircleShape
                )
            )
          }
        }
      }

      if (errorMsg.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5167), modifier = Modifier.size(14.dp))
          Text(errorMsg, fontSize = 12.sp, color = Color(0xFFFF5167), fontWeight = FontWeight.SemiBold)
        }
      }

      Spacer(Modifier.height(16.dp))

      PinKeypad(
        keySize = 64.dp,
        keySpacing = 10.dp,
        onDigit = { key ->
          errorMsg = ""
          when (stage) {
            "pin" -> {
              if (pin.length < 4) {
                pin += key
                if (pin.length == 4) stage = "confirm"
              }
            }
            "confirm" -> {
              if (confirmPin.length < 4) {
                confirmPin += key
                if (confirmPin.length == 4) {
                  if (pin == confirmPin) {
                    PinManager.savePin(context, pin)
                    onComplete()
                  } else {
                    errorMsg = "PINs do not match"
                    pin = ""; confirmPin = ""; stage = "pin"
                    coroutineScope.launch {
                      shakeOffset.animateTo(12f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium))
                      shakeOffset.animateTo(-12f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium))
                      shakeOffset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                    }
                  }
                }
              }
            }
          }
        },
        onDelete = {
          if (stage == "confirm" && confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
          else if (stage == "pin" && pin.isNotEmpty()) pin = pin.dropLast(1)
        },
        onClear = { pin = ""; confirmPin = ""; errorMsg = ""; stage = "pin" },
        modifier = Modifier.padding(horizontal = 8.dp)
      )

      Spacer(Modifier.height(12.dp))
      TextButton(onClick = onSkip, modifier = Modifier.height(44.dp)) {
        Text("SKIP FOR NOW", color = Color(0xFFC3C5D8), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
      }
    }
  }
}

@Composable
fun SetupCompleteScreen(
  onContinue: () -> Unit,
  onBack: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507))
      .safeDrawingPadding()
  ) {
    Box(
      modifier = Modifier
        .size(400.dp)
        .align(Alignment.TopCenter)
        .background(
          brush = Brush.radialGradient(
            colors = listOf(Color(0xFF3D6FFF).copy(alpha = 0.12f), Color.Transparent),
            radius = 400f
          )
        )
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 24.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF131316).copy(alpha = 0.6f))
            .border(0.5.dp, Color(0xFF434655), CircleShape)
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color(0xFFC3C5D8),
            modifier = Modifier.size(18.dp)
          )
        }

        Text(
          text = "Step 10 of 10",
          color = Color(0xFFE5E1E5),
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.width(40.dp))
      }

      Spacer(modifier = Modifier.height(32.dp))

      PermissionTimeline(currentStep = 10, totalSteps = 10)

      Spacer(modifier = Modifier.height(48.dp))

      Box(
        modifier = Modifier
          .size(120.dp)
          .clip(CircleShape)
          .background(Color(0xFF49FCD9).copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
      ) {
        Box(
          modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(Color(0xFF49FCD9).copy(alpha = 0.3f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Setup complete",
            tint = Color(0xFF49FCD9),
            modifier = Modifier.size(48.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      Text(
        text = "SETUP COMPLETE",
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Center,
        color = Color(0xFFE5E1E5)
      )

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = "Guardian is now configured and ready.\nAll critical permissions have been granted.",
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        color = Color(0xFFC3C5D8).copy(alpha = 0.7f),
        modifier = Modifier.widthIn(max = 280.dp)
      )

      Spacer(modifier = Modifier.height(48.dp))

      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131316).copy(alpha = 0.6f)),
        border = BorderStroke(0.5.dp, Color(0xFF49FCD9).copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "shield",
            tint = Color(0xFF49FCD9),
            modifier = Modifier.size(24.dp)
          )
          Column {
            Text(
              "ALL SYSTEMS NOMINAL",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
              color = Color(0xFF49FCD9)
            )
            Text(
              "Your device is under Guardian protection.",
              fontSize = 12.sp,
              color = Color(0xFFC3C5D8).copy(alpha = 0.7f)
            )
          }
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      Button(
        onClick = onContinue,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              brush = Brush.linearGradient(
                colors = listOf(Color(0xFF3D6FFF), Color(0xFF4DFFDB))
              )
            ),
          contentAlignment = Alignment.Center
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = "enter icon",
              tint = Color(0xFF00164F),
              modifier = Modifier.size(18.dp)
            )
            Text(
              "ENTER GUARDIAN",
              color = Color(0xFF00164F),
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp
            )
          }
        }
      }
    }
  }
}

@Composable
fun HomeScreen(
  isAlarmActive: Boolean = false,
  onToggleAlarm: () -> Unit,
  onNavigateToProfile: () -> Unit,
  onNavigateToPinSetup: () -> Unit = {}
) {
  val context = LocalContext.current
  var statsTabSelected by remember { mutableIntStateOf(0) }

  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507)),
    containerColor = Color(0xFF050507),
    bottomBar = {
      GuardianBottomNavBar(
        activeTab = if (statsTabSelected == 0) 0 else 1,
        onHomeClick = { statsTabSelected = 0 },
        onFeaturesClick = { statsTabSelected = if (statsTabSelected == 1) 0 else 1 },
        onProfileClick = onNavigateToProfile
      )
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 20.dp)
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Profile and Header active bar
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            // Operator profile headshot avatar with green ring
            Box(
              modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF131316))
                .border(2.dp, Color(0xFF49FCD9), CircleShape)
            ) {
              AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                  .data("https://lh3.googleusercontent.com/aida-public/AB6AXuDiohrRrcgGK62Dnpjiv30jbTUfSbEhAvxzZVtVOuJ7MViR2-9vki2cxjzjvOdLf2-uU2HR-tV-38JYk6SCct9bEjXTL8k_RCxxMVgSP7T919_jwBYSYv7Z2SG1rgKDyEFA7ib4u--buIoDs8SH6lFNJWk2_ooowX-4nPUQvBHbwq1lhs1N2eu0Sdw1qEbPywJiQoy503ElMdUny03t39l-V-gQxoF7h76iVcE5GPafQZLolwtiJb2UOA")
                  .crossfade(true)
                  .build(),
                contentDescription = "Operator Avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
              )
            }

            Column {
              Text(
                "Good evening, John",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFE5E1E5)
              )
              Text(
                "OPERATOR ACTIVE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = Color(0xFF49FCD9)
              )
            }
          }

          // Bell button alert
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(Color(0xFF201F22).copy(alpha = 0.6f))
              .border(0.5.dp, Color(0xFF434655).copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Outlined.Notifications,
              contentDescription = "Alert notification bell",
              tint = Color(0xFFE5E1E5),
              modifier = Modifier.size(20.dp)
            )
            // Glowing red pulse point
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF5167))
                .align(Alignment.TopEnd)
                .offset(x = (-4).dp, y = 4.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(30.dp))

        if (!PinManager.isPinSet(context)) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(Color(0xFF1A1A2E).copy(alpha = 0.85f))
              .border(0.5.dp, Color(0xFF3D6FFF).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
              .clickable(onClick = onNavigateToPinSetup)
              .padding(16.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3D6FFF).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF3D6FFF), modifier = Modifier.size(18.dp))
              }
              Spacer(Modifier.width(14.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Set a Guardian PIN", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE5E1E5))
                Spacer(Modifier.height(2.dp))
                Text("Protect your device during alarm lockdown", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC3C5D8).copy(alpha = 0.7f))
              }
              Spacer(Modifier.width(8.dp))
              Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF8D90A1), modifier = Modifier.size(20.dp))
            }
          }
          Spacer(modifier = Modifier.height(20.dp))
        }

        if (statsTabSelected == 1) {
          Text("GUARDIAN FEATURES", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Color(0xFFE5E1E5), modifier = Modifier.fillMaxWidth())
          Spacer(Modifier.height(16.dp))
          FeatureCard(
            Icons.Default.Lock, "Alarm Lockdown",
            "Full-screen lock with siren, PIN unlock, and lock-task mode that prevents exiting.",
            Color(0xFFFF5167),
            onClick = onToggleAlarm
          )
          Spacer(Modifier.height(10.dp))
          FeatureCard(
            Icons.Default.Sms, "SMS Trigger",
            "Send \"GUARDIAN LOCK\" from any phone to remotely trigger the alarm and lock the device.",
            Color(0xFF3D6FFF),
            onClick = {
              val smsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:")).putExtra("sms_body", "GUARDIAN LOCK")
              context.startActivity(smsIntent)
            }
          )
          Spacer(Modifier.height(10.dp))
          FeatureCard(
            Icons.Default.LocationOn, "Protection Monitoring",
            "Detects charger unplug, USB connection, and SIM card removal — triggers alarm.",
            Color(0xFF49FCD9),
            onClick = {
              android.widget.Toast.makeText(context, if (AlarmHelper.isArmed) "Protection active" else "Arm the system first", android.widget.Toast.LENGTH_SHORT).show()
            }
          )
          Spacer(Modifier.height(10.dp))
          FeatureCard(
            Icons.Default.Shield, "Device Admin",
            "Force lock, reset PIN, and wipe data remotely. Prevents thief from disabling security.",
            Color(0xFFB6C4FF),
            onClick = {
              val adminIntent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, android.content.ComponentName(context, com.example.receivers.SecurePhoneDeviceAdmin::class.java))
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Guardian needs device admin to lock, wipe, and reset PIN during theft.")
              }
              context.startActivity(adminIntent)
            }
          )
          Spacer(Modifier.height(10.dp))
          @Suppress("DEPRECATION") FeatureCard(
            Icons.Filled.VolumeUp, "Siren Alarm",
            "Max-volume siren with volume guard and continuous vibration.",
            Color(0xFFFF5167),
            onClick = {
              AlarmHelper.startSiren(context)
              android.widget.Toast.makeText(context, "Siren test — tap again to stop", android.widget.Toast.LENGTH_SHORT).show()
            }
          )
          Spacer(Modifier.height(10.dp))
          FeatureCard(
            Icons.Default.Visibility, "Power Button Block",
            "Accessibility service intercepts the power button and dismisses the power menu.",
            Color(0xFF3D6FFF),
            onClick = {
              val a11yIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
              context.startActivity(a11yIntent)
            }
          )
          Spacer(Modifier.height(10.dp))
          FeatureCard(
            Icons.Default.LocationOn, "Location Service",
            "Enable GPS for device tracking and recovery features.",
            Color(0xFF49FCD9),
            onClick = {
              context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
          )
          Spacer(Modifier.height(30.dp))
        }

        if (statsTabSelected == 0) {
        // Center HUD Circular animated system armed visualizer
        Box(
          modifier = Modifier
            .size(240.dp)
            .padding(10.dp),
          contentAlignment = Alignment.Center
        ) {
          val infiniteTransition = rememberInfiniteTransition(label = "RadarHUD")
          
          // Outer circle rotating slowly clockwise
          val outerAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(24000, easing = LinearEasing)),
            label = "outer"
          )
          
          // Inner dashed circle rotating counter-clockwise
          val innerAngle by infiniteTransition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(16000, easing = LinearEasing)),
            label = "inner"
          )

          // Background glowing radar wave effect
          val glowScale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
              animation = tween(2000, easing = EaseInOutSine),
              repeatMode = RepeatMode.Reverse
            ),
            label = "glow"
          )

          // Outer rotating ring
          Canvas(
            modifier = Modifier
              .fillMaxSize()
              .scale(glowScale)
              .rotate(outerAngle)
          ) {
            drawCircle(
              color = Color(0xFF49FCD9).copy(alpha = 0.12f),
              radius = size.width / 2,
              style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
              color = Color(0xFF49FCD9).copy(alpha = 0.25f),
              radius = size.width / 2 * 0.95f,
              style = Stroke(width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 25f), 0f))
            )
          }

          // Inner backward rotating ring
          Canvas(
            modifier = Modifier
              .fillMaxSize(0.85f)
              .rotate(innerAngle)
          ) {
            drawCircle(
              color = Color(0xFFB6C4FF).copy(alpha = 0.15f),
              radius = size.width / 2,
              style = Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 12f), 0f))
            )
          }

          // Core System toggle button
          val armedColor = Color(0xFF49FCD9)
          val disarmedColor = Color(0xFFFF5167)
          val activeColor = if (isAlarmActive) armedColor else disarmedColor

          Button(
            onClick = onToggleAlarm,
            colors = ButtonDefaults.buttonColors(
              containerColor = if (isAlarmActive) Color(0xFF0E6B3D).copy(alpha = 0.3f) else Color(0xFF330C0C).copy(alpha = 0.4f),
              contentColor = activeColor
            ),
            border = BorderStroke(1.dp, activeColor.copy(alpha = if (isAlarmActive) 0.7f else 0.4f)),
            shape = CircleShape,
            modifier = Modifier
              .size(130.dp)
              .scale(if (isAlarmActive) 1f else glowScale)
              .shadow(
                elevation = if (isAlarmActive) 25.dp else 10.dp,
                shape = CircleShape,
                ambientColor = activeColor.copy(alpha = 0.6f),
                spotColor = activeColor.copy(alpha = 0.4f)
              ),
            contentPadding = PaddingValues()
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = if (isAlarmActive) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                contentDescription = "Toggle alarm",
                tint = activeColor,
                modifier = Modifier.size(36.dp)
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                if (isAlarmActive) "SYSTEM ARMED" else "DISARMED",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = activeColor
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Bento Stats Grid Row (3 cards)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          // Card 1: Status
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(12.dp))
              .background(Color(0xFF131316).copy(alpha = 0.6f))
              .border(0.5.dp, Color(0xFF434655).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
              .padding(vertical = 12.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = if (isAlarmActive) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                contentDescription = "Status icon",
                tint = if (isAlarmActive) Color(0xFF49FCD9) else Color(0xFFFF5167),
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                if (isAlarmActive) "ARMED" else "OFF",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFE5E1E5)
              )
              Text(
                "STATUS",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = Color(0xFFC3C5D8).copy(alpha = 0.6f)
              )
            }
          }

          // Card 2: Alerts
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(12.dp))
              .background(Color(0xFF131316).copy(alpha = 0.6f))
              .border(0.5.dp, Color(0xFF434655).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
              .padding(vertical = 12.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Alerts warning",
                tint = if (AlarmHelper.isSirenActive) Color(0xFFFF5167) else Color(0xFF434655).copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                if (AlarmHelper.isSirenActive) "ACTIVE" else "0",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFE5E1E5)
              )
              Text(
                "ALERTS",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = Color(0xFFC3C5D8).copy(alpha = 0.6f)
              )
            }
          }

          // Card 3: Monitoring (clickable toggle — stays OFF by default)
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(12.dp))
              .background(Color(0xFF131316).copy(alpha = 0.6f))
              .border(0.5.dp, Color(0xFF434655).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
              .clickable { AlarmHelper.monitoringEnabled = !AlarmHelper.monitoringEnabled }
              .padding(vertical = 12.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "Monitoring active",
                tint = if (AlarmHelper.monitoringEnabled) Color(0xFF49FCD9) else Color(0xFF434655).copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                if (AlarmHelper.monitoringEnabled) "ON" else "OFF",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFE5E1E5)
              )
              Text(
                "MONITOR",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = Color(0xFFC3C5D8).copy(alpha = 0.6f)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Safe Zone interactive card with custom Canvas drawing representing high-end satellite HUD
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF131316).copy(alpha = 0.6f)),
          border = BorderStroke(0.5.dp, Color(0xFF434655).copy(alpha = 0.3f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                "Safe Zone",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFE5E1E5)
              )

              Box(
                modifier = Modifier
                  .clip(CircleShape)
                  .background(Color(0xFF49FCD9).copy(alpha = 0.1f))
                  .border(0.5.dp, Color(0xFF49FCD9).copy(alpha = 0.4f), CircleShape)
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Box(
                    modifier = Modifier
                      .size(6.dp)
                      .clip(CircleShape)
                      .background(Color(0xFF49FCD9))
                  )
                  Text(
                    "ACTIVE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF49FCD9)
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Radar HUD Canvas
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0E0E11))
                .border(0.5.dp, Color(0xFF434655).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            ) {
              // Radar coordinate grid crosshairs
              Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                // Draw coordinate tracking crosshair lines
                drawLine(
                  color = Color(0xFF49FCD9).copy(alpha = 0.15f),
                  start = Offset(width / 2, 0f),
                  end = Offset(width / 2, height),
                  strokeWidth = 0.5.dp.toPx()
                )
                drawLine(
                  color = Color(0xFF49FCD9).copy(alpha = 0.15f),
                  start = Offset(0f, height / 2),
                  end = Offset(width, height / 2),
                  strokeWidth = 0.5.dp.toPx()
                )

                // Safe Zone concentric boundary circles
                drawCircle(
                  color = Color(0xFF49FCD9).copy(alpha = 0.3f),
                  radius = 45.dp.toPx(),
                  center = Offset(width / 2, height / 2),
                  style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                  )
                )

                // Coordinate points
                drawCircle(
                  color = Color(0xFF49FCD9),
                  radius = 4.dp.toPx(),
                  center = Offset(width / 2, height / 2)
                )
              }

              // Pulse waves concentric from core coordinate
              val infiniteTransition = rememberInfiniteTransition(label = "MapPulse")
              val pulseSize by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 60f,
                animationSpec = infiniteRepeatable(
                  animation = tween(2000, easing = EaseOutQuad),
                  repeatMode = RepeatMode.Restart
                ),
                label = "pulse"
              )

              Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                drawCircle(
                  color = Color(0xFF49FCD9).copy(alpha = 0.35f * (1f - pulseSize / 60f)),
                  radius = pulseSize.dp.toPx(),
                  center = Offset(cx, cy),
                  style = Stroke(width = 1.5.dp.toPx())
                )
              }

              // Glowing center Location Pin
              Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "location pointer",
                tint = Color(0xFF49FCD9),
                modifier = Modifier
                  .size(24.dp)
                  .align(Alignment.Center)
                  .offset(y = (-6).dp)
                  .shadow(elevation = 10.dp, shape = CircleShape)
              )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CTA Ghost outline button
            OutlinedButton(
              onClick = onToggleAlarm,
              border = BorderStroke(0.5.dp, Color(0xFF434655)),
              colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFF0E0E11).copy(alpha = 0.5f),
                contentColor = Color(0xFFE5E1E5)
              ),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.EditLocation,
                  contentDescription = "Edit location pointer",
                  tint = Color(0xFFE5E1E5),
                  modifier = Modifier.size(16.dp)
                )
                Text(
                  "UPDATE SAFE LOCATION",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.sp
                )
              }
            }
          }
        }

        } // end if statsTabSelected == 0

        Spacer(modifier = Modifier.height(40.dp))
      }
    }
  }
}

@Composable
fun ProfileScreen(
  biometricEnabled: Boolean,
  twoFactorEnabled: Boolean,
  alertConfigEnabled: Boolean,
  onBiometricToggle: (Boolean) -> Unit,
  onTwoFactorToggle: (Boolean) -> Unit,
  onAlertConfigToggle: (Boolean) -> Unit,
  onLockTriggered: () -> Unit,
  onLogout: () -> Unit,
  onNavigateToHome: () -> Unit
) {
  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF050507)),
    containerColor = Color(0xFF050507),
    bottomBar = {
      GuardianBottomNavBar(
        activeTab = 2,
        onHomeClick = onNavigateToHome,
        onProfileClick = { /* Already here */ }
      )
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 20.dp)
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Top brand title bar
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = "Guardian logo icon",
              tint = Color(0xFFB6C4FF),
              modifier = Modifier.size(20.dp)
            )
            Text(
              "GUARDIAN",
              fontSize = 18.sp,
              fontWeight = FontWeight.Black,
              letterSpacing = 2.sp,
              color = Color(0xFFB6C4FF)
            )
          }

          IconButton(
            onClick = { },
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(Color(0xFF131316).copy(alpha = 0.6f))
              .border(0.5.dp, Color(0xFF434655), CircleShape)
          ) {
            Icon(
              imageVector = Icons.Default.Notifications,
              contentDescription = "Alert notifications config",
              tint = Color(0xFFE5E1E5),
              modifier = Modifier.size(18.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Profile Avatar with rotating border
        Box(
          modifier = Modifier.size(90.dp),
          contentAlignment = Alignment.Center
        ) {
          val infiniteTransition = rememberInfiniteTransition(label = "AvatarBorder")
          val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing)),
            label = "angle"
          )

          // Glowing spinning gradient mask
          Box(
            modifier = Modifier
              .size(86.dp)
              .rotate(angle)
              .clip(CircleShape)
              .background(
                brush = Brush.sweepGradient(
                  colors = listOf(Color(0xFF49FCD9), Color(0xFF3D6FFF), Color(0xFF49FCD9))
                )
              )
          )

          // Image Mask
          Box(
            modifier = Modifier
              .size(80.dp)
              .clip(CircleShape)
              .background(Color(0xFF050507))
              .border(2.dp, Color(0xFF050507), CircleShape)
          ) {
            AsyncImage(
              model = ImageRequest.Builder(LocalContext.current)
                .data("https://lh3.googleusercontent.com/aida-public/AB6AXuCNiw349BN_OaEpO2ZDpHHydAbRSJYnQwDjZPhulXt_Gem9CO7miqqVPZ84Jy1ob6fWsZG_YpnK2Yu6kfoqVPrtsdB64-JFPxCxQFJQMWxbYbwVHb4gEVLVMJGuyfJyPIc8nZ486KBpflTTyzClFsoqzIZc1x6lZxvVa03Hu6O1RyXwKnVLeAaqkmidLiObNVW8z6-p7qm1MygBwweH_pNCifmBXCQXP1H7A4IShsY9tL_mIBoVmkKADQ")
                .crossfade(true)
                .build(),
              contentDescription = "Tactical Profile Photo",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize()
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          "ALEX VANCE",
          fontSize = 28.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 1.sp,
          color = Color(0xFFE5E1E5)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          "OPERATIVE ID: 893-X-11",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.5.sp,
          color = Color(0xFFC3C5D8).copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Interactive Badges (Pro / Verified)
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .clip(CircleShape)
              .background(
                brush = Brush.linearGradient(
                  colors = listOf(Color(0xFFFF5167), Color(0xFFFFB3B5))
                )
              )
              .padding(horizontal = 14.dp, vertical = 5.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "pro active badge star",
                tint = Color(0xFF5B0015),
                modifier = Modifier.size(12.dp)
              )
              Text(
                "PRO",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF5B0015)
              )
            }
          }

          Box(
            modifier = Modifier
              .clip(CircleShape)
              .border(0.5.dp, Color(0xFF49FCD9).copy(alpha = 0.5f), CircleShape)
              .background(Color(0xFF49FCD9).copy(alpha = 0.05f))
              .padding(horizontal = 14.dp, vertical = 5.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = "verified badge",
                tint = Color(0xFF49FCD9),
                modifier = Modifier.size(12.dp)
              )
              Text(
                "VERIFIED",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF49FCD9)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Security settings protocols Card List
        SettingsSectionHeader("SECURITY PROTOCOLS")
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF131316).copy(alpha = 0.6f)),
          border = BorderStroke(0.5.dp, Color(0xFF434655).copy(alpha = 0.3f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column {
            // Row 1: Biometrics
            SettingsToggleRow(
              icon = Icons.Default.Fingerprint,
              title = "Biometric Authentication",
              isChecked = biometricEnabled,
              onCheckedChange = onBiometricToggle
            )
            Divider(color = Color(0xFF434655).copy(alpha = 0.2f), thickness = 0.5.dp)
            // Row 2: 2FA
            SettingsValueRow(
              icon = Icons.Default.PhonelinkLock,
              title = "Two-Factor Auth",
              value = "OFF",
              onClick = { onTwoFactorToggle(!twoFactorEnabled) }
            )
            Divider(color = Color(0xFF434655).copy(alpha = 0.2f), thickness = 0.5.dp)
            // Row 3: Active Sessions
            SettingsValueRow(
              icon = Icons.Default.Devices,
              title = "Active Sessions",
              value = "3 DEVICES",
              onClick = { }
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Preferences header
        SettingsSectionHeader("SYSTEM PREFERENCES")
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF131316).copy(alpha = 0.6f)),
          border = BorderStroke(0.5.dp, Color(0xFF434655).copy(alpha = 0.3f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column {
            SettingsToggleRow(
              icon = Icons.Default.NotificationsActive,
              title = "Alert Configuration",
              isChecked = alertConfigEnabled,
              onCheckedChange = onAlertConfigToggle
            )
            Divider(color = Color(0xFF434655).copy(alpha = 0.2f), thickness = 0.5.dp)
            SettingsValueRow(
              icon = Icons.Default.Language,
              title = "Region & Language",
              value = "EN-US",
              onClick = { }
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Recent Logs Section
        SettingsSectionHeader("RECENT LOGS")
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          LogItemRow(
            isAuthorized = true,
            title = "System Login Authorized",
            node = "NODE: OMEGA-4",
            time = "08:42 AM"
          )

          LogItemRow(
            isAuthorized = false,
            title = "Authentication Failure",
            node = "NODE: EXTERNAL-IP",
            time = "YESTERDAY"
          )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Disconnect logout button
        OutlinedButton(
          onClick = onLogout,
          border = BorderStroke(1.dp, Color(0xFFFF5167).copy(alpha = 0.5f)),
          colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFFFF5167).copy(alpha = 0.05f),
            contentColor = Color(0xFFFF5167)
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Logout,
              contentDescription = "Logout icon",
              modifier = Modifier.size(18.dp)
            )
            Text(
              "DISCONNECT",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.5.sp
            )
          }
        }

        Spacer(modifier = Modifier.height(30.dp))
      }
    }
  }
}

@Composable
fun SettingsSectionHeader(text: String) {
  Text(
    text = text,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 2.sp,
    color = Color(0xFFB6C4FF),
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 4.dp, bottom = 8.dp)
  )
}

@Composable
fun SettingsToggleRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  isChecked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF201F22)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = Color(0xFFC3C5D8).copy(alpha = 0.8f),
          modifier = Modifier.size(18.dp)
        )
      }
      Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFFE5E1E5)
      )
    }

    Switch(
      checked = isChecked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = Color(0xFF0E0E11),
        checkedTrackColor = Color(0xFF49FCD9),
        uncheckedThumbColor = Color(0xFF8D90A1),
        uncheckedTrackColor = Color(0xFF201F22)
      )
    )
  }
}

@Composable
fun SettingsValueRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  value: String = "",
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onClick() }
      .padding(horizontal = 16.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier.weight(1f)
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF201F22)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = Color(0xFFC3C5D8).copy(alpha = 0.8f),
          modifier = Modifier.size(18.dp)
        )
      }
      Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFFE5E1E5)
      )
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      if (value.isNotEmpty()) {
        Text(
          text = value,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
          color = if (value == "OFF") Color(0xFFC3C5D8).copy(alpha = 0.5f) else Color(0xFF49FCD9)
        )
      }
      Icon(
        imageVector = Icons.Default.ChevronRight,
        contentDescription = "Forward menu navigation",
        tint = Color(0xFF8D90A1),
        modifier = Modifier.size(18.dp)
      )
    }
  }
}

@Composable
fun LogItemRow(
  isAuthorized: Boolean,
  title: String,
  node: String,
  time: String
) {
  Card(
    shape = RoundedCornerShape(10.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF131316).copy(alpha = 0.6f)),
    border = BorderStroke(0.5.dp, Color(0xFF434655).copy(alpha = 0.2f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // Glowing status node dot
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (isAuthorized) Color(0xFF49FCD9) else Color(0xFFFF5167))
            .shadow(
              elevation = 4.dp,
              shape = CircleShape,
              ambientColor = if (isAuthorized) Color(0xFF49FCD9) else Color(0xFFFF5167),
              spotColor = if (isAuthorized) Color(0xFF49FCD9) else Color(0xFFFF5167)
            )
        )

        Column {
          Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE5E1E5)
          )
          Text(
            text = node,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = Color(0xFFC3C5D8).copy(alpha = 0.4f)
          )
        }
      }

      Text(
        text = time,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = Color(0xFF8D90A1)
      )
    }
  }
}

@Composable
fun GuardianBottomNavBar(
  activeTab: Int,
  onHomeClick: () -> Unit,
  onFeaturesClick: () -> Unit = {},
  onProfileClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(80.dp)
      .background(Color(0xFF201F22).copy(alpha = 0.6f))
      .border(0.5.dp, Color.White.copy(alpha = 0.1f))
      .padding(horizontal = 16.dp, vertical = 8.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxSize(),
      horizontalArrangement = Arrangement.SpaceAround,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Home Tab button
      NavBarItem(
        icon = Icons.Default.Shield,
        label = "HOME",
        isActive = activeTab == 0,
        onClick = onHomeClick
      )

      // Features Tab button
      NavBarItem(
        icon = Icons.Default.GridView,
        label = "FEATURES",
        isActive = activeTab == 1,
        onClick = onFeaturesClick
      )

      // Profile Tab button
      NavBarItem(
        icon = Icons.Default.Person,
        label = "PROFILE",
        isActive = activeTab == 2,
        onClick = onProfileClick
      )
    }
  }
}

@Composable
fun NavBarItem(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  isActive: Boolean,
  onClick: () -> Unit
) {
  val contentColor = if (isActive) Color(0xFF49FCD9) else Color(0xFFC3C5D8).copy(alpha = 0.6f)
  
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
      )
      .padding(horizontal = 16.dp, vertical = 6.dp)
  ) {
    if (isActive) {
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFF49FCD9).copy(alpha = 0.15f))
          .border(0.5.dp, Color(0xFF49FCD9).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
          .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color(0xFF49FCD9),
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF49FCD9),
            letterSpacing = 0.5.sp
          )
        }
      }
    } else {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = contentColor,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = label,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = contentColor,
        letterSpacing = 0.5.sp
      )
    }
  }
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, desc: String, color: Color, onClick: () -> Unit = {}) {
  Box(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
      .background(Color(0xFF131316).copy(alpha = 0.6f))
      .border(0.5.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(14.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
      }
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE5E1E5))
        Spacer(Modifier.height(2.dp))
        Text(desc, fontSize = 10.sp, fontWeight = FontWeight.Normal, color = Color(0xFFC3C5D8).copy(alpha = 0.7f), lineHeight = 14.sp)
      }
      Spacer(Modifier.width(4.dp))
      Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF8D90A1).copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
    }
  }
}

// ── Preview wrappers ──────────────────────────────────────────────
@Preview(showBackground = true, backgroundColor = 0xFF050507, name = "Splash")
@Composable fun _PreviewSplash() { SplashScreen(onTimeout = {}) }

@Preview(showBackground = true, backgroundColor = 0xFF050507, name = "Onboarding P1")
@Composable fun _PreviewOnboarding() { OnboardingScreen(onContinue = {}, onSkip = {}) }

@Preview(showBackground = true, backgroundColor = 0xFF050507, name = "Login")
@Composable fun _PreviewLogin() { LoginScreen(email = "test@guardian.app", password = "", onEmailChange = {}, onPasswordChange = {}, onLoginSuccess = {}) }

@Preview(showBackground = true, backgroundColor = 0xFF050507, name = "Home")
@Composable fun _PreviewHome() { HomeScreen(onToggleAlarm = {}, onNavigateToProfile = {}) }

@Preview(showBackground = true, backgroundColor = 0xFF050507, name = "Profile")
@Composable fun _PreviewProfile() { ProfileScreen(biometricEnabled = true, twoFactorEnabled = false, alertConfigEnabled = true, onBiometricToggle = {}, onTwoFactorToggle = {}, onAlertConfigToggle = {}, onLockTriggered = {}, onLogout = {}, onNavigateToHome = {}) }

@Preview(showBackground = true, backgroundColor = 0xFF050507, name = "Setup Complete")
@Composable fun _PreviewSetupComplete() { SetupCompleteScreen(onContinue = {}, onBack = {}) }

@Preview(showBackground = true, backgroundColor = 0xFF050507, name = "PIN Setup")
@Composable fun _PreviewPinSetup() { PermissionPinSetupScreen(onComplete = {}, onSkip = {}, onBack = {}) }
