package io.github.roberbarras.racha.application.sync

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.preferences.ConflictPolicy
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** Where the sync server lives (spec §7.4: host, port, HTTPS on/off, base path). */
data class ServerAddress(val host: String, val port: Int, val https: Boolean, val basePath: String) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' }) { "invalid host" }
        require(port in 1..65535) { "invalid port" }
    }

    val baseUrl: String
        get() {
            val scheme = if (https) "https" else "http"
            val defaultPort = (https && port == 443) || (!https && port == 80)
            val path = basePath.trim('/').let { if (it.isEmpty()) "" else "/$it" }
            return "$scheme://$host${if (defaultPort) "" else ":$port"}$path"
        }

    companion object {
        suspend fun from(preferences: Preferences): ServerAddress? = runCatching {
            ServerAddress(
                preferences.get(Settings.SyncHost), preferences.get(Settings.SyncPort),
                preferences.get(Settings.SyncHttps), preferences.get(Settings.SyncBasePath),
            )
        }.getOrNull()
    }
}

/** One change on the wire (ADR-0012). [entity]: category, categoryRoot, libraryItem, section, element, subtitle, progress, bookmark, history, favorite, setting. */
data class SyncChange(val entity: String, val id: String, val updatedAt: Instant, val deleted: Boolean, val data: JsonObject)

data class SyncRecord(val entity: String, val id: String, val updatedAt: Instant, val deleted: Boolean, val seq: Long, val data: JsonObject)

enum class PushOutcome { APPLIED, STALE, INVALID }

data class PushResult(val entity: String?, val id: String, val outcome: PushOutcome, val reason: String?, val server: SyncRecord?)

data class PullPage(val records: List<SyncRecord>, val cursor: Long, val hasMore: Boolean)

/** What the server returns on login/registration/refresh. */
data class Tokens(val userId: String, val deviceId: String, val accessToken: String, val expiresInSeconds: Long, val refreshToken: String)

/** This installation as the server knows it. */
data class DeviceDescriptor(val id: String?, val name: String, val platform: String, val appVersion: String)

/** Failures talking to the server, split by what the app does about them. */
sealed class RemoteError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The access token is not accepted (expired, revoked): refresh, or sign in again. */
    class Unauthorized : RemoteError("unauthorized")

    /** No network, DNS, TLS or a server that does not answer: try again later. */
    class Unreachable(cause: Throwable?) : RemoteError("server unreachable", cause)

    /** The server refused the request ([code] from its problem details: invalid_credentials, email_taken…). */
    class Rejected(val status: Int, val code: String?, detail: String?) : RemoteError(detail ?: "request rejected ($status)")
}

/** Stable, untranslated summary kept as the last sync error ("unreachable", "unauthorized", "rejected:500:code"); the UI words it. */
fun RemoteError.reportCode(): String = when (this) {
    is RemoteError.Unreachable -> "unreachable"
    is RemoteError.Unauthorized -> "unauthorized"
    is RemoteError.Rejected -> listOfNotNull("rejected", status.toString(), code).joinToString(":")
}

/** Outbound port: the account endpoints (§7.2). */
interface AuthServer {
    suspend fun register(address: ServerAddress, email: String, password: String, displayName: String?, device: DeviceDescriptor): Tokens

    suspend fun login(address: ServerAddress, email: String, password: String, device: DeviceDescriptor): Tokens

    suspend fun refresh(address: ServerAddress, refreshToken: String): Tokens

    suspend fun logout(address: ServerAddress, refreshToken: String)
}

/** Outbound port: changing a password (signed in) and recovering it by e-mail (signed out). */
interface PasswordServer {
    /** Throws [RemoteError.Rejected] 403 `wrong_password` when [current] is wrong. */
    suspend fun changePassword(address: ServerAddress, accessToken: String, current: String, replacement: String)

    /** Asks the server to e-mail a recovery code; [language] picks the mail's language. */
    suspend fun requestRecoveryCode(address: ServerAddress, email: String, language: String)

    suspend fun resetPassword(address: ServerAddress, email: String, code: String, replacement: String)
}

/** Outbound port: `/sync/push` and `/sync/pull`. */
interface SyncServer {
    suspend fun push(address: ServerAddress, accessToken: String, changes: List<SyncChange>): List<PushResult>

    suspend fun pull(address: ServerAddress, accessToken: String, since: Long, limit: Int): PullPage
}

/** What survives a restart to stay signed in. Kept in the platform's secret store, never in the database. */
data class StoredSession(val serverUrl: String, val userId: String, val deviceId: String, val email: String, val refreshToken: String)

/** Outbound port: Android Keystore / Secret Service (libsecret) on Linux. */
interface CredentialStore {
    suspend fun read(): StoredSession?

    suspend fun write(session: StoredSession)

    suspend fun clear()
}

/** A local change ready to push; [upTo] is the last queue entry it covers (acknowledged together). */
data class PendingChange(val change: SyncChange, val upTo: Long)

/** A setting changed both here and on another device (§6.3 "config: ask the user"). */
data class SettingConflict(val key: String, val localValue: String, val remoteValue: String, val remoteUpdatedAt: Instant)

data class LocalSyncState(val pending: Long, val lastSuccessAt: Instant?, val lastError: String?, val conflicts: List<SettingConflict>)

/**
 * A library root of this account on another device (ADR-0004). [layout]: what that folder holds there (ADR-0013);
 * linking to it takes it, so both devices make the same courses.
 */
data class RemoteRoot(val key: String, val categoryKey: String, val name: String, val layout: RootLayout = RootLayout.AUTO)

/** Outbound port: the local side of sync (outbox, cursor, applying remote changes). */
interface SyncLocalStore {
    /** Start recording local changes and queue everything that already exists (first upload). */
    suspend fun startRecording()

    /** Stop recording and forget queue, cursor, conflicts and remote roots (signing out). */
    suspend fun stopRecording()

    suspend fun pending(limit: Int): List<PendingChange>

    suspend fun acknowledge(changes: List<PendingChange>)

    suspend fun fail(change: PendingChange, error: String)

    /** Applies newer remote versions (last writer wins); settings that also changed here follow [policy]. */
    suspend fun applyRemote(records: List<SyncRecord>, policy: ConflictPolicy)

    /**
     * The server stored these pushed changes with its own time (it clamps clocks that run ahead, ADR-0012): rows
     * not changed since take that time, or this device would ignore newer remote changes until its clock catches up.
     */
    suspend fun adoptServerTimes(pushed: List<Pair<SyncChange, SyncRecord>>)

    suspend fun cursor(): Long

    suspend fun setCursor(cursor: Long)

    suspend fun recordSuccess(at: Instant)

    suspend fun recordError(message: String)

    fun observe(): Flow<LocalSyncState>

    /** Keeps this device's value (it will win the next push) or takes the other one. */
    suspend fun resolve(key: String, keepLocal: Boolean)

    suspend fun remoteRoots(categoryKey: String): List<RemoteRoot>
}
