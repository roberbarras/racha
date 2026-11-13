package io.github.roberbarras.racha.domain.library.scan

/** "Part 2" before "Part 10"; case-insensitive. Used as the last tie-breaker everywhere. */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val left = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val right = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0].isDigit() && y[0].isDigit()) {
                compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') }).takeIf { it != 0 } ?: x.length.compareTo(y.length)
            } else {
                x.compareTo(y)
            }
            if (result != 0) return result
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
