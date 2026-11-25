package com.bilal.tv.util

/**
 * Parses a station list from an .m3u/.m3u8 playlist or a tolerant .cfg/text
 * format, into [Station]s to bulk-import.
 *
 * Supported:
 *  - Extended M3U:  #EXTINF:-1 ...,Station Name  \n  http://url
 *  - Plain M3U:     a bare URL per line (name derived from the URL)
 *  - cfg/text:      "Name,URL" | "Name|URL" | "Name=URL" | "Name <tab> URL"
 *  - ipaudio cfg:   "0|Station Name|http://url#"  (flag | name | url, trailing #)
 *                   or a name line followed by a URL line
 */
object Playlist {

    fun parse(raw: String): List<Station> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Station>()
        var pendingName: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", true) -> {
                    val comma = line.lastIndexOf(',')
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else null
                }
                line.startsWith("#") -> { /* #EXTM3U, "# ipaudio:" header, and other directives */ }
                isUrl(cleanUrl(line)) -> {
                    val name = pendingName?.takeIf { it.isNotEmpty() } ?: nameFromUrl(cleanUrl(line))
                    out += Station(name, cleanUrl(line))
                    pendingName = null
                }
                line.contains('|') -> {
                    // flag|name|url  or  name|url  (trailing # allowed)
                    val parts = line.split('|').map { it.trim() }
                    val urlIdx = parts.indexOfLast { isUrl(cleanUrl(it)) }
                    if (urlIdx >= 0) {
                        val url = cleanUrl(parts[urlIdx])
                        val name = (urlIdx - 1 downTo 0)
                            .map { parts[it] }
                            .firstOrNull { it.isNotEmpty() && !isFlag(it) } ?: nameFromUrl(url)
                        out += Station(name, url)
                        pendingName = null
                    } else {
                        pendingName = line
                    }
                }
                else -> {
                    val sep = line.indexOfFirst { it == ',' || it == '=' || it == '\t' }
                    if (sep > 0) {
                        val n = line.substring(0, sep).trim()
                        val u = cleanUrl(line.substring(sep + 1).trim())
                        if (isUrl(u)) {
                            out += Station(if (n.isNotEmpty()) n else nameFromUrl(u), u)
                            pendingName = null
                            continue
                        }
                    }
                    pendingName = line
                }
            }
        }
        return out.distinctBy { it.url }
    }

    /**
     * Extracts TV channels (name + EPG id) from an extended M3U, reading the
     * tvg-id / tvg-name attributes on each #EXTINF line. Channels without a
     * tvg-id are skipped — the id is what pairs a channel with the guide.
     */
    fun parseChannels(raw: String): List<TvChannel> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<TvChannel>()
        for (line in text.split("\n")) {
            val l = line.trim()
            if (!l.startsWith("#EXTINF", true)) continue
            val id = attr(l, "tvg-id")?.takeIf { it.isNotEmpty() } ?: continue
            val comma = l.lastIndexOf(',')
            val display = if (comma >= 0) l.substring(comma + 1).trim() else ""
            val name = display.ifEmpty { attr(l, "tvg-name") ?: id }
            out += TvChannel(name, id)
        }
        return out.distinctBy { it.epgId }
    }

    /** Reads an attribute value like tvg-id="canal.fr" from an #EXTINF line. */
    private fun attr(line: String, key: String): String? {
        val k = "$key=\""
        val i = line.indexOf(k, ignoreCase = true)
        if (i < 0) return null
        val start = i + k.length
        val end = line.indexOf('"', start)
        return if (end > start) line.substring(start, end) else null
    }

    /** Strips a trailing '#' (and whitespace) that some cfg formats append to URLs. */
    private fun cleanUrl(s: String): String = s.trim().trimEnd('#').trim()

    /** A short leading flag field such as "0", "1", "k" — not a name. */
    private fun isFlag(s: String): Boolean = s.length <= 2 && s.all { it.isLetterOrDigit() }

    private fun isUrl(s: String): Boolean {
        val l = s.lowercase()
        return l.startsWith("http://") || l.startsWith("https://") ||
            l.startsWith("rtmp://") || l.startsWith("rtsp://") || l.startsWith("udp://")
    }

    private fun nameFromUrl(url: String): String {
        val noQuery = url.substringBefore('?')
        val seg = noQuery.trimEnd('/').substringAfterLast('/')
        val base = seg.substringBeforeLast('.').ifEmpty { noQuery.substringAfter("://").substringBefore('/') }
        return base.ifEmpty { "محطة" }
    }
}
