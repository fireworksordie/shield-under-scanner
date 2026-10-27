package dev.cdav.overscantoggle

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var valueText: TextView
    private lateinit var seek: SeekBar
    private lateinit var nameEdit: EditText
    private lateinit var list: LinearLayout
    private lateinit var flipBtn: Button

    private val applyRunnable = Runnable {
        Backend.submit(this, seek.progress) { err ->
            runOnUiThread { if (err != null) status.text = "Apply failed: $err" else refreshStatus() }
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { start() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { start() } }

    private fun schedule(delayMs: Long) {
        ui.removeCallbacks(applyRunnable)
        ui.postDelayed(applyRunnable, delayMs)
    }

    private fun label(t: String, size: Float = 20f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isFocusable = true; setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 40, 80, 40)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101820"))
            addView(root)
        }

        root.addView(label("Overscan", 34f))
        status = label("", 16f).apply { setTextColor(Color.parseColor("#76B900")) }
        root.addView(status)

        // Live slider
        valueText = label("0.0%", 48f).apply { setPadding(0, 24, 0, 0) }
        root.addView(valueText)
        seek = SeekBar(this).apply {
            max = 300
            keyProgressIncrement = 5
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    valueText.text = Backend.fmt(p)
                    if (fromUser) schedule(if (Backend.direct) 20L else 700L)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        root.addView(seek)

        val fine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fine.addView(button("-0.1") { nudge(-1) })
        fine.addView(button("+0.1") { nudge(1) })
        fine.addView(button("Reset to 0") { seek.progress = 0; schedule(0) })
        flipBtn = button("Flip key direction") {
            Backend.setFlipped(this, !Backend.flipped(this)); refreshStatus()
        }
        fine.addView(flipBtn)
        root.addView(fine)

        // Save as profile
        root.addView(label("Save current value as a profile", 18f).apply { setPadding(0, 32, 0, 0) })
        val saveRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nameEdit = EditText(this).apply {
            hint = "Name (e.g. Gaming)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
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

        setContentView(scroll)
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

    private fun nudge(d: Int) {
        seek.progress = (seek.progress + d).coerceIn(0, 300)
        schedule(if (Backend.direct) 20L else 700L)
    }

    /** Check Shizuku, request permission if needed, start the helper, sync the slider. */
    private fun start() {
        if (!Backend.running()) { status.text = "Shizuku is not running. Start it, then reopen this app."; return }
        if (!Backend.granted()) { status.text = "Requesting Shizuku permission..."; Backend.requestPermission(); return }
        status.text = "Starting helper..."
        Thread {
            Backend.ensure(this)
            runOnUiThread {
                seek.progress = Backend.lastKnown(this).coerceIn(0, 300)
                valueText.text = Backend.fmt(seek.progress)
                refreshStatus()
            }
        }.start()
    }

    private fun refreshStatus() {
        status.text = Backend.note.ifEmpty { "Ready" }
        flipBtn.visibility = if (Backend.direct) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun renderProfiles() {
        list.removeAllViews()
        val ps = Profiles.all(this)
        if (ps.isEmpty()) list.addView(label("None yet.", 16f).apply { setTextColor(Color.GRAY) })
        for (p in ps) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(p.name, 22f).apply { minWidth = 360 })
            row.addView(label(Backend.fmt(p.t), 22f).apply { minWidth = 140 })
            row.addView(button("Apply") {
                seek.progress = p.t; valueText.text = Backend.fmt(p.t); schedule(0)
            })
            row.addView(button("Delete") { Profiles.delete(this, p.name); renderProfiles() })
            list.addView(row)
        }
    }
}
