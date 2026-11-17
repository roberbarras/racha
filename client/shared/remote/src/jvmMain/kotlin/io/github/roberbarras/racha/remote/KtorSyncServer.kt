package io.github.roberbarras.racha.remote

import io.github.roberbarras.racha.application.sync.AuthServer
import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.PasswordServer
import io.github.roberbarras.racha.application.sync.PullPage
import io.github.roberbarras.racha.application.sync.PushOutcome
import io.github.roberbarras.racha.application.sync.PushResult
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.application.sync.SyncChange
import io.github.roberbarras.racha.application.sync.SyncRecord
import io.github.roberbarras.racha.application.sync.SyncServer
import io.github.roberbarras.racha.application.sync.Tokens
import io.github.roberbarras.racha.application.stats.DeviceTime
import io.github.roberbarras.racha.application.stats.RemoteDetails
import io.github.roberbarras.racha.application.stats.RemoteStats
import io.github.roberbarras.racha.application.stats.SessionStats
import io.github.roberbarras.racha.application.stats.StatsServer
import io.github.roberbarras.racha.application.telemetry.ClientError
import io.github.roberbarras.racha.application.telemetry.TelemetryEvent
import io.github.roberbarras.racha.application.telemetry.TelemetryServer
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.engine.ProxyBuilder
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * The sync server over HTTP (spec §7.2–7.4). One client for the app: connections are reused and kept few.
 * Network failures become [RemoteError.Unreachable] (retry later); a rejected access token
 * [RemoteError.Unauthorized] (refresh); anything else the server refuses [RemoteError.Rejected] with its code.
 */
