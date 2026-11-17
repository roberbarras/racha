package io.github.roberbarras.racha.domain.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class CastMediaTest {
    @Test
    fun subRipBecomesWebVtt() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:04,000\r\nHola, ¿qué tal?\r\n\r\n2\r\n00:01:02,05 --> 00:01:03,100\r\nAdiós\r\n"
        assertEquals("WEBVTT\n\n1\n00:00:01.500 --> 00:00:04.000\nHola, ¿qué tal?\n\n2\n00:01:02.05 --> 00:01:03.100\nAdiós\n", CastMedia.srtToVtt(srt))
    }

    @Test
    fun upnpTimesBothWays() {
        assertEquals("0:00:00", CastMedia.upnpTime(-5))
        assertEquals("1:02:03", CastMedia.upnpTime(3_723_999))
        assertEquals(3_723_000, CastMedia.parseUpnpTime("1:02:03"))
        assertEquals(3_723_500, CastMedia.parseUpnpTime("01:02:03.500"))
        assertEquals(null, CastMedia.parseUpnpTime("NOT_IMPLEMENTED"))
    }

    @Test
    fun typesAndHosts() {
        assertEquals("video/mp4", CastMedia.contentType("001 Intro.MP4"))
        assertEquals("video/x-matroska", CastMedia.contentType("a.mkv"))
        assertEquals("application/octet-stream", CastMedia.contentType("noext"))
        assertEquals("192.168.1.20", CastDevice("x", "Salón", CastKind.CHROMECAST, "192.168.1.20:8009").host)
        assertEquals("192.168.1.30", CastDevice("y", "TV", CastKind.DLNA, "http://192.168.1.30:9197/dmr").host)
    }
}
