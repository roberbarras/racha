package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.RelativePath
import kotlin.time.Instant

/** One file or directory found under a category root, as listed by the library source adapter. */
data class FileEntry(
    val path: RelativePath,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Instant,
)
