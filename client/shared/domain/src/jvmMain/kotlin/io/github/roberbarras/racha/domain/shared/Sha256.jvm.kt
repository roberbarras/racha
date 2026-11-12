package io.github.roberbarras.racha.domain.shared

import java.security.MessageDigest

actual object Sha256 {
    actual fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
