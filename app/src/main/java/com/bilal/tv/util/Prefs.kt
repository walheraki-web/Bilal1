package com.bilal.tv.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Station(val name: String, val url: String)

/** Typed access to Bilal's settings and per-station memory. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("bilal", Context.MODE_PRIVATE)

    var adbPort: Int
        get() = sp.getInt("adbPort", 5555)
        set(v) = sp.edit().putInt("adbPort", v).apply()

    /** Re-measure automatically after a network drop or a long audio stall. */
    var resyncAfterDrop: Boolean
        get() = sp.getBoolean("resyncAfterDrop", true)
        set(v) = sp.edit().putBoolean("resyncAfterDrop", v).apply()

    /** Re-measure automatically when the TV channel changes. */
    var resyncOnChannel: Boolean
        get() = sp.getBoolean("resyncOnChannel", false)
        set(v) = sp.edit().putBoolean("resyncOnChannel", v).apply()

    /** Mute the TV channel's own sound once the station is in sync. */
    var muteTvAfterSync: Boolean
        get() = sp.getBoolean("muteTvAfterSync", true)
        set(v) = sp.edit().putBoolean("muteTvAfterSync", v).apply()

    /** Allow Bilal to change the receiver's picture (video) delay. */
    var controlVideoDelay: Boolean
        get() = sp.getBoolean("controlVideoDelay", true)
        set(v) = sp.edit().putBoolean("controlVideoDelay", v).apply()

    /** Picture delay currently in force on the receiver, as best known. */
    var videoDelayMs: Int
        get() = sp.getInt("videoDelayMs", 0)
        set(v) = sp.edit().putInt("videoDelayMs", v).apply()

    var subtitleLang: String
        get() = sp.getString("subtitleLang", "en")!!
        set(v) = sp.edit().putString("subtitleLang", v).apply()

    /** Last SRT source (URL or local path). */
    var subtitleSource: String?
        get() = sp.getString("subtitleSource", null)
        set(v) = sp.edit().putString("subtitleSource", v).apply()

    var lastStationUrl: String?
        get() = sp.getString("lastStationUrl", null)
        set(v) = sp.edit().putString("lastStationUrl", v).apply()

    fun stationDelay(url: String): Int = sp.getInt("delay:$url", 0)
    fun setStationDelay(url: String, ms: Int) = sp.edit().putInt("delay:$url", ms).apply()

    /** Last measured lag (station minus TV with no delays) for a station, if any. */
    fun lastLag(url: String): Int? = if (sp.contains("lag:$url")) sp.getInt("lag:$url", 0) else null
    fun setLastLag(url: String, lag: Int) = sp.edit().putInt("lag:$url", lag).apply()

    fun stationVolume(url: String): Float = sp.getFloat("vol:$url", 1f)
    fun setStationVolume(url: String, v: Float) = sp.edit().putFloat("vol:$url", v).apply()

    var stations: List<Station>
        get() {
            return runCatching {
                val arr = JSONArray(sp.getString("stations", "[]"))
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Station(o.getString("name"), o.getString("url"))
                }
            }.getOrDefault(emptyList())
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
            sp.edit().putString("stations", arr.toString()).apply()
        }
}
