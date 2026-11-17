package io.github.roberbarras.racha.remote.cast

import io.github.roberbarras.racha.application.cast.CastConnector
import io.github.roberbarras.racha.application.cast.RemoteMedia
import io.github.roberbarras.racha.application.cast.RemotePlayer
import io.github.roberbarras.racha.application.cast.RemoteStatus
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.domain.cast.CastDevice
import io.github.roberbarras.racha.domain.cast.CastKind
import io.github.roberbarras.racha.domain.cast.CastMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** A UPnP MediaRenderer as its description says: who it is and where its AVTransport is controlled. */
internal data class RendererDescription(val udn: String, val name: String, val model: String?, val avTransport: String)

internal object UpnpXml {
    private val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Descriptions come from any device on the network: no DTDs or external entities.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        isExpandEntityReferences = false
    }

    fun parse(xml: String): Element = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement

    fun Element.all(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun Element.text(name: String): String? = all(name).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    /** The renderer in a device description fetched from [location]; null if it has no AVTransport. */
    fun renderer(location: String, xml: String): RendererDescription? {
        val root = parse(xml)
        val device = root.all("device").firstOrNull { d -> d.all("serviceType").any { "AVTransport" in it.textContent } } ?: return null
        val service = device.all("service").firstOrNull { s -> s.text("serviceType")?.contains("AVTransport") == true } ?: return null
        val control = service.text("controlURL") ?: return null
        val base = root.text("URLBase") ?: location
        return RendererDescription(
            udn = device.text("UDN") ?: location,
            name = device.text("friendlyName") ?: URI(location).host,
            model = device.text("modelName"),
            avTransport = URI(base).resolve(control).toString(),
        )
    }

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

/** TVs and renderers through UPnP AV (DLNA), found by SSDP: SOAP to their AVTransport. */
class DlnaConnector : CastConnector {
    override suspend fun connect(device: CastDevice): RemotePlayer = withContext(Dispatchers.IO) {
        val description = LanHttp.request(device.address)
        if (description.status != 200) throw IOException("description: HTTP ${description.status}")
        val renderer = UpnpXml.renderer(device.address, description.body) ?: throw IOException("not a media renderer")
        DlnaPlayer(renderer.avTransport)
    }

    companion object {
        internal fun deviceOf(location: String, xml: String): CastDevice? =
            UpnpXml.renderer(location, xml)?.let { CastDevice(it.udn, it.name, CastKind.DLNA, location, it.model) }
    }
}

class DlnaPlayer internal constructor(private val control: String) : RemotePlayer {
    override val supportsSpeed: Boolean = false
    private var lastPosition = 0L
    private var lastDuration: Long? = null
    private var started = false

    override suspend fun load(media: RemoteMedia, play: Boolean) = withContext(Dispatchers.IO) {
        runCatching { soap("Stop", "<InstanceID>0</InstanceID>") }
        soap("SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${UpnpXml.escape(media.url)}</CurrentURI>" +
            "<CurrentURIMetaData>${UpnpXml.escape(didl(media))}</CurrentURIMetaData>")
        soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        started = false
        lastPosition = media.startMs
        if (media.startMs > 0 || !play) {
            // Seeking only works once the TV has opened the file.
            for (attempt in 1..30) {
                val state = transportState()
                if (state == "PLAYING" || state == "PAUSED_PLAYBACK") break
                delay(500)
            }
            if (media.startMs > 0) runCatching { seekNow(media.startMs) }
            if (!play) soap("Pause", "<InstanceID>0</InstanceID>")
        }
    }

    override suspend fun play() = withContext(Dispatchers.IO) { soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"); Unit }
    override suspend fun pause() = withContext(Dispatchers.IO) { soap("Pause", "<InstanceID>0</InstanceID>"); Unit }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) { seekNow(positionMs) }
    override suspend fun setSpeed(speed: Float) = Unit

    private fun seekNow(positionMs: Long) {
        soap("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${CastMedia.upnpTime(positionMs)}</Target>")
    }

    override suspend fun status(): RemoteStatus = withContext(Dispatchers.IO) {
        val state = transportState()
        val position = UpnpXml.parse(soap("GetPositionInfo", "<InstanceID>0</InstanceID>"))
        val at = CastMedia.parseUpnpTime(position.let { with(UpnpXml) { it.text("RelTime") } }) ?: lastPosition
        val duration = CastMedia.parseUpnpTime(with(UpnpXml) { position.text("TrackDuration") })?.takeIf { it > 0 } ?: lastDuration
        val before = lastPosition
        lastPosition = at
        lastDuration = duration
        when (state) {
            "PLAYING" -> RemoteStatus(EngineState.READY, true, at, duration).also { started = true }
            "PAUSED_PLAYBACK" -> RemoteStatus(EngineState.READY, false, at, duration).also { started = true }
            "TRANSITIONING" -> RemoteStatus(EngineState.BUFFERING, true, at, duration)
            // TVs say STOPPED whether it finished or was stopped: it finished if it was at the end (some TVs reset the
            // position to 0 when they stop, so the previous report counts too).
            "STOPPED", "NO_MEDIA_PRESENT" ->
                if (started && duration != null && maxOf(at, before) >= duration - END_MARGIN_MS) {
                    RemoteStatus(EngineState.ENDED, false, duration, duration)
                } else {
                    RemoteStatus(EngineState.IDLE, false, at, duration)
                }
            else -> RemoteStatus(EngineState.BUFFERING, false, at, duration)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) { runCatching { soap("Stop", "<InstanceID>0</InstanceID>") }; Unit }

    override fun close() = Unit

    private fun transportState(): String? =
        with(UpnpXml) { parse(soap("GetTransportInfo", "<InstanceID>0</InstanceID>")).text("CurrentTransportState") }

    private fun soap(action: String, arguments: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$SERVICE">$arguments</u:$action></s:Body></s:Envelope>"""
        val response = LanHttp.request(control, "POST", mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPACTION" to "\"$SERVICE#$action\"",
        ), body)
        if (response.status != 200) throw IOException("$action: HTTP ${response.status}")
        return response.body
    }

    companion object {
        private const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val END_MARGIN_MS = 5_000L

        /** DIDL-Lite metadata: title, the video and, for TVs that show external subtitles (Samsung, LG…), the `.srt`. */
        internal fun didl(media: RemoteMedia): String = buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" """)
            append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/">""")
            append("""<item id="0" parentID="-1" restricted="1"><dc:title>${UpnpXml.escape(media.title)}</dc:title>""")
            append("<upnp:class>object.item.${if (media.contentType.startsWith("audio")) "audioItem.musicTrack" else "videoItem"}</upnp:class>")
            append("""<res protocolInfo="http-get:*:${media.contentType}:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000">""")
            append(UpnpXml.escape(media.url)).append("</res>")
            media.subtitle?.let { s ->
                append("""<res protocolInfo="http-get:*:text/srt:*">${UpnpXml.escape(s.url)}</res>""")
                append("""<sec:CaptionInfoEx sec:type="srt">${UpnpXml.escape(s.url)}</sec:CaptionInfoEx>""")
            }
            append("</item></DIDL-Lite>")
        }
    }
}

/** Chromecasts and DLNA TVs, each to its own protocol. */
class CastConnectors(
    private val chromecast: CastConnector = ChromecastConnector(),
    private val dlna: CastConnector = DlnaConnector(),
) : CastConnector {
    override suspend fun connect(device: CastDevice): RemotePlayer = when (device.kind) {
        CastKind.CHROMECAST -> chromecast.connect(device)
        CastKind.DLNA -> dlna.connect(device)
    }
}
