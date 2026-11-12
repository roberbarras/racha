package io.github.roberbarras.racha.application.identity

import io.github.roberbarras.racha.domain.identity.Account
import io.github.roberbarras.racha.domain.identity.Device

interface DeviceRepository {
    suspend fun current(): Device?

    suspend fun save(device: Device)

    suspend fun all(): List<Device>
}

interface AccountRepository {
    suspend fun current(): Account?

    suspend fun save(account: Account)

    suspend fun clear()
}
