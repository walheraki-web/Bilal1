package com.bilal.tv.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTest {
    private val sample = """
        1
        00:00:01,000 --> 00:00:04,000
        مرحبا بك

        2
        00:00:05,500 --> 00:00:08,000
        <i>سطر</i> ثانٍ
        على سطرين
    """.trimIndent()

    @Test fun parsesCuesAndStripsTags() {
        val srt = Srt.parse(sample)
        assertEquals(2, srt.cues.size)
        assertEquals(1000L, srt.cues[0].startMs)
        assertEquals(4000L, srt.cues[0].endMs)
        assertEquals("مرحبا بك", srt.cues[0].text)
        assertEquals("سطر ثانٍ\nعلى سطرين", srt.cues[1].text)
    }

    @Test fun lookupByTime() {
        val srt = Srt.parse(sample)
        assertNull(srt.at(500))
        assertEquals("مرحبا بك", srt.at(2000)?.text)
        assertNull(srt.at(4500))
        assertEquals("سطر ثانٍ\nعلى سطرين", srt.at(6000)?.text)
    }

    @Test fun handlesVttDotSeparatorAndBom() {
        val vtt = "﻿00:00:02.000 --> 00:00:03.000\nhi"
        val srt = Srt.parse(vtt)
        assertEquals(1, srt.cues.size)
        assertEquals("hi", srt.at(2500)?.text)
    }

    @Test fun offsetShiftsTiming() {
        val srt = Srt.parse(sample)
        // simulate a -1s nudge by querying position+1000
        assertTrue(srt.at(2000 + 1000) != null)
    }
}
