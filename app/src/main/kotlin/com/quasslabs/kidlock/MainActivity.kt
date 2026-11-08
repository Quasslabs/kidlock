package com.quasslabs.kidlock

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Three states:
 *  - not Device Owner: show the ADB setup command
 *  - Device Owner, PIN set, settings locked: show the kid launcher plus a PIN-gated settings button
 *  - Device Owner, no PIN yet or settings unlocked: show parent settings
 */
class MainActivity : AppCompatActivity() {

    private lateinit var notOwnerSection: View
    private lateinit var launcherSection: View
    private lateinit var settingsSection: View
    private lateinit var appGrid: ExpandedGridView
    private lateinit var noAppsText: TextView
    private var launcherAdapter: AppGridAdapter? = null
    private lateinit var appChecks: LinearLayout
    private lateinit var modeTotal: RadioButton
    private lateinit var modeLight: RadioButton
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var homeSwitch: SwitchCompat
    private lateinit var powerSwitch: SwitchCompat
    private lateinit var levelLittle: RadioButton
    private lateinit var levelBig: RadioButton
    private lateinit var levelFree: RadioButton
    private lateinit var pinField: EditText
    private lateinit var pinConfirmField: EditText
    private lateinit var launcherSearch: EditText
    private lateinit var appSearch: EditText

    private val checkBoxes = mutableMapOf<String, CheckBox>()
    private var settingsUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        notOwnerSection = findViewById(R.id.notOwnerSection)
        launcherSection = findViewById(R.id.launcherSection)
        settingsSection = findViewById(R.id.settingsSection)
        appGrid = findViewById(R.id.appGrid)
        noAppsText = findViewById(R.id.noAppsText)
        appGrid.setOnItemClickListener { _, _, position, _ ->
            launcherAdapter?.let { LockActivity.start(this, it.getItem(position).packageName) }
        }
        appChecks = findViewById(R.id.appChecks)
        modeTotal = findViewById(R.id.modeTotal)
        modeLight = findViewById(R.id.modeLight)
        debugSwitch = findViewById(R.id.debugSwitch)
        homeSwitch = findViewById(R.id.homeSwitch)
        powerSwitch = findViewById(R.id.powerSwitch)
        levelLittle = findViewById(R.id.levelLittle)
        levelBig = findViewById(R.id.levelBig)
        levelFree = findViewById(R.id.levelFree)
        pinField = findViewById(R.id.pinField)
        pinConfirmField = findViewById(R.id.pinConfirmField)
        launcherSearch = findViewById(R.id.launcherSearch)
        appSearch = findViewById(R.id.appSearch)
        launcherSearch.onTextChanged { launcherAdapter?.filter(it) }
        appSearch.onTextChanged { filter(checkBoxes, it) }

