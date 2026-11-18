package io.github.roberbarras.racha.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.roberbarras.racha.application.remote.RemoteSourceRepository
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.data.db.Remote_source
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.remote.DownloadMode
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.domain.remote.RemoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** Remote folders in SQLite (`remote_source`, v10). */
class SqlRemoteSources(private val db: RachaDatabase, private val io: CoroutineContext) : RemoteSourceRepository {
    private val q get() = db.remoteSourceQueries

    override fun observe(): Flow<List<RemoteSource>> = q.selectAllSources().asFlow().mapToList(io).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun all(): List<RemoteSource> = withContext(io) { q.selectAllSources().executeAsList().mapNotNull { it.toDomain() } }

    override suspend fun get(id: String): RemoteSource? = withContext(io) { q.selectSource(id).executeAsOneOrNull()?.toDomain() }

    override suspend fun save(source: RemoteSource) = withContext(io) {
        q.upsertSource(
            Remote_source(
                source.id, source.provider.name, source.name, source.account, source.address, source.location, source.category.value,
                source.rootKey.value, source.layout.name, source.destination, source.mode.name, source.selected.sorted().joinToString("\n"),
                source.wifiOnly, source.lastSyncAt?.toEpochMilliseconds(), source.lastError,
            ),
        )
        Unit
    }

    override suspend fun delete(id: String) = withContext(io) { q.deleteSource(id); Unit }

    private fun Remote_source.toDomain(): RemoteSource? = runCatching {
        RemoteSource(
            id, RemoteProvider.valueOf(provider), name, account, address, location, CategoryKey(category_key), RootKey(root_key),
            RootLayout.entries.firstOrNull { it.name == layout } ?: RootLayout.AUTO, destination, DownloadMode.valueOf(mode),
            selected.split('\n').filter { it.isNotEmpty() }.toSet(), wifi_only, last_sync_at?.let(Instant::fromEpochMilliseconds), last_error,
        )
    }.getOrNull()
}
