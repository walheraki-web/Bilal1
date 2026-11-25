package com.bilal.tv.util

import java.util.Calendar
import java.util.TimeZone

/**
 * Minimal XMLTV guide parser. Reads <programme> blocks (start/stop/channel +
 * <title>) with a tolerant string scan rather than a full XML parser, so the
 * same code runs on plain JVM for unit tests and handles the slightly irregular
 * XMLTV that IPTV providers emit.
 *
 * It answers one question: for the TV channel the user is watching (by EPG id),
 * what programme is on right now — e.g. the match title to pair with an Arabic
 * commentary station.
 */
object Epg {

    data class Programme(
        val channel: String,
        val startMs: Long,
        val stopMs: Long,
        val title: String,
    )

    fun parse(xml: String): List<Programme> {
        val out = ArrayList<Programme>()
        var i = 0
        while (true) {
            val open = xml.indexOf("<programme", i, ignoreCase = true)
            if (open < 0) break
            val headEnd = xml.indexOf('>', open)
            if (headEnd < 0) break
            val close = xml.indexOf("</programme>", headEnd, ignoreCase = true)
            if (close < 0) break
            val head = xml.substring(open, headEnd)
            val body = xml.substring(headEnd + 1, close)

            val channel = attr(head, "channel")
            val start = parseTime(attr(head, "start"))
            val stop = parseTime(attr(head, "stop"))
            val title = tag(body, "title")
            if (channel != null && start != null && title != null) {
                out += Programme(channel, start, stop ?: (start + 2 * 3600_000L), title.trim())
            }
            i = close + 12
        }
        return out
    }

    /** The programme on [epgId] at [nowMs], or the most recent one that has started. */
    fun currentTitle(programmes: List<Programme>, epgId: String, nowMs: Long): String? {
        val forChannel = programmes.filter { it.channel.equals(epgId, ignoreCase = true) }
        if (forChannel.isEmpty()) return null
        forChannel.firstOrNull { nowMs in it.startMs until it.stopMs }?.let { return it.title }
        return forChannel.filter { it.startMs <= nowMs }.maxByOrNull { it.startMs }?.title
    }

    private fun attr(head: String, key: String): String? {
        val k = "$key=\""
        val i = head.indexOf(k, ignoreCase = true)
        if (i < 0) return null
        val s = i + k.length
        val e = head.indexOf('"', s)
        return if (e > s) head.substring(s, e) else null
    }

    private fun tag(body: String, name: String): String? {
        val open = body.indexOf("<$name", ignoreCase = true)
        if (open < 0) return null
        val gt = body.indexOf('>', open)
        if (gt < 0) return null
        val end = body.indexOf("</$name>", gt, ignoreCase = true)
        if (end < 0) return null
        return unescape(body.substring(gt + 1, end))
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    /** XMLTV time: "yyyyMMddHHmmss" optionally followed by " +HHmm" offset. */
    fun parseTime(raw: String?): Long? {
        if (raw == null) return null
        val t = raw.trim()
        val digits = t.takeWhile { it.isDigit() }
        if (digits.length < 14) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val min = digits.substring(10, 12).toInt()
        val sec = digits.substring(12, 14).toInt()

        val rest = t.substring(digits.length).trim()
        val tz = when {
            rest.length >= 5 && (rest[0] == '+' || rest[0] == '-') -> {
                val sign = if (rest[0] == '-') -1 else 1
                val oh = rest.substring(1, 3).toIntOrNull() ?: 0
                val om = rest.substring(3, 5).toIntOrNull() ?: 0
                TimeZone.getTimeZone("GMT").also { /* offset applied below */ }
                    .let { sign to (oh * 60 + om) }
            }
            else -> null
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
        cal.clear()
        cal.set(year, month - 1, day, hour, min, sec)
        var ms = cal.timeInMillis
        if (tz != null) ms -= tz.first * tz.second * 60_000L // convert local-with-offset to UTC
        return ms
    }
}
