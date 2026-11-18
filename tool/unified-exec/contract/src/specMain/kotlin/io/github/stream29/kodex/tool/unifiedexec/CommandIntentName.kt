package io.github.stream29.kodex.tool.unifiedexec

/** Tests a new command name without interpreting its intent or changing the supplied text. */
public fun String?.isValidCommandIntentName(): Boolean =
    this != null && isNotBlank() && none(Char::isCommandIntentSeparator)

/** Projects stored names to a single display line; never rewrites the stored arguments. */
public fun String?.commandIntentDisplayName(): String {
    if (this == null) return ""
    if (none(Char::isCommandIntentSeparator)) return trim()
    return buildString(length) {
        for (char in this@commandIntentDisplayName) append(if (char.isCommandIntentSeparator()) ' ' else char)
    }.trim()
}

private fun Char.isCommandIntentSeparator(): Boolean =
    this in '\u0000'..'\u001f' || this in '\u007f'..'\u009f' || this == '\u2028' || this == '\u2029'
