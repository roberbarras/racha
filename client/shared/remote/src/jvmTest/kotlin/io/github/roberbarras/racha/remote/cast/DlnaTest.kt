package io.github.roberbarras.racha.remote.cast

import com.sun.net.httpserver.HttpServer
import io.github.roberbarras.racha.application.cast.RemoteMedia
import io.github.roberbarras.racha.application.cast.RemoteSubtitle
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.domain.cast.CastKind
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UPnP AV against a simulated TV (H18), plus the SSDP and mDNS answers discovery reads. */
class DlnaTest {
    private val actions: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var transport = "NO_MEDIA_PRESENT"
    @Volatile private var relTime = "0:00:00"
    private val tv = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/dmr/desc.xml") { ex ->
            val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>[TV] Salón</friendlyName>
                <modelName>QE55</modelName><UDN>uuid:tv-1</UDN><serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>ctl/av</controlURL></service>
                </serviceList></device></root>""".toByteArray()
            ex.sendResponseHeaders(200, xml.size.toLong()); ex.responseBody.use { it.write(xml) }
        }
        createContext("/dmr/ctl/av") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
            val body = ex.requestBody.readBytes().decodeToString()
            actions += action
            bodies += body
            when (action) {
                "Play" -> transport = "PLAYING"
                "Pause" -> transport = "PAUSED_PLAYBACK"
                "Stop" -> transport = "STOPPED"
                "Seek" -> relTime = body.substringAfter("<Target>").substringBefore("</Target>")
            }
            val result = when (action) {
                "GetTransportInfo" -> "<CurrentTransportState>$transport</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
                "GetPositionInfo" -> "<TrackDuration>0:10:00</TrackDuration><RelTime>$relTime</RelTime>"
                else -> ""
            }
            val xml = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$result</u:${action}Response></s:Body></s:Envelope>""".toByteArray()
            ex.sendResponseHeaders(200, xml.size.toLong()); ex.responseBody.use { it.write(xml) }
        }
        start()
    }
    private val location = "http://127.0.0.1:${tv.address.port}/dmr/desc.xml"

    @AfterTest
    fun cleanUp() = tv.stop(0)

    @Test
    fun aTvIsDescribedLoadedAtThePositionDrivenAndFinishes() = runBlocking<Unit> {
        val device = NetworkCastDiscovery.describeRenderer(location)!!
        assertEquals("uuid:tv-1", device.id)
        assertEquals("[TV] Salón", device.name)
        assertEquals(CastKind.DLNA, device.kind)
        assertEquals("QE55", device.model)

        val player = DlnaConnector().connect(device)
        player.load(RemoteMedia("http://10.0.0.5:4000/t/v.mp4", "video/mp4", "IAM & roles", RemoteSubtitle("http://10.0.0.5:4000/t/v.srt", "es", "Español"), 754_000), play = true)
        assertEquals(listOf("Stop", "SetAVTransportURI", "Play", "GetTransportInfo", "Seek"), actions.toList())
        val set = bodies[1]
        assertTrue("<CurrentURI>http://10.0.0.5:4000/t/v.mp4</CurrentURI>" in set)
        assertTrue("IAM &amp;amp; roles" in set, "metadata is escaped inside the SOAP argument")
        assertTrue("sec:CaptionInfoEx" in set && "v.srt" in set, "subtitles for TVs that show them")
        assertEquals("0:12:34", relTime)

        val playing = player.status()
        assertEquals(EngineState.READY, playing.state)
        assertTrue(playing.playing)
        assertEquals(754_000, playing.positionMs)
        assertEquals(600_000, playing.durationMs)
        player.pause()
        assertEquals(false, player.status().playing)
        assertEquals(false, player.supportsSpeed)

        // Stopped at the end: finished. Stopped in the middle (someone pressed stop): not.
        relTime = "0:09:58"
        player.status()
        transport = "STOPPED"
        relTime = "0:00:00"
        assertEquals(EngineState.ENDED, player.status().state)
        player.load(RemoteMedia("http://10.0.0.5:4000/t/w.mp4", "video/mp4", "EC2", null, 0), play = true)
        relTime = "0:03:00"
        player.status()
        transport = "STOPPED"
        assertEquals(EngineState.IDLE, player.status().state)
    }

    @Test
    fun discoveryAnswers() {
        assertEquals(
            "http://192.168.1.30:9197/dmr",
            Ssdp.location("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.30:9197/dmr\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n"),
        )
        assertEquals(null, Ssdp.location("M-SEARCH * HTTP/1.1\r\n\r\n"))

        val answer = mdnsAnswer()
        val devices = Mdns.chromecasts(answer, InetAddress.getByName("192.168.1.99"))
        assertEquals(1, devices.size)
        val cast = devices.single()
        assertEquals("a1b2c3", cast.id)
        assertEquals("Salón", cast.name)
        assertEquals("192.168.1.20:8009", cast.address)
        assertEquals("Chromecast", cast.model)
        assertEquals(emptyList(), Mdns.chromecasts(byteArrayOf(0, 1, 2), InetAddress.getLoopbackAddress()))
    }

    /** A Chromecast's answer: PTR, then SRV/TXT/A in the additional section, with name compression. */
    private fun mdnsAnswer(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun name(vararg labels: String) { labels.forEach { out.write(it.length); out.write(it.toByteArray()) }; out.write(0) }
        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(3)
        val service = out.size()
        name("_googlecast", "_tcp", "local")
        u16(12); u16(1); u16(0); u16(120)
        val instanceData = java.io.ByteArrayOutputStream().apply { val l = "Chromecast-a1b2c3"; write(l.length); write(l.toByteArray()); write(0xC0); write(service) }.toByteArray()
        u16(instanceData.size); val instance = out.size(); out.write(instanceData)
        // SRV: priority, weight, port 8009, target host.local
        out.write(0xC0); out.write(instance); u16(33); u16(1); u16(0); u16(120)
        val target = java.io.ByteArrayOutputStream().apply { write(byteArrayOf(0, 0, 0, 0, 0x1F, 0x49)); val l = "a1b2c3"; write(l.length); write(l.toByteArray()); write(5); write("local".toByteArray()); write(0) }.toByteArray()
        u16(target.size); val hostName = out.size() + 6; out.write(target)
        // TXT
        out.write(0xC0); out.write(instance); u16(16); u16(1); u16(0); u16(120)
        val txt = java.io.ByteArrayOutputStream().apply { listOf("id=a1b2c3", "md=Chromecast", "fn=Salón").forEach { val b = it.toByteArray(); write(b.size); write(b) } }.toByteArray()
        u16(txt.size); out.write(txt)
        // A
        out.write(0xC0); out.write(hostName); u16(1); u16(1); u16(0); u16(120); u16(4); out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        return out.toByteArray()
    }
}
