package com.quasslabs.kidlock

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Root of a locked session. It enters lock task mode (via lockTaskMode="if_whitelisted"
 * in the manifest), then launches the chosen app on top. Because every allowed app is in
 * the lock task allowlist, the child stays inside it. Backing out of the app lands here,
 * where the PIN-protected Done button ends the session.
 */
class LockActivity : AppCompatActivity() {

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        launched = savedInstanceState?.getBoolean(STATE_LAUNCHED) ?: false

        findViewById<Button>(R.id.reopenButton).setOnClickListener { launchTarget() }
        findViewById<Button>(R.id.doneButton).setOnClickListener {
            KidLock.promptPin(this, "Enter PIN to unlock") {
                stopLockTask()
                finish()
            }
        }

        // Back does nothing here; the only exit is the PIN.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launched = false
    }

    override fun onResume() {
        super.onResume()
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            // Fallback: if the allowlist was not applied, this shows the system screen-pinning prompt.
            startLockTask()
        }
        if (!launched) {
            launched = true
            launchTarget()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launched)
    }

    private fun launchTarget() {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(this, "App is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val STATE_LAUNCHED = "launched"

        fun start(context: Context, packageName: String) {
            context.startActivity(
                Intent(context, LockActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
