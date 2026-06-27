package io.github.stream29.codex.lite.tool.contract

import kotlinx.serialization.Serializable

/**
 * Mirrors Rust `ToolName`.
 *
 * @property namespace Nullable because plain function tools are not namespaced;
 * `null` means route by `name` only.
 */
@Serializable
public data class ToolName(
    public val name: String,
    public val namespace: String? = null,
) {
    init {
        require(name.isNotBlank()) { "`name` must not be blank" }
        require(namespace == null || namespace.isNotBlank()) { "`namespace` must not be blank" }
    }

    override fun toString(): String =
        namespace?.let { "$it.$name" } ?: name

    public companion object {
        public fun plain(name: String): ToolName =
            ToolName(name = name)

        public fun namespaced(namespace: String, name: String): ToolName =
            ToolName(name = name, namespace = namespace)
    }
}
