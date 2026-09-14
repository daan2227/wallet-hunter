package com.btcseedrecovery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Session(
    val date: String,
    val mode: String,
    val keysScanned: Long,
    val avgKps: Double,
    val durationSec: Long,
    val matches: Int
)

object SessionStats {
    private const val PREFS = "session_stats"
    private const val KEY   = "sessions"
    private const val MAX   = 50

    fun save(ctx: Context, s: Session) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch(e: Exception) { JSONArray() }
        val obj = JSONObject().apply {
            put("date", s.date); put("mode", s.mode)
            put("keys", s.keysScanned); put("kps", s.avgKps)
            put("dur", s.durationSec); put("matches", s.matches)
        }
        arr.put(obj)
        // Keep only last MAX
        val trimmed = JSONArray()
        val start = if (arr.length() > MAX) arr.length() - MAX else 0
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY, trimmed.toString()).apply()
    }

    fun load(ctx: Context): List<Session> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch(e: Exception) { return emptyList() }
        val list = ArrayList<Session>(arr.length())
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            list.add(Session(
                o.optString("date","?"), o.optString("mode","?"),
                o.optLong("keys",0), o.optDouble("kps",0.0),
                o.optLong("dur",0), o.optInt("matches",0)
            ))
        }
        return list
    }

    data class Aggregates(val totalKeys: Long, val totalMatches: Int, val bestKps: Double)

    fun aggregate(ctx: Context): Aggregates {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch(e: Exception) { return Aggregates(0, 0, 0.0) }
        var keys = 0L; var matches = 0; var best = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            keys    += o.optLong("keys", 0)
            matches += o.optInt("matches", 0)
            val kps  = o.optDouble("kps", 0.0); if (kps > best) best = kps
        }
        return Aggregates(keys, matches, best)
    }

    fun totalKeys(ctx: Context): Long = aggregate(ctx).totalKeys
    fun totalMatches(ctx: Context): Int = aggregate(ctx).totalMatches
    fun bestKps(ctx: Context): Double = aggregate(ctx).bestKps

    fun nowStr(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
