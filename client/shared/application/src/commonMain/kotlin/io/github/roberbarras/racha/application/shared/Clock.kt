package io.github.roberbarras.racha.application.shared

import kotlin.time.Clock as SystemClock
import kotlin.time.Instant

/** Outbound port for the current time, so use cases are deterministic in tests. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val SYSTEM = Clock { SystemClock.System.now() }
    }
}
