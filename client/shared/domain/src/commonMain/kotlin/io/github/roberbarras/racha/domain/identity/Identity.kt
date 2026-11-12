package io.github.roberbarras.racha.domain.identity

import kotlin.time.Instant

enum class Platform { ANDROID, LINUX }

/** A device known to this installation; [isCurrent] marks this one. */
data class Device(
    val id: String,
    val name: String,
    val platform: Platform,
    val lastSeenAt: Instant,
    val isCurrent: Boolean,
)

/**
 * The signed-in account mirrored locally. Tokens are deliberately not part of it: they live in the platform's
 * secure credential storage (Android Keystore, Secret Service on Linux), never in the database. The study/leisure
 * mode is a user-scoped setting ([io.github.roberbarras.racha.domain.preferences.Settings.Mode]) so it works
 * offline and without an account; it reaches the server through settings sync.
 */
data class Account(
    val id: String,
    val email: String,
    val displayName: String?,
)
