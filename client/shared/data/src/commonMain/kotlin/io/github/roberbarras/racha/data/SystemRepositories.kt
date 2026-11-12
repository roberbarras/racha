package io.github.roberbarras.racha.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.roberbarras.racha.application.identity.AccountRepository
import io.github.roberbarras.racha.application.identity.DeviceRepository
import io.github.roberbarras.racha.application.preferences.SettingsStore
import io.github.roberbarras.racha.application.sync.SyncQueueRepository
import io.github.roberbarras.racha.data.db.App_setting
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.identity.Account
import io.github.roberbarras.racha.domain.identity.Device
import io.github.roberbarras.racha.domain.identity.Platform
import io.github.roberbarras.racha.domain.sync.SyncOperation
import io.github.roberbarras.racha.domain.sync.SyncQueueEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import io.github.roberbarras.racha.data.db.Account as AccountRow
import io.github.roberbarras.racha.data.db.Device as DeviceRow

class SqlSettingsStore(private val db: RachaDatabase, private val io: CoroutineContext) : SettingsStore {
    private val q get() = db.systemQueries

    override suspend fun get(key: String): String? = withContext(io) { q.selectSetting(key).executeAsOneOrNull() }

    override suspend fun put(key: String, value: String, at: Instant) = withContext(io) {
        q.transaction {
            q.insertOrIgnoreSetting(App_setting(key, value, at.millis()))
            q.updateSetting(value, at.millis(), key)
        }
    }

    override suspend fun remove(key: String) = withContext(io) { q.deleteSetting(key); Unit }

    override fun observe(key: String): Flow<String?> = q.selectSetting(key).asFlow().mapToOneOrNull(io)

    override suspend fun all(): Map<String, String> =
        withContext(io) { q.selectAllSettings().executeAsList().associate { it.key to it.value_ } }
}

class SqlSyncQueueRepository(private val db: RachaDatabase, private val io: CoroutineContext) : SyncQueueRepository {
    private val q get() = db.systemQueries

    override suspend fun enqueue(entityType: String, entityId: String, operation: SyncOperation, payload: String?, at: Instant) =
        withContext(io) { q.enqueue(entityType, entityId, operation.name, payload, at.millis()); Unit }

    override suspend fun pending(limit: Int): List<SyncQueueEntry> = withContext(io) {
        q.selectPending(limit.toLong()).executeAsList().map {
            SyncQueueEntry(
                it.id, it.entity_type, it.entity_id, SyncOperation.valueOf(it.operation), it.payload,
                it.retries.toInt(), it.last_error, it.created_at.instant(),
            )
        }
    }

    override suspend fun markFailed(id: Long, error: String) = withContext(io) { q.markFailed(error, id); Unit }

    override suspend fun remove(ids: Collection<Long>) = withContext(io) {
        if (ids.isNotEmpty()) q.deleteQueued(ids)
        Unit
    }

    override suspend fun count(): Long = withContext(io) { q.countQueued().executeAsOne() }
}

class SqlDeviceRepository(private val db: RachaDatabase, private val io: CoroutineContext) : DeviceRepository {
    private val q get() = db.systemQueries

    override suspend fun current(): Device? = withContext(io) { q.selectCurrentDevice().executeAsOneOrNull()?.toDomain() }

    override suspend fun save(device: Device) = withContext(io) {
        val r = DeviceRow(device.id, device.name, device.platform.name, device.lastSeenAt.millis(), device.isCurrent)
        q.transaction {
            if (r.is_current) q.clearCurrentDevice(r.id)
            q.insertOrIgnoreDevice(r)
            q.updateDevice(r.name, r.platform, r.last_seen_at, r.is_current, r.id)
        }
    }

    override suspend fun all(): List<Device> = withContext(io) { q.selectDevices().executeAsList().map { it.toDomain() } }

    private fun DeviceRow.toDomain() = Device(id, name, Platform.valueOf(platform), last_seen_at.instant(), is_current)
}

class SqlAccountRepository(private val db: RachaDatabase, private val io: CoroutineContext) : AccountRepository {
    private val q get() = db.systemQueries

    override suspend fun current(): Account? =
        withContext(io) { q.selectAccount().executeAsOneOrNull()?.let { Account(it.id, it.email, it.display_name) } }

    override suspend fun save(account: Account) = withContext(io) {
        q.transaction {
            q.deleteAccounts()
            q.insertAccount(AccountRow(account.id, account.email, account.displayName))
        }
    }

    override suspend fun clear() = withContext(io) { q.deleteAccounts(); Unit }
}
