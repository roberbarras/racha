package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.Fixtures.t0
import io.github.roberbarras.racha.domain.identity.Account
import io.github.roberbarras.racha.domain.identity.Device
import io.github.roberbarras.racha.domain.identity.Platform
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.sync.SyncOperation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class SystemPersistenceTest {
    private val db = Fixtures.newDatabase()

    @Test
    fun preferencesPersistTypedValues() = runBlocking {
        val preferences = Preferences(db.settings, Clock { t0 })

        preferences.set(Settings.SeekStepSeconds, 30)
        preferences.set(Settings.SpeedSteps, listOf(0.5, 1.0, 2.0))

        assertEquals(30, preferences.get(Settings.SeekStepSeconds))
        assertEquals(listOf(0.5, 1.0, 2.0), preferences.get(Settings.SpeedSteps))
        assertEquals(mapOf("player.seekStepSeconds" to "30", "player.speedSteps" to "0.5,1.0,2.0"), db.settings.all())
        preferences.reset(Settings.SeekStepSeconds)
        assertEquals(10, preferences.get(Settings.SeekStepSeconds))
    }

    @Test
    fun syncQueueIsFifoAndTracksFailures() = runBlocking {
        db.syncQueue.enqueue("progress", "e1", SyncOperation.UPSERT, "{}", t0)
        db.syncQueue.enqueue("bookmark", "b1", SyncOperation.DELETE, null, t0 + 1.seconds)

        val pending = db.syncQueue.pending(10)
        assertEquals(listOf("e1", "b1"), pending.map { it.entityId })

        db.syncQueue.markFailed(pending[0].id, "timeout")
        assertEquals(1 to "timeout", db.syncQueue.pending(1).single().let { it.retries to it.lastError })

        db.syncQueue.remove(pending.map { it.id })
        assertEquals(0, db.syncQueue.count())
    }

    @Test
    fun onlyOneCurrentDeviceAndOneAccount() = runBlocking {
        db.devices.save(Device("d1", "Portátil", Platform.LINUX, t0, isCurrent = true))
        db.devices.save(Device("d2", "Móvil", Platform.ANDROID, t0, isCurrent = true))

        assertEquals("d2", db.devices.current()?.id)
        assertEquals(2, db.devices.all().size)

        db.accounts.save(Account("u1", "a@example.com", null))
        db.accounts.save(Account("u2", "b@example.com", "B"))
        assertEquals(Account("u2", "b@example.com", "B"), db.accounts.current())
        db.accounts.clear()
        assertNull(db.accounts.current())
    }
}
