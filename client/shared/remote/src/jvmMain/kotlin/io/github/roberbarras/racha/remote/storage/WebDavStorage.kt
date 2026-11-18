package io.github.roberbarras.racha.remote.storage

import io.github.roberbarras.racha.application.remote.RemoteCredentials
import io.github.roberbarras.racha.application.remote.RemoteDirectory
import io.github.roberbarras.racha.application.remote.RemoteStorage
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.remote.RemoteFile
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.remote.HttpOptions
import io.github.roberbarras.racha.remote.okHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Instant

/**
 * Nextcloud, ownCloud and any WebDAV server (H19): `PROPFIND` one level at a time (servers often refuse `infinity`),
 * downloads with `Range`, an app password through Basic auth over HTTPS.
 */
class WebDavStorage(
    engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    options: suspend () -> HttpOptions = { HttpOptions() },
) : RemoteStorage {
    private val http = RemoteHttp(engineFactory, options)
    override val provider = RemoteProvider.WEBDAV

    private data class Entry(val url: String, val name: String, val directory: Boolean, val size: Long, val modifiedAt: Instant)

    override suspend fun folders(credentials: RemoteCredentials, base: String, address: String?): List<RemoteDirectory> = http.use { client ->
        val folder = directoryUrl(address ?: base)
        list(client, credentials.webDav(), folder).filter { it.directory }.map { entry ->
            RemoteDirectory(entry.name, entry.url, "/" + decode(URI(entry.url).rawPath.removePrefix(URI(directoryUrl(base)).rawPath)).trimEnd('/'))
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun files(credentials: RemoteCredentials, address: String): List<RemoteFile> = http.use { client ->
        val auth = credentials.webDav()
        val root = directoryUrl(address)
        val rootPath = URI(root).rawPath
        val out = ArrayList<RemoteFile>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            for (entry in list(client, auth, pending.removeFirst())) {
                if (entry.directory) {
                    pending += entry.url
                } else {
                    val relative = decode(URI(entry.url).rawPath.removePrefix(rootPath))
                    runCatching { RelativePath.of(relative) }.getOrNull()?.let { out += RemoteFile(it, entry.size, entry.modifiedAt, entry.url) }
                }
            }
        }
        out
    }

    override suspend fun download(credentials: RemoteCredentials, file: RemoteFile, target: String, progress: (Long) -> Unit) {
        val auth = credentials.webDav()
        http.use { client ->
            client.downloadTo(file, target, progress) {
                url(file.ref)
                basicAuth(auth.user, auth.password)
            }
        }
    }

    private suspend fun list(client: HttpClient, auth: RemoteCredentials.WebDav, folder: String): List<Entry> {
        val response = client.request(folder) {
            method = HttpMethod("PROPFIND")
            basicAuth(auth.user, auth.password)
            header("Depth", "1")
            contentType(ContentType.Application.Xml)
            setBody(PROPFIND)
        }
        RemoteHttp.check(response, "PROPFIND")
        val self = URI(folder).rawPath.trimEnd('/')
        return parse(response.bodyAsText(), folder).filter { URI(it.url).rawPath.trimEnd('/') != self }
    }

    private fun RemoteCredentials.webDav() = this as? RemoteCredentials.WebDav ?: throw RemoteError.Unauthorized()

    companion object {
        private val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
            """<d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        private val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            isExpandEntityReferences = false
        }

        fun directoryUrl(url: String) = if (url.endsWith('/')) url else "$url/"

        private fun decode(path: String) = URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)

        private fun Element.child(name: String): Element? {
            val nodes = getElementsByTagNameNS("DAV:", name)
            return if (nodes.length > 0) nodes.item(0) as Element else null
        }

        /** The entries of a `207 Multi-Status`, with absolute URLs. */
        private fun parse(xml: String, base: String): List<Entry> {
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            return (0 until responses.length).mapNotNull { i ->
                val response = responses.item(i) as Element
                val href = response.child("href")?.textContent?.trim() ?: return@mapNotNull null
                val url = URI(base).resolve(href).toString()
                val directory = response.child("resourcetype")?.getElementsByTagNameNS("DAV:", "collection")?.length?.let { it > 0 } ?: false
                val size = response.child("getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0
                val modified = response.child("getlastmodified")?.textContent?.trim()?.let {
                    runCatching { Instant.fromEpochMilliseconds(ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()) }.getOrNull()
                } ?: Instant.fromEpochMilliseconds(0)
                Entry(url, decode(URI(url).rawPath.trimEnd('/').substringAfterLast('/')), directory, size, modified)
            }
        }

        /** The WebDAV folder of a Nextcloud account: `https://server/remote.php/dav/files/<user>/`. */
        fun nextcloudBase(server: String, user: String): String =
            server.trim().trimEnd('/') + "/remote.php/dav/files/" + URLEncoder.encode(user, Charsets.UTF_8).replace("+", "%20") + "/"
    }
}

/**
 * Nextcloud's login flow v2: the user signs in on their server in the browser (with 2FA if they use it) and Racha
 * receives an app password, which can be revoked from Nextcloud and is never the account password.
 */
class NextcloudLogin(
    engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    options: suspend () -> HttpOptions = { HttpOptions() },
) {
    private val http = RemoteHttp(engineFactory, options)

    data class Flow(val loginUrl: String, val endpoint: String, val token: String)
    data class Account(val server: String, val user: String, val appPassword: String) {
        val davBase: String get() = WebDavStorage.nextcloudBase(server, user)
    }

    suspend fun start(server: String): Flow = http.use { client ->
        val base = server.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
        val response = client.post("$base/index.php/login/v2")
        RemoteHttp.check(response, "login")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val poll = json["poll"]?.jsonObject ?: throw RemoteError.Rejected(response.status.value, "not_nextcloud", null)
        Flow(json.str("login") ?: "", poll.str("endpoint") ?: "", poll.str("token") ?: "")
    }

    /** Null while the user has not finished signing in. */
    suspend fun poll(flow: Flow): Account? = http.use { client ->
        val response = client.submitForm(flow.endpoint, parameters { append("token", flow.token) })
        if (response.status == HttpStatusCode.NotFound) return@use null
        RemoteHttp.check(response, "login")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        Account(json.str("server") ?: "", json.str("loginName") ?: "", json.str("appPassword") ?: "")
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
