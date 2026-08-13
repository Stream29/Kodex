package io.github.stream29.kodex.app.pathpicker.contract

import kotlinx.io.files.Path

/** One-shot result emitted after the current resolved directory is confirmed. */
public sealed interface DirectoryPickerEffect {
    public data class DirectorySelected(
        public val directory: Path,
    ) : DirectoryPickerEffect
}
