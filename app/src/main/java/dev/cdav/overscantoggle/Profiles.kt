package dev.cdav.overscantoggle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Profiles {
    data class P(val name: String, val t: Int)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("profiles", 0)

    fun all(ctx: Context): List<P> {
        val arr = JSONArray(sp(ctx).getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            P(o.getString("name"), o.getInt("t"))
        }
    }

    private fun write(ctx: Context, l: List<P>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("name", it.name).put("t", it.t)) }
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun save(ctx: Context, name: String, t: Int) {
        val l = all(ctx).toMutableList()
        val i = l.indexOfFirst { it.name.equals(name, true) }
        if (i >= 0) l[i] = P(l[i].name, t) else l.add(P(name, t))
        write(ctx, l)
    }

    fun delete(ctx: Context, name: String) = write(ctx, all(ctx).filterNot { it.name.equals(name, true) })

    fun find(ctx: Context, name: String) = all(ctx).firstOrNull { it.name.equals(name, true) }

    /** The profile after the one matching the current value (wraps). First profile if none match. */
    fun next(ctx: Context, current: Int): P? {
        val l = all(ctx)
        if (l.isEmpty()) return null
        val i = l.indexOfFirst { it.t == current }
        return l[(i + 1) % l.size]
    }
}
