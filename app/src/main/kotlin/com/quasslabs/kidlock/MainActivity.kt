package com.quasslabs.kidlock

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
    private lateinit var appButtons: LinearLayout
    private lateinit var appChecks: LinearLayout
    private lateinit var modeTotal: RadioButton
    private lateinit var modeLight: RadioButton
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var pinField: EditText
    private lateinit var pinConfirmField: EditText
    private lateinit var launcherSearch: EditText
    private lateinit var appSearch: EditText

    private val checkBoxes = mutableMapOf<String, CheckBox>()
    private val launchButtons = mutableMapOf<String, Button>()
    private var settingsUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        notOwnerSection = findViewById(R.id.notOwnerSection)
        launcherSection = findViewById(R.id.launcherSection)
        settingsSection = findViewById(R.id.settingsSection)
        appButtons = findViewById(R.id.appButtons)
        appChecks = findViewById(R.id.appChecks)
        modeTotal = findViewById(R.id.modeTotal)
        modeLight = findViewById(R.id.modeLight)
        debugSwitch = findViewById(R.id.debugSwitch)
        pinField = findViewById(R.id.pinField)
        pinConfirmField = findViewById(R.id.pinConfirmField)
        launcherSearch = findViewById(R.id.launcherSearch)
        appSearch = findViewById(R.id.appSearch)
        launcherSearch.onTextChanged { filter(launchButtons, it) }
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
        findViewById<Button>(R.id.removeOwnerButton).setOnClickListener { confirmRemoveOwner() }
    }

    override fun onResume() {
        super.onResume()
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
        appButtons.removeAllViews()
        launchButtons.clear()
        val allowed = KidLock.allowedPackages(this)
        KidLock.launchableApps(this)
            .filter { it.packageName in allowed }
            .forEach { app ->
                val button = Button(this).apply {
                    text = app.label
                    setOnClickListener { LockActivity.start(this@MainActivity, app.packageName) }
                }
                launchButtons[app.packageName] = button
                appButtons.addView(button)
            }
        if (appButtons.childCount == 0) {
            appButtons.addView(TextView(this).apply { text = "No allowed apps are installed." })
        }
        filter(launchButtons, launcherSearch.text.toString())
    }

    private fun renderSettings() {
        val prefs = KidLock.prefs(this)
        when (KidLock.mode(this)) {
            Mode.TOTAL -> modeTotal.isChecked = true
            Mode.LIGHT -> modeLight.isChecked = true
        }
        debugSwitch.isChecked = prefs.getBoolean(KidLock.KEY_BLOCK_DEBUGGING, true)

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
        val selected = checkBoxes.filterValues { it.isChecked }.keys
        KidLock.prefs(this).edit()
            .putBoolean(KidLock.KEY_BLOCK_DEBUGGING, debugSwitch.isChecked)
            .putStringSet(KidLock.KEY_PACKAGES, selected)
            .apply()
        KidLock.applyPolicies(this)

        pinField.text.clear()
        pinConfirmField.text.clear()
        settingsUnlocked = false
        toast("Applied")
        render()
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
