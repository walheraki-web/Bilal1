package com.bilal.tv.subs

/** A parsed SRT/VTT subtitle file: a list of timed cues. */
class Srt(val cues: List<Cue>) {

    class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** The cue to show at movie time [ms], or null (binary search; cues sorted). */
    fun at(ms: Long): Cue? {
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                ms < c.startMs -> hi = mid - 1
                ms > c.endMs -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    companion object {
        /**
         * Parses SRT (and simple WebVTT). Tolerant of BOM, CRLF, blank gaps,
         * numeric indices, and "." or "," as the millisecond separator.
         */
        fun parse(raw: String): Srt {
            val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            val blocks = text.split(Regex("\n[ \t]*\n"))
            val cues = ArrayList<Cue>()
            val timing = Regex(
                """(\d{1,2}):(\d{2}):(\d{2})[.,](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[.,](\d{1,3})""",
            )
            for (block in blocks) {
                val lines = block.split("\n").filter { it.isNotBlank() || true }
                var timeLineIdx = -1
                var m: MatchResult? = null
                for ((i, line) in lines.withIndex()) {
                    val found = timing.find(line)
                    if (found != null) { timeLineIdx = i; m = found; break }
                }
                val match = m ?: continue
                val start = toMs(match.groupValues, 1)
                val end = toMs(match.groupValues, 5)
                if (end < start) continue
                val body = lines.drop(timeLineIdx + 1).joinToString("\n").trim()
                if (body.isEmpty()) continue
                cues += Cue(start, end, stripTags(body))
            }
            cues.sortBy { it.startMs }
            return Srt(cues)
        }

        private fun toMs(g: List<String>, i: Int): Long {
            val h = g[i].toLong(); val mi = g[i + 1].toLong(); val s = g[i + 2].toLong()
            val msRaw = g[i + 3]
            val ms = (msRaw + "000").substring(0, 3).toLong()
            return ((h * 60 + mi) * 60 + s) * 1000 + ms
        }

        private fun stripTags(s: String) =
            s.replace(Regex("<[^>]+>"), "").replace(Regex("\\{[^}]+\\}"), "").trim()
    }
}
