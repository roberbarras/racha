package io.github.roberbarras.racha.remote

import io.github.roberbarras.racha.application.sync.AuthServer
import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.PullPage
import io.github.roberbarras.racha.application.sync.PushOutcome
import io.github.roberbarras.racha.application.sync.PushResult
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.application.sync.SyncChange
import io.github.roberbarras.racha.application.sync.SyncRecord
import io.github.roberbarras.racha.application.sync.SyncServer
import io.github.roberbarras.racha.application.sync.Tokens
import io.github.roberbarras.racha.application.stats.RemoteStats
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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
class KtorSyncServer(engine: HttpClientEngine = OkHttp.create()) : AuthServer, SyncServer, TelemetryServer, StatsServer, AutoCloseable {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
        }
    }

    override suspend fun register(address: ServerAddress, email: String, password: String, displayName: String?, device: DeviceDescriptor): Tokens =
        tokens(call { client.post(address.baseUrl + "/auth/register") { jsonBody(RegisterDto(email, password, displayName, device.dto())) } })

    override suspend fun login(address: ServerAddress, email: String, password: String, device: DeviceDescriptor): Tokens =
        tokens(call { client.post(address.baseUrl + "/auth/login") { jsonBody(LoginDto(email, password, device.dto())) } })

    override suspend fun refresh(address: ServerAddress, refreshToken: String): Tokens {
        val response = call { client.post(address.baseUrl + "/auth/refresh") { jsonBody(RefreshDto(refreshToken)) } }
        if (response.status.value == 401) throw RemoteError.Unauthorized()
        return tokens(response)
    }

    override suspend fun logout(address: ServerAddress, refreshToken: String) {
        call { client.post(address.baseUrl + "/auth/logout") { jsonBody(RefreshDto(refreshToken)) } }
    }

    override suspend fun push(address: ServerAddress, accessToken: String, changes: List<SyncChange>): List<PushResult> {
        val response = authorized(call {
            client.post(address.baseUrl + "/sync/push") {
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
            client.get(address.baseUrl + "/sync/pull") {
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
            client.post("$baseUrl/analytics/events") {
                bearerAuth(accessToken)
                jsonBody(events.map { EventDto(it.id, it.type, it.occurredAt.toString(), it.properties) })
            }
        })
    }

    override suspend fun sendErrors(baseUrl: String, accessToken: String, errors: List<ClientError>) {
        authorized(call {
            client.post("$baseUrl/errors") {
                bearerAuth(accessToken)
                jsonBody(errors.map { ErrorDto(it.id, it.platform, it.appVersion, it.message, it.stackTrace, it.occurredAt.toString(), it.context) })
            }
        })
    }

    override suspend fun personalStats(address: ServerAddress, accessToken: String, zone: String): RemoteStats {
        val response = authorized(call {
            client.get(address.baseUrl + "/stats/me") {
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

    override fun close() = client.close()

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
