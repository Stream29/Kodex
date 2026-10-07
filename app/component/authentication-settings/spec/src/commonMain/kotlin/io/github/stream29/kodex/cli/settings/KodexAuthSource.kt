package io.github.stream29.kodex.cli.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Selects whether subscription credentials come from Codex or Kodex storage. */
@Serializable
public enum class KodexAuthSource {
    /** Backend loads, refreshes and saves the fixed user-home `.codex/auth.json`. */
    @SerialName("codex")
    Codex,

    /** Backend loads, refreshes and saves Kodex private auth.yml. */
    @SerialName("kodex")
    Kodex,
}
