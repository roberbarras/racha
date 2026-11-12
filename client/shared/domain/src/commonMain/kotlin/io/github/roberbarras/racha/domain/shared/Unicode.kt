package io.github.roberbarras.racha.domain.shared

/** Unicode canonical composition (NFC), so "é" typed as one or two code points yields the same identifier. */
internal expect fun normalizeNfc(text: String): String
