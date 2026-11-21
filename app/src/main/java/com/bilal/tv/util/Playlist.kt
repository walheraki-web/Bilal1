package com.bilal.tv.util

/**
 * Parses a station list from an .m3u/.m3u8 playlist or a tolerant .cfg/text
 * format, into [Station]s to bulk-import.
 *
 * Supported:
 *  - Extended M3U:  #EXTINF:-1 ...,Station Name  \n  http://url
 *  - Plain M3U:     a bare URL per line (name derived from the URL)
 *  - cfg/text:      "Name,URL"  |  "Name|URL"  |  "Name=URL"  |  "Name <tab> URL"
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
                line.startsWith("#") -> { /* #EXTM3U and other directives */ }
                isUrl(line) -> {
                    val name = pendingName?.takeIf { it.isNotEmpty() } ?: nameFromUrl(line)
                    out += Station(name, line)
                    pendingName = null
                }
                else -> {
                    val sep = line.indexOfFirst { it == ',' || it == '|' || it == '=' || it == '\t' }
                    if (sep > 0) {
                        val n = line.substring(0, sep).trim()
                        val u = line.substring(sep + 1).trim()
                        if (isUrl(u)) {
                            out += Station(if (n.isNotEmpty()) n else nameFromUrl(u), u)
                            pendingName = null
                            continue
                        }
                    }
                    // Otherwise treat as a name waiting for its URL on the next line.
                    pendingName = line
                }
            }
        }
        return out.distinctBy { it.url }
    }

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
