package com.bilal.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgTest {

    private val xml = """
        <tv>
          <channel id="canal.fr"><display-name>Canal+</display-name></channel>
          <programme start="20261125200000 +0000" stop="20261125220000 +0000" channel="canal.fr">
            <title>PSG vs Marseille</title>
          </programme>
          <programme start="20261125220000 +0000" stop="20261125233000 +0000" channel="canal.fr">
            <title>Post-match</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test fun parsesProgrammes() {
        val p = Epg.parse(xml)
        assertEquals(2, p.size)
        assertEquals("canal.fr", p[0].channel)
        assertEquals("PSG vs Marseille", p[0].title)
    }

    @Test fun parsesXmltvTime() {
        // 2026-11-25 20:00:00 UTC
        val t = Epg.parseTime("20261125200000 +0000")!!
        assertEquals(1795636800000L, t)
    }

    @Test fun offsetConvertedToUtc() {
        // 22:00 at +0200 is 20:00 UTC — same instant as the +0000 20:00 above.
        val utc = Epg.parseTime("20261125200000 +0000")!!
        val plus2 = Epg.parseTime("20261125220000 +0200")!!
        assertEquals(utc, plus2)
    }

    @Test fun currentTitlePicksTheLiveProgramme() {
        val p = Epg.parse(xml)
        val during = Epg.parseTime("20261125210000 +0000")!! // 21:00, inside first
        assertEquals("PSG vs Marseille", Epg.currentTitle(p, "canal.fr", during))
        val later = Epg.parseTime("20261125222000 +0000")!! // 22:20, inside second
        assertEquals("Post-match", Epg.currentTitle(p, "canal.fr", later))
    }

    @Test fun unknownChannelHasNoTitle() {
        assertNull(Epg.currentTitle(Epg.parse(xml), "other.id", System.currentTimeMillis()))
    }

    @Test fun parsesChannelsFromM3u() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="canal.fr" tvg-name="Canal Plus",Canal+ HD
            http://x/canal
            #EXTINF:-1 tvg-name="No Id Channel",No Id
            http://x/noid
        """.trimIndent()
        val chans = Playlist.parseChannels(m3u)
        assertEquals(1, chans.size)
        assertEquals("canal.fr", chans[0].epgId)
        assertTrue(chans[0].name.contains("Canal"))
    }
}
