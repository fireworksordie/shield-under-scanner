package dev.cdav.overscantoggle

import android.content.pm.PackageManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import rikka.shizuku.Shizuku

/**
 * Sets the Shield's underscan (NVIDIA calls it overscan compensation) in tenths of a percent (25 = 2.5%).
 *
 * The only writer for this setting is NVIDIA's OverscanComp app, which runs as the system user and talks to the
 * display HAL; neither the shell user nor a normal app may do that. So we operate that app's slider with key
 * presses, and treat the persisted value as the source of truth:
 *
 *   1. read the current value from the system property the stock app maintains,
 *   2. open the stock screen (its slider starts at that same value),
 *   3. press exactly the number of steps needed, in the right direction,
 *   4. read the property again to confirm, and correct if it did not land on the target.
 */
object Backend {
    const val MAX = 300                                       // 30.0%, the stock slider's range

    private const val PROP = "persist.vendor.tegra.hdmi.underscan"   // fraction: 0.138 = 13.8%
    private const val STOCK_PKG = "com.nvidia.overscancomp"
    private const val STOCK_ACTIVITY = "$STOCK_PKG/.OverscanCompActivity"
    private const val KEY_INCREASE = 20                       // DPAD_DOWN raises the percentage (shrinks the picture)
    private const val KEY_DECREASE = 19                       // DPAD_UP lowers it
    private const val KEY_BACK = 4
    // NEW_TASK | EXCLUDE_FROM_RECENTS: the stock screen is force-stopped first, so this always creates a fresh task
    // and the flag keeps it out of the recents list.
    private const val STOCK_LAUNCH_FLAGS = "0x10800000"
    private const val OPEN_SETTLE_MS = 600L                   // time for the stock screen to read the hardware value
    private const val MAX_ROUNDS = 3

    private val exec = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(-1)

    fun fmt(t: Int) = "${t / 10}.${t % 10}%"

    fun running() = Shizuku.pingBinder()
    fun granted() = running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    fun requestPermission() = Shizuku.requestPermission(0)

    private fun newProcess(args: Array<String>): Process {
        // Shizuku.newProcess is private in API 13; reflection is the documented workaround.
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, args, null, null) as Process
    }

    /** Runs a command as the shell user through Shizuku and returns its exit code and stdout. */
    private fun shell(cmd: String): Pair<Int, String> {
        val p = newProcess(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        return p.waitFor() to out
    }

    /** Current underscan in tenths of a percent, read from the system property; null if unreadable. */
    fun readCurrent(): Int? {
        val (rc, out) = shell("getprop $PROP")
        if (rc != 0) return null
        val text = out.trim()
        if (text.isEmpty()) return 0                          // never set: no compensation
        val fraction = text.toFloatOrNull() ?: return null
        return (fraction * 1000f).roundToInt().coerceIn(0, MAX)
    }

    private fun press(key: Int, count: Int) {
        if (count <= 0) return
        shell("input keyevent " + List(count) { key }.joinToString(" "))
    }

    /** Polls until the property reaches [target] or the timeout passes; returns the last value read. */
    private fun awaitValue(target: Int, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Int? = readCurrent()
        while (last != target && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            last = readCurrent()
        }
        return last
    }

    /** Blocking. Returns null on success, or an error message. [bringBack] returns to this app afterwards. */
    @Synchronized
    fun applyNow(target: Int, bringBack: Boolean = false): String? {
        val t = target.coerceIn(0, MAX)
        var cur = try { readCurrent() } catch (e: Exception) { null }
            ?: return "Could not read the current underscan value"
        if (cur == t) return null

        return try {
            shell("am force-stop $STOCK_PKG; am start -W -f $STOCK_LAUNCH_FLAGS -n $STOCK_ACTIVITY")
            Thread.sleep(OPEN_SETTLE_MS)

            var round = 0
            while (cur != t && round < MAX_ROUNDS) {
                val delta = t - cur
                press(if (delta > 0) KEY_INCREASE else KEY_DECREASE, abs(delta))
                cur = awaitValue(t, 1500L + abs(delta) * 15L) ?: cur
                round++
            }

            shell("input keyevent $KEY_BACK" + if (bringBack) "; am start -n dev.cdav.overscantoggle/.MainActivity" else "")
            if (cur == t) null else "Ended at ${fmt(cur)}, wanted ${fmt(t)}"
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    /** Non-blocking; if called repeatedly (slider drag) only the newest value is applied. */
    fun submit(target: Int, bringBack: Boolean = false, done: (String?) -> Unit) {
        pending.set(target)
        exec.execute {
            val v = pending.getAndSet(-1)
            if (v >= 0) done(applyNow(v, bringBack))
        }
    }
}
