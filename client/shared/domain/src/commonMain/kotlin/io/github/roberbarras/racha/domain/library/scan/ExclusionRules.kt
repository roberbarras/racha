package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.RelativePath

/**
 * Category exclusion patterns (spec §3.2.4, §9), a small gitignore-like subset, case-insensitive:
 * - `temp/`: a directory with that name at any depth (and everything inside it);
 * - `*.m3u8`: a file name glob at any depth (`*` and `?`);
 * - `Course/extras/`: a path anchored at the root (contains a `/` before the end).
 * Hidden entries (name starting with `.`) are always skipped.
 */
class ExclusionRules(patterns: List<String>) {
    private val rules: List<Rule> = patterns.map(String::trim).filter(String::isNotEmpty).map(::parse)

    /** True if [path] (a file or a directory) must not be indexed nor descended into. */
    fun excludes(path: RelativePath, isDirectory: Boolean): Boolean {
        if (path.segments.any { it.startsWith(".") }) return true
        return rules.any { it.matches(path, isDirectory) }
    }

    private sealed interface Rule {
        fun matches(path: RelativePath, isDirectory: Boolean): Boolean
    }

    /** Matches any directory segment (so files inside excluded directories are excluded too). */
    private class DirectoryName(val glob: Regex) : Rule {
        override fun matches(path: RelativePath, isDirectory: Boolean): Boolean {
            val dirs = if (isDirectory) path.segments else path.segments.dropLast(1)
            return dirs.any(glob::matches)
        }
    }

    private class FileName(val glob: Regex) : Rule {
        override fun matches(path: RelativePath, isDirectory: Boolean) = path.segments.lastOrNull()?.let(glob::matches) == true
    }

    private class Anchored(val segments: List<Regex>, val directoryOnly: Boolean) : Rule {
        override fun matches(path: RelativePath, isDirectory: Boolean): Boolean {
            val names = path.segments
            if (names.size < segments.size) return false
            val prefixMatches = segments.indices.all { segments[it].matches(names[it]) }
            if (!prefixMatches) return false
            return !directoryOnly || names.size > segments.size || isDirectory
        }
    }

    private fun parse(pattern: String): Rule {
        val normalized = pattern.replace('\\', '/').trimStart('/')
        val directoryOnly = normalized.endsWith("/")
        val body = normalized.trimEnd('/')
        return when {
            '/' in body -> Anchored(body.split('/').map(::glob), directoryOnly)
            directoryOnly -> DirectoryName(glob(body))
            else -> FileName(glob(body))
        }
    }

    private fun glob(text: String): Regex = Regex(
        text.map { c ->
            when (c) {
                '*' -> "[^/]*"
                '?' -> "[^/]"
                else -> Regex.escape(c.toString())
            }
        }.joinToString(""),
        RegexOption.IGNORE_CASE,
    )
}
