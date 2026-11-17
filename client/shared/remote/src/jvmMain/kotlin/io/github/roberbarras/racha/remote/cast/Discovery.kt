package io.github.roberbarras.racha.remote.cast

import io.github.roberbarras.racha.application.cast.CastDiscovery
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.cast.CastDevice
import io.github.roberbarras.racha.domain.cast.CastKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Chromecasts (mDNS, `_googlecast._tcp`) and DLNA renderers (SSDP, AVTransport) on the local network, while the flow
 * is collected: a question every few seconds on each IPv4 interface, answers gathered into one list. Questions are
 * sent from an ordinary port, so answers come back to it directly (no listening on the shared multicast ports).
 * [multicastLock] lets Android receive multicast while searching (Wi-Fi drops it otherwise to save battery).
 */
class NetworkCastDiscovery(
    private val multicastLock: () -> AutoCloseable = { AutoCloseable {} },
    private val describe: (String) -> CastDevice? = ::describeRenderer,
    private val intervalMs: Long = 4_000,
) : CastDiscovery {
    override fun devices(): Flow<List<CastDevice>> = callbackFlow {
        val found = ConcurrentHashMap<String, CastDevice>()
        val described = ConcurrentHashMap.newKeySet<String>()
        fun publish(device: CastDevice) {
            if (found.put(device.id, device) != device) trySend(found.values.sortedWith(compareBy({ it.kind }, { it.name.lowercase() })))
        }
        trySend(emptyList())
        val lock = multicastLock()
        val sockets = interfaces().mapNotNull { address -> runCatching { DatagramSocket(InetSocketAddress(address, 0)).apply { soTimeout = 500 } }.getOrNull() }
        sockets.forEach { socket ->
            launch(Dispatchers.IO) {
                val buffer = ByteArray(9_000)
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        break
                    }
                    val data = packet.data.copyOf(packet.length)
                    if (packet.port == MDNS_PORT) {
                        Mdns.chromecasts(data, packet.address).forEach(::publish)
                    } else {
                        val location = Ssdp.location(data.decodeToString()) ?: continue
                        if (described.add(location)) launch(Dispatchers.IO) { runCatching { describe(location) }.getOrNull()?.let(::publish) }
                    }
                }
            }
            launch(Dispatchers.IO) {
                while (isActive) {
                    runCatching {
                        socket.send(DatagramPacket(Mdns.QUERY, Mdns.QUERY.size, InetAddress.getByName(MDNS_GROUP), MDNS_PORT))
                        socket.send(DatagramPacket(Ssdp.SEARCH, Ssdp.SEARCH.size, InetAddress.getByName(SSDP_GROUP), SSDP_PORT))
                    }.onFailure { AppLog.debug("cast", "search on ${socket.localAddress} failed: ${it.message}") }
                    delay(intervalMs)
                }
            }
        }
        awaitClose {
            sockets.forEach { it.close() }
            runCatching { lock.close() }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900

        /** IPv4 addresses of interfaces that are up, not loopback and multicast-capable (Wi-Fi, Ethernet). */
        fun interfaces(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
        }.getOrDefault(emptyList())

        fun describeRenderer(location: String): CastDevice? {
            val response = LanHttp.request(location, timeoutMs = 4_000)
            return if (response.status == 200) DlnaConnector.deviceOf(location, response.body) else null
        }
    }
}

/** The SSDP search for renderers and the `LOCATION` of its answers. */
internal object Ssdp {
    val SEARCH: ByteArray = ("M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n" +
        "USER-AGENT: Racha/1 UPnP/1.1\r\n\r\n").toByteArray(Charsets.US_ASCII)

    fun location(response: String): String? {
        if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("NOTIFY")) return null
        return response.lines().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http://") }
    }
}

/** Just enough DNS to ask for Chromecasts and read their answers (PTR → SRV/TXT/A). */
internal object Mdns {
    private const val SERVICE = "_googlecast._tcp.local"
    private const val PTR = 12
    private const val TXT = 16
    private const val SRV = 33
    private const val A = 1

    val QUERY: ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // id 0, flags 0, one question
        SERVICE.split('.').forEach { label -> write(label.length); write(label.toByteArray()) }
        write(0)
        write(byteArrayOf(0, PTR.toByte(), 0, 1)) // PTR, class IN
    }.toByteArray()

    private data class Record(val name: String, val type: Int, val data: ByteArray, val dataOffset: Int)

    /** The Chromecasts in one mDNS answer; [from] is used when the answer has no address record. */
    fun chromecasts(packet: ByteArray, from: InetAddress): List<CastDevice> = runCatching {
        val records = records(packet)
        records.filter { it.type == PTR && it.name.equals(SERVICE, ignoreCase = true) }.mapNotNull { ptr ->
            val instance = name(packet, ptr.dataOffset).first
            val srv = records.firstOrNull { it.type == SRV && it.name.equals(instance, ignoreCase = true) }
            val port = srv?.let { ((it.data[4].toInt() and 0xFF) shl 8) or (it.data[5].toInt() and 0xFF) } ?: 8009
            val target = srv?.let { name(packet, it.dataOffset + 6).first }
            val address = records.firstOrNull { it.type == A && target != null && it.name.equals(target, ignoreCase = true) }
                ?.let { InetAddress.getByAddress(it.data).hostAddress } ?: from.hostAddress
            val txt = records.firstOrNull { it.type == TXT && it.name.equals(instance, ignoreCase = true) }?.let { txt(it.data) }.orEmpty()
            val id = txt["id"] ?: instance
            CastDevice(id, txt["fn"] ?: instance.substringBefore("._googlecast"), CastKind.CHROMECAST, "$address:$port", txt["md"])
        }
    }.getOrDefault(emptyList())

    private fun records(packet: ByteArray): List<Record> {
        fun u16(at: Int) = ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)
        val questions = u16(4)
        val total = u16(6) + u16(8) + u16(10)
        var at = 12
        repeat(questions) { at = name(packet, at).second + 4 }
        val out = ArrayList<Record>(total)
        repeat(total) {
            val (name, next) = name(packet, at)
            val type = u16(next)
            val length = u16(next + 8)
            val start = next + 10
            if (start + length > packet.size) throw IOException("truncated")
            out += Record(name, type, packet.copyOfRange(start, start + length), start)
            at = start + length
        }
        return out
    }

    /** A (possibly compressed) DNS name at [offset], and where the data after it starts. */
    private fun name(packet: ByteArray, offset: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var at = offset
        var end = -1
        var jumps = 0
        while (true) {
            val length = packet[at].toInt() and 0xFF
            when {
                length == 0 -> { if (end < 0) end = at + 1; break }
                length and 0xC0 == 0xC0 -> {
                    if (end < 0) end = at + 2
                    at = ((length and 0x3F) shl 8) or (packet[at + 1].toInt() and 0xFF)
                    if (++jumps > 20) throw IOException("name loop")
                }
                else -> {
                    labels += packet.decodeToString(at + 1, at + 1 + length)
                    at += 1 + length
                }
            }
        }
        return labels.joinToString(".") to end
    }

    private fun txt(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var at = 0
        while (at < data.size) {
            val length = data[at].toInt() and 0xFF
            if (at + 1 + length > data.size) break
            val entry = data.decodeToString(at + 1, at + 1 + length)
            out[entry.substringBefore('=')] = entry.substringAfter('=', "")
            at += 1 + length
        }
        return out
    }
}
