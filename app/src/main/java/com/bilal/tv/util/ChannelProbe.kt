package com.bilal.tv.util

/**
 * Best-effort, no-privilege discovery of the receiver's currently-tuned channel.
 *
 * Runs only read-only commands that a normal app is allowed to execute as its own
 * uid: `getprop` (world-readable) and `content query` against the public settings
 * providers. No Shizuku, no root. The combined output lets us see whether this box
 * leaks the current channel somewhere readable; if it does, we automate the match.
 */
object ChannelProbe {

    private val probes = listOf(
        // System properties often carry the live service/channel on DVB boxes.
        "getprop | grep -iE 'channel|dtv|\\.tv\\.|program|service|freq|lcn|prog_num'",
        // Public settings providers are readable by an app's own uid.
        "content query --uri content://settings/system 2>/dev/null | grep -iE 'channel|dtv|program|tv_' ",
        "content query --uri content://settings/secure 2>/dev/null | grep -iE 'channel|dtv|program|tv_' ",
        "content query --uri content://settings/global 2>/dev/null | grep -iE 'channel|dtv|program|tv_' ",
        // DroidLogic / Android TV framework providers (may or may not be exported).
        "content query --uri content://android.media.tv/channel --projection _id:display_number:display_name 2>&1 | head -30",
        "content query --uri content://com.droidlogic.tvinput/channel 2>&1 | head -30",
        "content query --uri content://com.droidlogic.channels/channel 2>&1 | head -30",
    )

    fun run(): String {
        val sb = StringBuilder("— فحص محلي (بلا Shizuku) —\n\n")
        for (cmd in probes) {
            sb.append("\$ ").append(cmd.substringBefore(" 2>").take(70)).append('\n')
            val out = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val text = p.inputStream.bufferedReader().readText() +
                    p.errorStream.bufferedReader().readText()
                p.waitFor()
                text
            }.getOrElse { "  <خطأ: ${it.message}>\n" }
            sb.append(out.ifBlank { "  <فارغ>\n" }).append('\n')
        }
        return sb.toString()
    }
}
