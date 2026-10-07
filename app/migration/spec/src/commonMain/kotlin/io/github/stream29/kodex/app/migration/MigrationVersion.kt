package io.github.stream29.kodex.app.migration

/**
 * Application version used to select Kodex Home migrations.
 *
 * Ordering compares the three integer components, not the formatted string.
 * The string representation is canonical `major.minor.patch`.
 *
 * @throws IllegalArgumentException If any component is negative.
 */
public data class MigrationVersion(
    public val major: Int,
    public val minor: Int,
    public val patch: Int,
) : Comparable<MigrationVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "Migration version components must be non-negative: $major.$minor.$patch"
        }
    }

    /**
     * Parses exactly three canonical, non-negative decimal [Int] components.
     *
     * @throws IllegalArgumentException If [value] has missing or extra components,
     * leading zeros, non-decimal characters, prerelease/build metadata, or a
     * component outside the non-negative [Int] range.
     */
    public constructor(value: String) : this(parseMigrationVersion(value))

    private constructor(components: IntArray) : this(
        major = components[0],
        minor = components[1],
        patch = components[2],
    )

    override fun compareTo(other: MigrationVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

private fun parseMigrationVersion(value: String): IntArray {
    val components = value.split('.')
    require(components.size == VersionComponentCount) {
        "Invalid migration version: $value"
    }
    return IntArray(VersionComponentCount) { index ->
        val component = components[index]
        require(
            component.isNotEmpty() &&
                component.all { character -> character in '0'..'9' } &&
                (component == "0" || component.first() != '0'),
        ) {
            "Invalid migration version: $value"
        }
        requireNotNull(component.toIntOrNull()) {
            "Migration version component is outside the Int range: $value"
        }
    }
}

private const val VersionComponentCount: Int = 3
