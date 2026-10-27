package dev.cdav.overscantoggle

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import rikka.shizuku.Shizuku

/** SeekBar whose left/right keys snap to a grid (see [Snap]) instead of adding a fixed amount. */
private class SnapSeekBar(
    context: Context,
    private val stepTenths: () -> Int,
    private val onSnap: (Int) -> Unit,
) : SeekBar(context) {
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { onSnap(Snap.up(progress, stepTenths())); return true }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { onSnap(Snap.down(progress, stepTenths())); return true }
        return super.onKeyDown(keyCode, event)
    }
}

class MainActivity : Activity() {
    private companion object {
        const val APPLY_DELAY_MS = 700L
        val STEPS = intArrayOf(1, 5, 10)          // slider step choices, in tenths of a percent
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var valueText: TextView
    private lateinit var mathText: TextView
    private lateinit var seek: SnapSeekBar
    private lateinit var typedEdit: EditText
    private lateinit var stepBtn: Button
    private lateinit var nameEdit: EditText
    private lateinit var list: LinearLayout
    private var stepTenths = 5

    private val applyRunnable = Runnable {
        Backend.submit(seek.progress, true) { err ->
            runOnUiThread {
                status.text = err?.let { "Apply failed: $it" } ?: "Applied ${Backend.fmt(seek.progress)}"
            }
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { start() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { start() } }

    private fun prefs() = getSharedPreferences("ui", 0)

    private fun schedule(delayMs: Long) {
        ui.removeCallbacks(applyRunnable)
        ui.postDelayed(applyRunnable, delayMs)
    }

    /** Move to [tenths] (clamped to 0..30.0%), update the readouts, and optionally apply to the display. */
    private fun setValue(tenths: Int, apply: Boolean = true) {
        val v = tenths.coerceIn(0, Backend.MAX)
        seek.progress = v
        showValue(v)
        if (apply) schedule(APPLY_DELAY_MS)
    }

    private fun showValue(v: Int) {
        valueText.text = Backend.fmt(v)
        mathText.text = String.format(
            Locale.US,
            "Stored value %.3f  (= %d.%d%% / 100)\nSlider units %d of %d  (1 unit = 0.1%%)",
            v / 1000.0, v / 10, v % 10, v, Backend.MAX
        )
    }

    private fun label(t: String, size: Float = 20f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isFocusable = true; setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stepTenths = prefs().getInt("step", 5).takeIf { it in STEPS } ?: 5

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 40, 80, 40)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101820"))
            addView(root)
        }
        // Green frame on the very edge of the screen, so the visible picture edge (after underscan) is easy to see.
        val edge = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((6 * resources.displayMetrics.density).toInt(), Color.parseColor("#76B900"))
            }
        }

        root.addView(label("sus", 40f).apply { typeface = android.graphics.Typeface.MONOSPACE })
        root.addView(label("shield-under-scanner", 16f).apply { setTextColor(Color.LTGRAY) })
        status = label("", 16f).apply { setTextColor(Color.parseColor("#76B900")) }
        root.addView(status)

        // Value readout
        valueText = label("0.0%", 48f).apply { setPadding(0, 24, 0, 0) }
        root.addView(valueText)
        mathText = label("", 15f).apply { setTextColor(Color.LTGRAY) }
        root.addView(mathText)

