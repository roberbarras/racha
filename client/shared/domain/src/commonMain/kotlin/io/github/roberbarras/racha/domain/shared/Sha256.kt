package io.github.roberbarras.racha.domain.shared

/** SHA-256 as lowercase hex. Platform implementations use the native, hardware-accelerated digest. */
expect object Sha256 {
    fun hex(bytes: ByteArray): String
}
