package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PinKeypad(
  modifier: Modifier = Modifier,
  keySpacing: Dp = 10.dp,
  keySize: Dp = 64.dp,
  buttonColor: Color = Color(0xFF131316).copy(alpha = 0.55f),
  borderColor: Color = Color(0xFFB6C4FF).copy(alpha = 0.1f),
  textColor: Color = Color(0xFFE5E1E5),
  subtitleColor: Color = Color(0xFF8D90A1).copy(alpha = 0.5f),
  actionColor: Color = Color(0xFFFF5167),
  clearColor: Color = Color(0xFF8D90A1),
  onDigit: (String) -> Unit = {},
  onDelete: () -> Unit = {},
  onClear: () -> Unit = {},
  clearLabel: String = "CLEAR"
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(keySpacing),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      repeat(3) { i ->
        DigitKey((i + 1).toString(), keySize, buttonColor, borderColor, textColor, subtitleColor) { onDigit((i + 1).toString()) }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      repeat(3) { i ->
        DigitKey((i + 4).toString(), keySize, buttonColor, borderColor, textColor, subtitleColor) { onDigit((i + 4).toString()) }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      repeat(3) { i ->
        DigitKey((i + 7).toString(), keySize, buttonColor, borderColor, textColor, subtitleColor) { onDigit((i + 7).toString()) }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      ActionKey(label = clearLabel, size = keySize, buttonColor = buttonColor, borderColor = borderColor, contentColor = clearColor, onClick = onClear)
      DigitKey("0", keySize, buttonColor, borderColor, textColor, subtitleColor) { onDigit("0") }
      ActionKey(label = "DEL", size = keySize, buttonColor = buttonColor, borderColor = borderColor, contentColor = actionColor, onClick = onDelete, icon = Icons.AutoMirrored.Filled.Backspace)
    }
  }
}

@Composable
private fun DigitKey(
  key: String,
  size: Dp,
  buttonColor: Color,
  borderColor: Color,
  textColor: Color,
  subtitleColor: Color,
  onClick: () -> Unit
) {
  val interactionSource = remember { MutableInteractionSource() }
  val bg by animateColorAsState(buttonColor, tween(80), label = "kbg")
  val sub = when (key) {
    "2" -> "ABC"; "3" -> "DEF"; "4" -> "GHI"; "5" -> "JKL"
    "6" -> "MNO"; "7" -> "PQRS"; "8" -> "TUV"; "9" -> "WXYZ"
    else -> ""
  }

  Box(
    modifier = Modifier
      .size(size)
      .clip(CircleShape)
      .background(bg)
      .border(0.5.dp, borderColor, CircleShape)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(key, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = textColor, lineHeight = 26.sp)
      if (sub.isNotEmpty()) {
        Text(sub, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = subtitleColor, letterSpacing = 0.8.sp, lineHeight = 9.sp)
      }
    }
  }
}

@Composable
private fun ActionKey(
  label: String,
  size: Dp,
  buttonColor: Color,
  borderColor: Color,
  contentColor: Color,
  onClick: () -> Unit,
  icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
  val interactionSource = remember { MutableInteractionSource() }

  Box(
    modifier = Modifier
      .size(size)
      .clip(CircleShape)
      .background(buttonColor)
      .border(0.5.dp, borderColor, CircleShape)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(20.dp))
    } else {
      Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = contentColor, letterSpacing = 0.5.sp)
    }
  }
}
