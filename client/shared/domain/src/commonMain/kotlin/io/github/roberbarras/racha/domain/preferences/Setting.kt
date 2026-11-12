package io.github.roberbarras.racha.domain.preferences

/** Settings sync per user (follow the account) or per device (hardware, folders, connection). Spec §7.3. */
enum class SettingScope { USER, DEVICE }

/**
 * A typed setting with its default, validation and a stable string encoding. Invalid or unreadable stored values
 * fall back to [default] instead of breaking the app.
 */
sealed class Setting<T>(val key: String, val scope: SettingScope, val default: T) {
    abstract fun encode(value: T): String

    /** @throws IllegalArgumentException when [raw] is not a valid value. */
    abstract fun decode(raw: String): T

    /** @throws IllegalArgumentException when [value] is outside the allowed values. */
    open fun validate(value: T) {}

    fun decodeOrDefault(raw: String?): T =
        raw?.let { runCatching { decode(it).also(::validate) }.getOrNull() } ?: default

    override fun toString(): String = key
}

class BooleanSetting(key: String, scope: SettingScope, default: Boolean) : Setting<Boolean>(key, scope, default) {
    override fun encode(value: Boolean) = value.toString()
    override fun decode(raw: String) = raw.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Not a boolean: $raw")
}

class IntSetting(
    key: String,
    scope: SettingScope,
    default: Int,
    private val allowed: Set<Int>? = null,
    private val range: IntRange? = null,
) : Setting<Int>(key, scope, default) {
    override fun encode(value: Int) = value.toString()
    override fun decode(raw: String) = raw.toIntOrNull() ?: throw IllegalArgumentException("Not an int: $raw")
    override fun validate(value: Int) {
        require(allowed == null || value in allowed) { "$key must be one of $allowed" }
        require(range == null || value in range) { "$key must be in $range" }
    }
}

class DoubleSetting(
    key: String,
    scope: SettingScope,
    default: Double,
    private val range: ClosedFloatingPointRange<Double>,
) : Setting<Double>(key, scope, default) {
    override fun encode(value: Double) = value.toString()
    override fun decode(raw: String) = raw.toDoubleOrNull() ?: throw IllegalArgumentException("Not a number: $raw")
    override fun validate(value: Double) = require(value in range) { "$key must be in $range" }
}

class DoubleListSetting(
    key: String,
    scope: SettingScope,
    default: List<Double>,
    private val range: ClosedFloatingPointRange<Double>,
) : Setting<List<Double>>(key, scope, default) {
    override fun encode(value: List<Double>) = value.joinToString(",")
    override fun decode(raw: String) = raw.split(',').map { it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Not a number: $it") }
    override fun validate(value: List<Double>) {
        require(value.isNotEmpty()) { "$key must not be empty" }
        require(value.all { it in range }) { "$key values must be in $range" }
        require(value == value.sorted().distinct()) { "$key must be strictly increasing" }
    }
}

class StringSetting(
    key: String,
    scope: SettingScope,
    default: String,
    private val pattern: Regex? = null,
) : Setting<String>(key, scope, default) {
    override fun encode(value: String) = value
    override fun decode(raw: String) = raw
    override fun validate(value: String) = require(pattern == null || pattern.matches(value)) { "$key does not match $pattern" }
}

class EnumSetting<E : Enum<E>>(
    key: String,
    scope: SettingScope,
    default: E,
    private val values: List<E>,
) : Setting<E>(key, scope, default) {
    override fun encode(value: E) = value.name.lowercase()
    override fun decode(raw: String) = values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown value for $key: $raw")
}