class KtorSyncServer(
    private val engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    private val options: suspend () -> HttpOptions = { HttpOptions() },
) : AuthServer, PasswordServer, SyncServer, TelemetryServer, StatsServer, AutoCloseable {
    /** Tests: a fixed engine (e.g. MockEngine). */
    constructor(engine: HttpClientEngine) : this({ engine })

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val clientLock = Mutex()
    private var current: Pair<HttpOptions, HttpClient>? = null

    /** The client for the current proxy and User-Agent (§9 "Avanzado"); rebuilt only when they change. */
    private suspend fun client(): HttpClient {
        val wanted = options()
        return clientLock.withLock {
            current?.takeIf { it.first == wanted }?.second ?: build(wanted).also { fresh ->
                current?.second?.close()
                current = wanted to fresh
            }
        }
    }

    private fun build(options: HttpOptions) = HttpClient(engineFactory(options)) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
        }
        install(UserAgent) { agent = options.userAgent }
    }

    override suspend fun register(address: ServerAddress, email: String, password: String, displayName: String?, device: DeviceDescriptor): Tokens =
        tokens(call { client().post(address.baseUrl + "/auth/register") { jsonBody(RegisterDto(email, password, displayName, device.dto())) } })

    override suspend fun login(address: ServerAddress, email: String, password: String, device: DeviceDescriptor): Tokens =
        tokens(call { client().post(address.baseUrl + "/auth/login") { jsonBody(LoginDto(email, password, device.dto())) } })

    override suspend fun refresh(address: ServerAddress, refreshToken: String): Tokens {
        val response = call { client().post(address.baseUrl + "/auth/refresh") { jsonBody(RefreshDto(refreshToken)) } }
        if (response.status.value == 401) throw RemoteError.Unauthorized()
        return tokens(response)
    }

    override suspend fun logout(address: ServerAddress, refreshToken: String) {
        call { client().post(address.baseUrl + "/auth/logout") { jsonBody(RefreshDto(refreshToken)) } }
    }

    override suspend fun changePassword(address: ServerAddress, accessToken: String, current: String, replacement: String) {
        authorized(call {
            client().post(address.baseUrl + "/account/password") {
                bearerAuth(accessToken)
                jsonBody(ChangePasswordDto(current, replacement))
            }
        })
    }

    override suspend fun requestRecoveryCode(address: ServerAddress, email: String, language: String) {
        ok(call {
            client().post(address.baseUrl + "/auth/password/forgot") {
                header(HttpHeaders.AcceptLanguage, language)
                jsonBody(ForgotPasswordDto(email))
            }
        })
    }

    override suspend fun resetPassword(address: ServerAddress, email: String, code: String, replacement: String) {
        ok(call { client().post(address.baseUrl + "/auth/password/reset") { jsonBody(ResetPasswordDto(email, code, replacement)) } })
    }

    override suspend fun push(address: ServerAddress, accessToken: String, changes: List<SyncChange>): List<PushResult> {
        val response = authorized(call {
            client().post(address.baseUrl + "/sync/push") {
                bearerAuth(accessToken)
                jsonBody(PushDto(changes.map { ChangeDto(it.entity, it.id, it.updatedAt.toString(), it.deleted, it.data) }))
            }
        })
        return response.body<PushResponseDto>().results.map { r ->
            PushResult(r.entity, r.id, PushOutcome.valueOf(r.outcome.uppercase()), r.reason, r.server?.record())
        }
    }

    override suspend fun pull(address: ServerAddress, accessToken: String, since: Long, limit: Int): PullPage {
        val response = authorized(call {
            client().get(address.baseUrl + "/sync/pull") {
                bearerAuth(accessToken)
                parameter("since", since)
                parameter("limit", limit)
            }
        })
        val page = response.body<PageDto>()
        return PullPage(page.changes.map { it.record() }, page.cursor, page.hasMore)
    }

    override suspend fun sendEvents(baseUrl: String, accessToken: String, events: List<TelemetryEvent>) {
        authorized(call {
            client().post("$baseUrl/analytics/events") {
                bearerAuth(accessToken)
                jsonBody(events.map { EventDto(it.id, it.type, it.occurredAt.toString(), it.properties) })
            }
        })
    }

    override suspend fun sendErrors(baseUrl: String, accessToken: String, errors: List<ClientError>) {
        authorized(call {
            client().post("$baseUrl/errors") {
                bearerAuth(accessToken)
                jsonBody(errors.map { ErrorDto(it.id, it.platform, it.appVersion, it.message, it.stackTrace, it.occurredAt.toString(), it.context) })
            }
        })
    }

    override suspend fun personalDetails(address: ServerAddress, accessToken: String, zone: String): RemoteDetails? {
        val response = call {
            client().get(address.baseUrl + "/stats/me/details") {
                bearerAuth(accessToken)
                parameter("zone", zone)
            }
        }
        if (response.status.value == 404) return null // a server older than the breakdowns
        val dto = authorized(response).body<StatsDetailsDto>()
        return RemoteDetails(
            sessions = SessionStats(dto.sessions.count, dto.sessions.averageSeconds, dto.sessions.averageSpeed, dto.sessions.withSubtitles),
            byHour = dto.byHour.associate { it.hour to it.seconds },
            byWeekday = dto.byWeekday.associate { it.day to it.seconds },
            devices = dto.byDevice.map { DeviceTime(it.name ?: "?", it.platform ?: "", it.seconds) },
            titleSeconds = dto.titles.associate { it.itemId to it.seconds },
        )
    }

    override suspend fun personalStats(address: ServerAddress, accessToken: String, zone: String): RemoteStats {
        val response = authorized(call {
            client().get(address.baseUrl + "/stats/me") {
                bearerAuth(accessToken)
                parameter("zone", zone)
            }
        })
        val dto = response.body<PersonalStatsDto>()
        return RemoteStats(
            dto.watchSeconds,
            dto.watchByDay.mapNotNull { d -> CalendarDate.parseOrNull(d.day)?.let { it to d.seconds } }.toMap(),
            dto.watchByCategory.mapNotNull { c -> c.categoryId?.let { it to c.seconds } }.toMap(),
        )
    }

    override fun close() {
        current?.second?.close()
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw RemoteError.Unreachable(e)
    } catch (e: java.io.IOException) {
        throw RemoteError.Unreachable(e)
    } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
        throw RemoteError.Unreachable(e)
    }

    private suspend fun authorized(response: HttpResponse): HttpResponse {
        if (response.status.value == 401) throw RemoteError.Unauthorized()
        return ok(response)
    }

    private suspend fun ok(response: HttpResponse): HttpResponse {
        if (response.status.isSuccess()) return response
        val problem = runCatching { json.decodeFromString(ProblemDto.serializer(), response.bodyAsText()) }.getOrNull()
        throw RemoteError.Rejected(response.status.value, problem?.code, problem?.detail)
    }

    private suspend fun tokens(response: HttpResponse): Tokens {
        val t = ok(response).body<TokenDto>()
        return Tokens(t.userId, t.deviceId, t.accessToken, t.expiresIn, t.refreshToken)
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private companion object {
        fun DeviceDescriptor.dto() = DeviceDto(id, name, platform, appVersion)

        fun RecordDto.record() = SyncRecord(entity, id, Instant.parse(updatedAt), deleted, seq, data)
    }
}

/** Connection options from the advanced settings (§9): proxy (`http://host:port`, `socks5://host:port`) and User-Agent. */
data class HttpOptions(val proxy: String? = null, val userAgent: String = "Racha") {
    companion object {
        /** `Racha/<version> (<platform>)` unless the user set one; a malformed proxy is ignored (direct connection). */
        fun of(proxy: String, userAgent: String, appVersion: String, platform: String) = HttpOptions(
            proxy = proxy.trim().takeIf { it.isNotEmpty() },
            userAgent = userAgent.trim().ifEmpty { "Racha/$appVersion ($platform)" },
        )
    }
}

internal fun okHttp(options: HttpOptions): HttpClientEngine = OkHttp.create {
    proxy = options.proxy?.let(::proxyOf)
}

internal fun proxyOf(text: String): java.net.Proxy? = runCatching {
    val withScheme = if ("://" in text) text else "http://$text"
    val url = Url(withScheme)
    if (url.host.isEmpty() || url.port <= 0) return null
    when (url.protocol.name) {
        "socks", "socks5" -> ProxyBuilder.socks(url.host, url.port)
        else -> ProxyBuilder.http(url)
    }
}.getOrNull()

