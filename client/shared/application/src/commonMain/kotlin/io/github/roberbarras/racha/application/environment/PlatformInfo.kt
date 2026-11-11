package io.github.roberbarras.racha.application.environment

/** Outbound port: facts about the host platform, implemented by each app's platform adapter. */
fun interface PlatformInfo {
    fun name(): String
}
