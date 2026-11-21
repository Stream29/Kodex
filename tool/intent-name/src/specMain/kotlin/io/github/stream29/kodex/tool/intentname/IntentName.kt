package io.github.stream29.kodex.tool.intentname

/** Tests an intent label without trimming, rewriting or interpreting its content. */
public fun String.isValidIntentName(): Boolean =
    isNotBlank() && none {
        it in '\u0000'..'\u001f' || it in '\u007f'..'\u009f' || it == '\u2028' || it == '\u2029'
    }
