package com.quasslabs.kidlock

import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest
import java.security.SecureRandom

class KidLockApp : Application()

data class AllowedApp(val label: String, val packageName: String)

/** Shared policy, PIN, and preference helpers. */
object KidLock {
    val CATALOG = listOf(
        AllowedApp("YouTube Kids", "com.google.android.apps.youtube.kids"),
        AllowedApp("Netflix", "com.netflix.mediaclient"),
        AllowedApp("Prime Video", "com.amazon.avod.thirdpartyclient"),
        AllowedApp("Disney+", "com.disney.disneyplus"),
    )

    private const val PREFS = "kidlock"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    const val KEY_AIRPLANE_LOCK = "airplane_lock"
    const val KEY_PACKAGES = "packages"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    fun isAirplaneModeOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /** DISALLOW_AIRPLANE_MODE exists from API 28; on older devices the toggle cannot be locked. */
    val airplaneLockSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun allowedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()

    fun applyPolicies(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)

        if (airplaneLockSupported) {
            if (prefs(context).getBoolean(KEY_AIRPLANE_LOCK, false)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
            }
        }

        // KidLock itself must be allowlisted so LockActivity can be the root of the locked task.
        val packages = allowedPackages(context) + context.packageName
        dpm.setLockTaskPackages(admin, packages.toTypedArray())
    }

    @Suppress("DEPRECATION") // clearDeviceOwnerApp is the only self-service way out for a hobby app.
    fun releaseDeviceOwner(context: Context) {
        val dpm = dpm(context)
        val admin = admin(context)
        if (airplaneLockSupported) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
        }
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    fun hasPin(context: Context) = prefs(context).contains(KEY_PIN_HASH)

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs(context).edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash(salt, pin))
            .apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /** Shows a PIN prompt and runs [onSuccess] only when the PIN matches. */
    fun promptPin(activity: Activity, title: String, onSuccess: () -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (checkPin(activity, input.text.toString())) {
                    onSuccess()
                } else {
                    Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
