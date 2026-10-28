package com.quasslabs.kidlock

import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest
import java.security.SecureRandom

class KidLockApp : Application()

data class AllowedApp(val label: String, val packageName: String)

enum class Mode { TOTAL, LIGHT }

/** Shared policy, PIN, and preference helpers. */
object KidLock {
    private const val PREFS = "kidlock"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_FAILS = "pin_fails"
    private const val KEY_PIN_LOCKED_UNTIL = "pin_locked_until"
    private const val KEY_MODE = "mode"
    const val KEY_BLOCK_DEBUGGING = "block_debugging"
    const val KEY_PACKAGES = "packages"

    private const val FREE_PIN_TRIES = 5
    private const val BASE_LOCKOUT_MS = 30_000L
    private const val MAX_LOCKOUT_MS = 60 * 60_000L

    // UserManager.DISALLOW_SIM_GLOBALLY, added in API 35 (compileSdk here is 34).
    private const val DISALLOW_SIM_GLOBALLY = "no_sim_globally"

    /** On in both modes: the child must not be able to wipe the phone or sidestep the policy. */
    private val BASE_RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_CONFIG_DATE_TIME, // the PIN lockout uses the wall clock
    )

    /** Total lockdown only: the phone cannot be used as a phone. */
    private val TOTAL_RESTRICTIONS = buildList {
        add(UserManager.DISALLOW_OUTGOING_CALLS)
        add(UserManager.DISALLOW_SMS)
        add(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        if (Build.VERSION.SDK_INT >= 35) add(DISALLOW_SIM_GLOBALLY)
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    fun mode(context: Context): Mode =
        runCatching { Mode.valueOf(prefs(context).getString(KEY_MODE, null) ?: "") }.getOrDefault(Mode.TOTAL)

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun allowedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()

    /** Every app with a home-screen icon, except KidLock itself, sorted by name. */
    fun launchableApps(context: Context): List<AllowedApp> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(home, 0)
            .map { AllowedApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun applyPolicies(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val prefs = prefs(context)

        BASE_RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
        val total = mode(context) == Mode.TOTAL
        TOTAL_RESTRICTIONS.forEach { setRestriction(dpm, admin, it, total) }
        setRestriction(dpm, admin, UserManager.DISALLOW_DEBUGGING_FEATURES, prefs.getBoolean(KEY_BLOCK_DEBUGGING, true))

        // KidLock itself must be allowlisted so LockActivity can be the root of the locked task.
        val packages = allowedPackages(context) + context.packageName
        dpm.setLockTaskPackages(admin, packages.toTypedArray())

        // No power menu inside a session, so the child cannot use Restart to break out.
        // Must come after setLockTaskPackages: on Android 14+ that call resets the features to the default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
    }

    private fun setRestriction(dpm: DevicePolicyManager, admin: ComponentName, key: String, on: Boolean) {
        if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
    }

    @Suppress("DEPRECATION") // clearDeviceOwnerApp is the only self-service way out for a hobby app.
    fun releaseDeviceOwner(context: Context) {
        val dpm = dpm(context)
        val admin = admin(context)
        (BASE_RESTRICTIONS + TOTAL_RESTRICTIONS + UserManager.DISALLOW_DEBUGGING_FEATURES).forEach {
            dpm.clearUserRestriction(admin, it)
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
            .remove(KEY_PIN_FAILS)
            .remove(KEY_PIN_LOCKED_UNTIL)
            .apply()
    }

    /** Milliseconds until another PIN attempt is allowed; 0 when not locked out. */
    fun pinLockoutRemaining(context: Context): Long =
        (prefs(context).getLong(KEY_PIN_LOCKED_UNTIL, 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /**
     * Checks the PIN and records the attempt. After [FREE_PIN_TRIES] misses, each further miss
     * locks entry for 30s, doubling up to an hour, so a child cannot work through all PINs.
     */
    fun checkPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        if (pinLockoutRemaining(context) > 0) return false
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        if (MessageDigest.isEqual(stored.toByteArray(), hash(salt, pin).toByteArray())) {
            p.edit().remove(KEY_PIN_FAILS).remove(KEY_PIN_LOCKED_UNTIL).apply()
            return true
        }
        val fails = p.getInt(KEY_PIN_FAILS, 0) + 1
        val editor = p.edit().putInt(KEY_PIN_FAILS, fails)
        if (fails >= FREE_PIN_TRIES) {
            val lockout = (BASE_LOCKOUT_MS shl (fails - FREE_PIN_TRIES).coerceAtMost(7)).coerceAtMost(MAX_LOCKOUT_MS)
            editor.putLong(KEY_PIN_LOCKED_UNTIL, System.currentTimeMillis() + lockout)
        }
        editor.apply()
        return false
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /** Shows a PIN prompt and runs [onSuccess] only when the PIN matches. */
    fun promptPin(activity: Activity, title: String, onSuccess: () -> Unit) {
        val wait = pinLockoutRemaining(activity)
        if (wait > 0) {
            Toast.makeText(activity, "Too many tries. Wait ${(wait + 999) / 1000}s.", Toast.LENGTH_SHORT).show()
            return
        }
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
