package org.fossify.phone.receivers

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Device admin with the single "lock the screen" policy: keypad phones have no proximity sensor, so the call screen
 * turns the display off itself, and the ear can't touch anything (like pulling down the status bar).
 */
class ScreenOffAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, ScreenOffAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val policyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return policyManager.isAdminActive(component(context))
        }

        /** Turns the display off; false when the permission isn't given. */
        fun turnScreenOff(context: Context): Boolean {
            if (!isActive(context)) {
                return false
            }

            return try {
                (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                true
            } catch (e: SecurityException) {
                false
            }
        }
    }
}
