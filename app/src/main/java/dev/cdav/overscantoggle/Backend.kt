package dev.cdav.overscantoggle

import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

/**
 * Applies an overscan value (tenths of a percent, 25 = 2.5%) through Shizuku.
 *  - Direct mode:   a shell-user helper process calls NVIDIA's display HAL. Instant, works while you slide.
 *  - Key mode:      fallback that drives the stock OverscanComp app with key presses. Slow, applies after you stop.
 */
object Backend {
    private const val PREFS = "overscan"
    private const val STOCK_ACTIVITY = "com.nvidia.overscancomp/.OverscanCompActivity"

    @Volatile var direct = false
    @Volatile var note = ""
    @Volatile var tenths = -1

    private var proc: Process? = null
    private var out: OutputStreamWriter? = null
    private var inp: BufferedReader? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(-1)

    fun fmt(t: Int) = "${t / 10}.${t % 10}%"

    fun running() = Shizuku.pingBinder()
    fun granted() = running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    fun requestPermission() = Shizuku.requestPermission(0)

    fun flipped(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("flip", false)
    fun setFlipped(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("flip", v).apply()

    fun lastKnown(ctx: Context): Int =
        if (tenths >= 0) tenths else ctx.getSharedPreferences(PREFS, 0).getInt("cur", 0)

    private fun remember(ctx: Context, t: Int) {
        tenths = t
        ctx.getSharedPreferences(PREFS, 0).edit().putInt("cur", t).apply()
    }

    private fun newProcess(args: Array<String>): Process {
        // Shizuku.newProcess is private in API 13; reflection is the documented workaround.
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, args, null, null) as Process
    }

    /** One request/response with the helper. Skips any non-protocol output (JVM noise). */
    @Synchronized
    private fun rawRequest(line: String): String {
        return try {
            out!!.write(line + "\n")
            out!!.flush()
            var noise = ""
            while (true) {
                val l = inp!!.readLine() ?: return "@ERR helper exited. $noise"
                if (l.startsWith("@")) return l
                noise = l
            }
            ""
        } catch (t: Throwable) {
            proc?.destroy(); proc = null
            "@ERR ${t.message}"
        }
    }

    /** Shizuku's remote process throws IllegalArgumentException (not IllegalThreadStateException) while running. */
    private fun helperAlive(): Boolean {
        val p = proc ?: return false
        return try {
            p.exitValue()
            false
        } catch (t: Throwable) {
            true
        }
    }

    /** Starts the helper if needed and works out which mode we are in. Call off the main thread. */
    @Synchronized
    fun ensure(ctx: Context) {
        if (helperAlive()) return
        direct = false
        try {
            val apk = ctx.applicationContext.applicationInfo.sourceDir
            val p = newProcess(
                arrayOf(
                    "sh", "-c",
                    "exec app_process -Djava.class.path=$apk /system/bin --nice-name=overscan_helper dev.cdav.overscantoggle.Helper 2>&1"
                )
            )
            proc = p
            out = OutputStreamWriter(p.outputStream)
            inp = BufferedReader(InputStreamReader(p.inputStream))
            val r = rawRequest("G")
            if (r.startsWith("@VAL ")) {
                direct = true
                tenths = r.substring(5).trim().toInt()
                note = "Direct mode (live preview)"
            } else {
                note = "Key-press fallback. Direct call failed: ${r.removePrefix("@ERR ")}"
            }
        } catch (t: Throwable) {
            note = "Could not start helper: ${t.message}"
        }
    }

    private fun applyKeys(ctx: Context, n: Int, bringBack: Boolean): String? {
        val inc = if (flipped(ctx)) 19 else 20
        val dec = if (flipped(ctx)) 20 else 19
        // The stock screen opens with its slider at the CURRENT hardware value and logs it
        // ("percentageToSeekbarVal: percentage=<pct>"). Read that, then press exactly the steps needed.
        // If the value can't be read, fall back to slamming the slider to the nearer end first.
        val rail = (if (n <= 150) List(300) { dec } + List(n) { inc } else List(300) { inc } + List(300 - n) { dec })
            .joinToString(" ")
        val back = if (bringBack) "; am start -n dev.cdav.overscantoggle/.MainActivity" else ""
        val script = """
am force-stop com.nvidia.overscancomp
logcat -c
am start -W -n $STOCK_ACTIVITY >/dev/null
sleep 0.6
T=$n
v=§(logcat -d -s OverscanCompActivity | grep -m1 'percentageToSeekbarVal: percentage=' | sed 's/.*percentage=//')
OK=0
if [ -n "§v" ]; then
  case "§v" in *.*) ;; *) v="§v.0";; esac
  ip=§{v%%.*}; fp="§{v#*.}00"
  d1=§(echo "§fp" | cut -c1); d2=§(echo "§fp" | cut -c2)
  case "§ip§d1§d2" in ''|*[!0-9]*) OK=0;; *) OK=1;; esac
fi
if [ "§OK" = 1 ]; then
  cur=§((ip*10+d1)); [ "§d2" -ge 5 ] && cur=§((cur+1))
  if [ "§cur" -lt "§T" ]; then d=§((T-cur)); K=$inc; else d=§((cur-T)); K=$dec; fi
  keys=""; i=0
  while [ "§i" -lt "§d" ]; do keys="§keys §K"; i=§((i+1)); done
  if [ -n "§keys" ]; then input keyevent §keys; fi
else
  input keyevent $rail
fi
sleep 0.3
input keyevent 4$back
""".trimIndent().replace('§', '$')
        val cmd = script
        val rc = newProcess(arrayOf("sh", "-c", cmd)).waitFor()
        return if (rc == 0) null else "Key command failed (exit $rc)"
    }

    /** Blocking apply. Returns null on success, or an error message. */
    fun applyNow(ctx: Context, n: Int, bringBack: Boolean = false): String? {
        val app = ctx.applicationContext
        ensure(app)
        val v = n.coerceIn(0, 300)
        val err = try {
            if (direct) {
                val r = rawRequest("S $v")
                if (r.startsWith("@OK")) null else r.removePrefix("@ERR ")
            } else applyKeys(app, v, bringBack)
        } catch (t: Throwable) {
            t.message ?: t.toString()
        }
        if (err == null) remember(app, v)
        return err
    }

    /** Non-blocking; if called repeatedly (slider drag) only the newest value is applied. */
    fun submit(ctx: Context, n: Int, bringBack: Boolean = false, done: (String?) -> Unit) {
        pending.set(n)
        val app = ctx.applicationContext
        exec.execute {
            val v = pending.getAndSet(-1)
            if (v >= 0) done(applyNow(app, v, bringBack))
        }
    }
}
