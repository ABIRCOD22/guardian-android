package com.example.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class SecurePhoneDeviceAdmin : DeviceAdminReceiver() {
  override fun onEnabled(context: Context, intent: Intent) {
    Toast.makeText(context, "Device admin granted", Toast.LENGTH_SHORT).show()
  }

  override fun onDisabled(context: Context, intent: Intent) {
    Toast.makeText(context, "Device admin disabled", Toast.LENGTH_LONG).show()
  }
}
