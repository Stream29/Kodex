package io.github.stream29.kodex.app.pathpicker.contract

import kotlinx.io.files.Path

/**
 * One-shot result emitted after the current resolved directory is confirmed.
 *
 * The renderer or parent component must dismiss the picker after consuming
 * this effect. It must not treat the effect as persistent state or recreate a
 * second selection state from it.
 */
public sealed interface DirectoryPickerEffect {
    public data class DirectorySelected(
        public val directory: Path,
    ) : DirectoryPickerEffect
}
