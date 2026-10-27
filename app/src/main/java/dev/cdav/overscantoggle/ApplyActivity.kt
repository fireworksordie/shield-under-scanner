package dev.cdav.overscantoggle

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Headless entry point for other tools. No UI; toasts the result and closes.
 *
 *   overscan://profile/<name>     apply a saved profile
 *   overscan://set/<tenths>       apply a raw value (25 = 2.5%)
 *   overscan://next               cycle to the next saved profile
 *   (launcher icon / no data)     same as next
 *
 * Intent extras work too, with action dev.cdav.overscantoggle.APPLY:
 *   profile (String), tenths (int), next (boolean)
 */
class ApplyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Backend.granted()) {
            Toast.makeText(this, "Overscan: grant Shizuku permission in the app first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val d = intent.data
        val seg = d?.lastPathSegment
        val profileName = (if (d?.host == "profile") seg else null) ?: intent.getStringExtra("profile")
        val rawValue = (if (d?.host == "set") seg?.toIntOrNull() else null)
            ?: intent.getIntExtra("tenths", -1).takeIf { it >= 0 }

        Thread {
            val msg: String = when {
                profileName != null -> {
                    val p = Profiles.find(this, profileName)
                    if (p == null) "No profile named \"$profileName\"" else run(p.name, p.t)
                }
                rawValue != null -> run(Backend.fmt(rawValue.coerceIn(0, 300)), rawValue)
                else -> {
                    Backend.ensure(this)
                    val p = Profiles.next(this, Backend.lastKnown(this))
                    if (p == null) "No saved profiles yet" else run(p.name, p.t)
                }
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }

    private fun run(label: String, t: Int): String {
        val err = Backend.applyNow(this, t)
        return if (err == null) "Overscan: $label (${Backend.fmt(t)})" else "Overscan failed: $err"
    }
}
