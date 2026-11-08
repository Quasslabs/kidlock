package com.quasslabs.kidlock

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Root of a locked session. It enters lock task mode (via lockTaskMode="if_whitelisted"
 * in the manifest), then launches the chosen app on top. Because every allowed app is in
 * the lock task allowlist, the child stays inside it. Backing out of the app lands here,
 * where holding Done for a few seconds, then entering the PIN, ends the session.
 */
class LockActivity : AppCompatActivity() {

    private var launched = false
    /** Endless decorative animations, paused while the screen is hidden behind the app. */
    private val loops = mutableListOf<ValueAnimator>()
    private val holds = mutableListOf<ValueAnimator>()
    private var switchLevel: KidLevel? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        launched = savedInstanceState?.getBoolean(STATE_LAUNCHED) ?: false

        val reopen = findViewById<TextView>(R.id.reopenButton)
        reopen.setOnClickListener { launchTarget() }
        styleReopenButton(reopen)
        setUpHoldForDone(findViewById(R.id.doneButton))

        // Back does nothing here; the only exit is the PIN.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    /**
     * Lets an older child move to another allowed app without the PIN. Every allowed app is in the
     * lock task allowlist, so the new app opens inside the same locked session.
     */
    private fun pickAnotherApp() {
        showAppGridDialog(this, "Pick an app", KidLock.allowedApps(this)) { app ->
            intent = Intent(intent).putExtra(EXTRA_PACKAGE, app.packageName)
            launchTarget()
        }
    }

    override fun onStart() {
        super.onStart()
        loops.forEach { if (it.isPaused) it.resume() }
    }

    override fun onStop() {
        // The session screen sits stopped behind the app for the whole session; do not animate it.
        loops.forEach { it.pause() }
        super.onStop()
    }

    override fun onDestroy() {
        (loops + holds).forEach { it.cancel() }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int) = value * resources.displayMetrics.density

    /** A rounded rainbow pill whose colors drift and which gently pulses, so it reads as "tap me". */
    private fun styleReopenButton(view: TextView) {
        val palette = intArrayOf(
            Color.parseColor("#FF6B6B"), Color.parseColor("#FFB84C"), Color.parseColor("#FFE66D"),
            Color.parseColor("#4ECDC4"), Color.parseColor("#5B8DEF"), Color.parseColor("#B76EF0"),
        )
        val pill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(palette[0], palette[1]))
        pill.cornerRadius = dp(48)
        view.background = pill
        view.setShadowLayer(dp(3), 0f, dp(1), Color.parseColor("#55000000"))

        val evaluator = ArgbEvaluator()
        fun colorAt(t: Float): Int {
            val pos = (t % 1f) * palette.size
            val i = pos.toInt() % palette.size
            return evaluator.evaluate(pos - pos.toInt(), palette[i], palette[(i + 1) % palette.size]) as Int
        }
        loops += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                pill.colors = intArrayOf(colorAt(t), colorAt(t + 1f / palette.size))
            }
            start()
        }
        loops += ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
            start()
        }
    }

    /**
     * Done needs a 3-second hold before the PIN prompt. A quick tap from a curious child does nothing.
     */
    private fun setUpHoldForDone(view: TextView) {
        holdToActivate(view, DONE_HOLD_MS, "Hold for Done", "#2E2A44", "#6C63A8") {
            KidLock.promptPin(this@LockActivity, "Enter PIN to unlock") {
                KidLock.sessionEnding = true
                stopLockTask()
                finish()
            }
        }
    }

    /**
     * Turns [view] into a pill that must be held for [holdMs], with a bar filling up behind the label,
     * before [onComplete] runs. Letting go early resets it. The hold is timed by a Handler, not the
     * fill animation, because animations finish instantly when the phone has animations turned off.
     */
    @SuppressLint("ClickableViewAccessibility") // deliberately hold-only; a plain click must do nothing
    private fun holdToActivate(
        view: TextView,
        holdMs: Long,
        label: String,
        trackColor: String,
        fillColor: String,
        onComplete: () -> Unit,
    ) {
        view.text = label
        val track = GradientDrawable().apply {
            cornerRadius = dp(32)
            setColor(Color.parseColor(trackColor))
            setStroke(dp(1).toInt(), Color.parseColor("#4A4566"))
        }
        val fill = ClipDrawable(
            GradientDrawable().apply {
                cornerRadius = dp(32)
                setColor(Color.parseColor(fillColor))
            },
            Gravity.START, ClipDrawable.HORIZONTAL,
        )
        view.background = LayerDrawable(arrayOf(track, fill))

        val hold = ValueAnimator.ofInt(0, 10_000).apply {
            duration = holdMs
            interpolator = LinearInterpolator()
            addUpdateListener { fill.level = it.animatedValue as Int }
        }
        holds += hold
        var completed = false
        val complete = Runnable {
            completed = true
            hold.cancel()
            view.text = label
            fill.level = 0
            onComplete()
        }

        view.setOnClickListener(null)
        view.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    completed = false
                    view.text = "Keep holding..."
                    hold.start()
                    handler.postDelayed(complete, holdMs)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(complete)
                    if (!completed) {
                        hold.cancel()
                        fill.level = 0
                        view.text = label
                    }
                    v.isPressed = false
                }
            }
            true
        }
    }

    /** Switch app: hidden for little kids, a 1-second hold for big kids, a plain tap at Free. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpSwitchButton() {
        val view = findViewById<TextView>(R.id.switchButton)
        val level = KidLock.kidLevel(this)
        if (level == switchLevel) return // already set up; onResume runs on every return to this screen
        switchLevel = level
        view.visibility = if (level == KidLevel.LITTLE) View.GONE else View.VISIBLE
        when (level) {
            KidLevel.LITTLE -> Unit
            KidLevel.BIG -> holdToActivate(view, SWITCH_HOLD_MS, "Switch app", "#2B4A7A", "#5B8DEF") { pickAnotherApp() }
            KidLevel.FREE -> {
                view.text = "Switch app"
                view.setOnTouchListener(null)
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(32)
                    setColor(Color.parseColor("#5B8DEF"))
                }
                view.setOnClickListener { pickAnotherApp() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A bare bringToFront() keeps the current app; only a new app choice replaces it.
        if (intent.hasExtra(EXTRA_PACKAGE)) {
            setIntent(intent)
            launched = false
        }
    }

    override fun onResume() {
        super.onResume()
        setUpSwitchButton()
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            if (!intent.hasExtra(EXTRA_PACKAGE)) {
                // Opened by bringToFront() after the session already ended: never start a new one.
                finish()
                return
            }
            if (!KidLock.dpm(this).isLockTaskPermitted(packageName)) {
                // Without the allowlist, startLockTask() would show the screen-pinning prompt on every resume.
                Toast.makeText(this, "Open KidLock settings and tap Apply first", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            KidLock.sessionEnding = false
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
        private const val DONE_HOLD_MS = 3000L
        private const val SWITCH_HOLD_MS = 1000L

        fun start(context: Context, packageName: String) {
            context.startActivity(
                Intent(context, LockActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /** Shows the session screen (Back to app / Done) without starting a new app. */
        fun bringToFront(context: Context) {
            context.startActivity(Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
