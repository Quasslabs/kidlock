package com.quasslabs.kidlock

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.quasslabs.kidlock.Pin.toHex
import java.security.SecureRandom

class KidLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // During a session, turning the screen off and on brings up the session screen (Back to app /
        // Done), so there is always a way to the PIN even when the app ignores Back. A Device Owner
        // is exempt from Android's background activity start limits, so this works from a receiver.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (KidLock.inSession(context) && KidLock.prefs(context).getBoolean(KidLock.KEY_POWER_EXIT, true)) {
                    LockActivity.bringToFront(context)
                }
            }
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }
}

data class AllowedApp(val label: String, val packageName: String)

enum class Mode { TOTAL, LIGHT }

/** How a child moves between allowed apps inside a session. */
enum class KidLevel { LITTLE, BIG, FREE }

/** Shared policy, PIN, and preference helpers. */
object KidLock {
    /**
     * Set by Done just before stopLockTask(), so the home screen does not reopen the session screen
     * while Android is still leaving lock task mode. Cleared when the next session starts.
     */
    @Volatile var sessionEnding = false

    /**
     * True during a KidLock session. Screen pinning by the user (LOCK_TASK_MODE_PINNED) is not
     * a KidLock session, so it does not count.
     */
    fun inSession(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED

    private const val PREFS = "kidlock"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_FAILS = "pin_fails"
    private const val KEY_PIN_LOCKED_UNTIL = "pin_locked_until"
    private const val KEY_MODE = "mode"
    const val KEY_BLOCK_DEBUGGING = "block_debugging"
    const val KEY_HOME_SCREEN = "home_screen"
    const val KEY_POWER_EXIT = "power_exit"
    private const val KEY_KID_LEVEL = "kid_level"
    const val KEY_PACKAGES = "packages"

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

    fun kidLevel(context: Context): KidLevel =
        runCatching { KidLevel.valueOf(prefs(context).getString(KEY_KID_LEVEL, null) ?: "") }.getOrDefault(KidLevel.LITTLE)

    fun setKidLevel(context: Context, level: KidLevel) {
        prefs(context).edit().putString(KEY_KID_LEVEL, level.name).apply()
    }

    /** The allowed apps that are installed, for the kid screens. */
    fun allowedApps(context: Context): List<AllowedApp> {
        val allowed = allowedPackages(context)
        return launcherApps(context) { it in allowed }
    }

    fun allowedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()

    /** Every app with a home-screen icon, except KidLock itself, sorted by name. */
    fun launchableApps(context: Context): List<AllowedApp> = launcherApps(context) { true }

    /** Launcher apps whose package passes [keep]. Filters before loading labels, which is the slow part. */
    private fun launcherApps(context: Context, keep: (String) -> Boolean): List<AllowedApp> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(home, 0)
            .filter { it.activityInfo.packageName != context.packageName && keep(it.activityInfo.packageName) }
            .map { AllowedApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
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

        setHomeScreen(context, prefs.getBoolean(KEY_HOME_SCREEN, false))

        // No power menu, Home, or Recents inside a session. Home stays off even with KidLock as the
        // home screen: on gesture navigation the swipe-up is handled by the Pixel launcher's recents
        // screen, which freezes in lock task mode. The way out is the power button (see KidLockApp).
        // Must come after setLockTaskPackages: on Android 14+ that call resets the features to the default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
    }

    fun isHomeScreen(context: Context) = prefs(context).getBoolean(KEY_HOME_SCREEN, false)

    /**
     * Makes KidLock the phone's home screen, so Home always returns to "Pick an app" and the
     * child can only start allowed apps, always locked. Also stops the notification shade from
     * being pulled down, since its Settings gear would otherwise open the whole phone.
     * The HOME intent filter lives on a disabled alias, so a phone without this setting never
     * offers KidLock as a launcher.
     */
    private fun setHomeScreen(context: Context, on: Boolean) {
        val dpm = dpm(context)
        val admin = admin(context)
        val alias = ComponentName(context, HOME_ALIAS)
        context.packageManager.setComponentEnabledSetting(
            alias,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        if (on) {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, alias)
        }
        // Returns false (and does nothing) when the phone has a secure screen lock; Home still works.
        dpm.setStatusBarDisabled(admin, on)
    }

    private const val HOME_ALIAS = "com.quasslabs.kidlock.HomeAlias"

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
        setHomeScreen(context, false)
        prefs(context).edit().remove(KEY_HOME_SCREEN).apply()
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    fun hasPin(context: Context) = prefs(context).contains(KEY_PIN_HASH)

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs(context).edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, Pin.hash(salt, pin))
            .remove(KEY_PIN_FAILS)
            .remove(KEY_PIN_LOCKED_UNTIL)
            .apply()
    }

    /** Milliseconds until another PIN attempt is allowed; 0 when not locked out. */
    fun pinLockoutRemaining(context: Context): Long =
        (prefs(context).getLong(KEY_PIN_LOCKED_UNTIL, 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /**
     * Checks the PIN and records the attempt. After [Pin.FREE_TRIES] misses, each further miss
     * locks entry for 30s, doubling up to an hour, so a child cannot work through all PINs.
     */
    fun checkPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        if (pinLockoutRemaining(context) > 0) return false
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        if (Pin.matches(salt, stored, pin)) {
            p.edit().remove(KEY_PIN_FAILS).remove(KEY_PIN_LOCKED_UNTIL).apply()
            return true
        }
        val fails = p.getInt(KEY_PIN_FAILS, 0) + 1
        val editor = p.edit().putInt(KEY_PIN_FAILS, fails)
        val lockout = Pin.lockoutMs(fails)
        if (lockout > 0) editor.putLong(KEY_PIN_LOCKED_UNTIL, System.currentTimeMillis() + lockout)
        editor.apply()
        return false
    }

    /** Shows a PIN prompt and runs [onSuccess] only when the PIN matches. */
    fun promptPin(activity: Activity, title: String, onSuccess: () -> Unit) {
        val wait = pinLockoutRemaining(activity)
        if (wait > 0) {
            Toast.makeText(activity, "Too many tries. Wait ${(wait + 999) / 1000}s.", Toast.LENGTH_SHORT).show()
            return
        }
        val side = (24 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setPaddingRelative(side / 2, paddingTop, paddingEnd, paddingBottom) // text inset from the underline
        }
        // Line the field up with the dialog title instead of the dialog's edge.
        val container = FrameLayout(activity).apply {
            setPadding(side, side / 3, side, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
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
