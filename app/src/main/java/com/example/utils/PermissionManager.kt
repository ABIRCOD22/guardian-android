package com.example.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object PermissionManager {
  fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo?.name?.contains(serviceClass.name) == true }
  }

  fun isNotificationListenerEnabled(context: Context): Boolean {
    val packageNames = Settings.Secure.getString(
      context.contentResolver,
      "enabled_notification_listeners"
    )
    return packageNames?.contains(context.packageName) == true
  }
}