        // Slider
        seek = SnapSeekBar(this, { stepTenths }, { setValue(it) }).apply {
            max = Backend.MAX
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    showValue(p)
                    if (fromUser) schedule(APPLY_DELAY_MS)       // pointer drag; remote keys go through onSnap
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        root.addView(seek)

        stepBtn = button("") {
            stepTenths = STEPS[(STEPS.indexOf(stepTenths) + 1) % STEPS.size]
            prefs().edit().putInt("step", stepTenths).apply()
            refreshStepButton()
        }
        refreshStepButton()
        root.addView(stepBtn)

        // Snapping buttons: each moves to the next multiple of its own step.
        val steps = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (st in listOf(10, 5, 1)) steps.addView(button("-${Backend.fmt(st)}") { setValue(Snap.down(seek.progress, st)) })
        for (st in listOf(1, 5, 10)) steps.addView(button("+${Backend.fmt(st)}") { setValue(Snap.up(seek.progress, st)) })
        steps.addView(button("Reset to 0") { setValue(0) })
        root.addView(steps)
        root.addView(label(
            "Steps snap to the grid: from 14.3%, +0.5% goes to 14.5% and -0.5% goes to 14.0%.",
            14f
        ).apply { setTextColor(Color.LTGRAY) })

        // Typed entry
        root.addView(label("Type an exact value (0 to 30, one decimal)", 18f).apply { setPadding(0, 28, 0, 0) })
        val typedRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        typedEdit = EditText(this).apply {
            hint = "e.g. 14.5"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true); minWidth = 300
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { applyTyped(); true } else false
            }
        }
        typedRow.addView(typedEdit)
        typedRow.addView(button("Set") { applyTyped() })
        root.addView(typedRow)

        // Save as profile
        root.addView(label("Save current value as a profile", 18f).apply { setPadding(0, 28, 0, 0) })
        val saveRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nameEdit = EditText(this).apply {
            hint = "Name (e.g. Projector wall)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setSingleLine(true); minWidth = 500
        }
        saveRow.addView(nameEdit)
        saveRow.addView(button("Save") {
            val n = nameEdit.text.toString().trim().ifEmpty { Backend.fmt(seek.progress) }
            Profiles.save(this, n, seek.progress)
            nameEdit.setText("")
            renderProfiles()
        })
        root.addView(saveRow)

        root.addView(label("Saved profiles", 18f).apply { setPadding(0, 24, 0, 0) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(label(
            "Use from other tools (Tasker, Button Mapper, adb, ...):\n" +
            "  overscan://profile/<name>    overscan://set/<tenths>    overscan://next\n" +
            "  adb shell am start -a android.intent.action.VIEW -d \"overscan://profile/Gaming\"",
            14f
        ).apply { setTextColor(Color.LTGRAY); setPadding(0, 32, 0, 0) })

        setContentView(edge)
        showValue(0)
        renderProfiles()

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onResume() {
        super.onResume()
        start()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun refreshStepButton() {
        stepBtn.text = "Slider step (left/right on slider): ${Backend.fmt(stepTenths)}   [select to change]"
    }

    private fun applyTyped() {
        val parsed = typedEdit.text.toString().trim().replace(',', '.').toBigDecimalOrNull()
        if (parsed == null || parsed < BigDecimal.ZERO || parsed > BigDecimal(30)) {
            status.text = "Enter a number from 0 to 30."
            return
        }
        val tenths = parsed.multiply(BigDecimal(10)).setScale(0, RoundingMode.HALF_UP).toInt()
        if (parsed.multiply(BigDecimal(10)).compareTo(BigDecimal(tenths)) != 0) {
            Toast.makeText(this, "Rounded to the nearest 0.1%: ${Backend.fmt(tenths)}", Toast.LENGTH_SHORT).show()
        }
        typedEdit.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typedEdit.windowToken, 0)
        setValue(tenths)
    }

    /** Check Shizuku, request permission if needed, then sync the slider with the current hardware value. */
    private fun start() {
        if (!Backend.running()) { status.text = "Shizuku is not running. Start it, then reopen this app."; return }
        if (!Backend.granted()) { status.text = "Requesting Shizuku permission..."; Backend.requestPermission(); return }
        Thread {
            val cur = Backend.readCurrent()
            runOnUiThread {
                if (cur == null) {
                    status.text = "Could not read the current overscan value."
                } else {
                    setValue(cur, apply = false)
                    status.text = "Current: ${Backend.fmt(cur)}. Changes apply about a second after you stop."
                }
            }
        }.start()
    }

    private fun renderProfiles() {
        list.removeAllViews()
        val ps = Profiles.all(this)
        if (ps.isEmpty()) list.addView(label("None yet.", 16f).apply { setTextColor(Color.GRAY) })
        for (p in ps) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(p.name, 22f).apply { minWidth = 360 })
            row.addView(label(Backend.fmt(p.t), 22f).apply { minWidth = 140 })
            row.addView(button("Apply") { setValue(p.t) })
            row.addView(button("Delete") { Profiles.delete(this, p.name); renderProfiles() })
            list.addView(row)
        }
    }
}
