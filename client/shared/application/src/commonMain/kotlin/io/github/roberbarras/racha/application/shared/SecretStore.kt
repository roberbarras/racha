package io.github.roberbarras.racha.application.shared

/** Outbound port: one secret (e.g. an API key) in the platform's protected storage; never in settings or exports. */
interface SecretStore {
    suspend fun read(): String?

    suspend fun write(value: String)

    suspend fun clear()
}