        findViewById<TextView>(R.id.adbCommand).text =
            "adb shell dpm set-device-owner $packageName/.AdminReceiver"

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            KidLock.promptPin(this, "Parent PIN") {
                settingsUnlocked = true
                render()
            }
        }
        findViewById<Button>(R.id.applyButton).setOnClickListener { apply() }
        findViewById<Button>(R.id.openAppButton).setOnClickListener { pickAppToOpen() }

        // As the home screen, Back has nowhere to go; in parent settings it closes them.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    settingsUnlocked -> { settingsUnlocked = false; render() }
                    KidLock.isHomeScreen(this@MainActivity) -> Unit
                    else -> finish()
                }
            }
        })
        findViewById<Button>(R.id.removeOwnerButton).setOnClickListener { confirmRemoveOwner() }
    }

    override fun onResume() {
        super.onResume()
        // Reached while a session is still running (for example the home intent after an allowed app
        // exits): show the session screen instead of the launcher, so the session cannot be sidestepped.
        // sessionEnding covers the moment after Done, before Android has finished leaving lock task mode.
        val ending = KidLock.sessionEnding
        KidLock.sessionEnding = false
        if (!ending && KidLock.inSession(this)) {
            LockActivity.bringToFront(this)
            return
        }
        render()
    }

    override fun onStop() {
        super.onStop()
        settingsUnlocked = false
    }

    private fun render() {
        val owner = KidLock.isDeviceOwner(this)
        val hasPin = KidLock.hasPin(this)
        val showLauncher = owner && hasPin && !settingsUnlocked
        val showSettings = owner && (!hasPin || settingsUnlocked)

        notOwnerSection.visibility = if (owner) View.GONE else View.VISIBLE
        launcherSection.visibility = if (showLauncher) View.VISIBLE else View.GONE
        settingsSection.visibility = if (showSettings) View.VISIBLE else View.GONE

        if (showLauncher) renderLauncher()
        if (showSettings) renderSettings()
    }

    private fun renderLauncher() {
        val adapter = AppGridAdapter(this, KidLock.allowedApps(this))
        launcherAdapter = adapter
        appGrid.adapter = adapter
        noAppsText.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
        adapter.filter(launcherSearch.text.toString())
    }

    private fun renderSettings() {
        val prefs = KidLock.prefs(this)
        when (KidLock.mode(this)) {
            Mode.TOTAL -> modeTotal.isChecked = true
            Mode.LIGHT -> modeLight.isChecked = true
        }
        debugSwitch.isChecked = prefs.getBoolean(KidLock.KEY_BLOCK_DEBUGGING, true)
        homeSwitch.isChecked = KidLock.isHomeScreen(this)
        powerSwitch.isChecked = prefs.getBoolean(KidLock.KEY_POWER_EXIT, true)
        when (KidLock.kidLevel(this)) {
            KidLevel.LITTLE -> levelLittle.isChecked = true
            KidLevel.BIG -> levelBig.isChecked = true
            KidLevel.FREE -> levelFree.isChecked = true
        }

        appChecks.removeAllViews()
        checkBoxes.clear()
        val allowed = KidLock.allowedPackages(this)
        // Allowed apps first, so the current setup is visible without scrolling through everything.
        for (app in KidLock.launchableApps(this).sortedBy { it.packageName !in allowed }) {
            val box = CheckBox(this).apply {
                text = app.label
                isChecked = app.packageName in allowed
            }
            checkBoxes[app.packageName] = box
            appChecks.addView(box)
        }
        filter(checkBoxes, appSearch.text.toString())
    }

    /** Hides every view whose label does not contain [query]. */
    private fun filter(views: Map<String, TextView>, query: String) {
        val q = query.trim()
        views.values.forEach {
            it.visibility = if (it.text.contains(q, ignoreCase = true)) View.VISIBLE else View.GONE
        }
    }

    private fun EditText.onTextChanged(action: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action(s.toString())
    })

    private fun apply() {
        val pin = pinField.text.toString()
        val confirm = pinConfirmField.text.toString()
        when {
            pin.isEmpty() && !KidLock.hasPin(this) -> return toast("Set a PIN first")
            pin.isNotEmpty() && pin.length < 4 -> return toast("PIN must be at least 4 digits")
            pin != confirm -> return toast("PINs do not match")
        }

        if (pin.isNotEmpty()) KidLock.setPin(this, pin)
        KidLock.setMode(this, if (modeLight.isChecked) Mode.LIGHT else Mode.TOTAL)
        KidLock.setKidLevel(this, when {
            levelFree.isChecked -> KidLevel.FREE
            levelBig.isChecked -> KidLevel.BIG
            else -> KidLevel.LITTLE
        })
        val selected = checkBoxes.filterValues { it.isChecked }.keys
        KidLock.prefs(this).edit()
            .putBoolean(KidLock.KEY_BLOCK_DEBUGGING, debugSwitch.isChecked)
            .putBoolean(KidLock.KEY_HOME_SCREEN, homeSwitch.isChecked)
            .putBoolean(KidLock.KEY_POWER_EXIT, powerSwitch.isChecked)
            .putStringSet(KidLock.KEY_PACKAGES, selected)
            .apply()
        KidLock.applyPolicies(this)

        pinField.text.clear()
        pinConfirmField.text.clear()
        settingsUnlocked = false
        toast("Applied")
        render()
    }

    /**
     * Parent escape hatch: opens any app outside a locked session, so the parent can use the
     * Play Store or Settings without turning off the home screen. Home comes back to KidLock,
     * and leaving this screen re-locks Parent settings behind the PIN. The app is kept out of
     * Recents so the child cannot swipe back into it from the KidLock home screen.
     */
    private fun pickAppToOpen() {
        val apps = KidLock.launchableApps(this)
        AlertDialog.Builder(this)
            .setTitle("Open an app")
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                packageManager.getLaunchIntentForPackage(apps[which].packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveOwner() {
        AlertDialog.Builder(this)
            .setTitle("Remove Device Owner?")
            .setMessage("This clears every restriction and app pinning. KidLock can then be uninstalled normally.")
            .setPositiveButton("Remove") { _, _ ->
                KidLock.releaseDeviceOwner(this)
                toast("Device Owner removed")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
