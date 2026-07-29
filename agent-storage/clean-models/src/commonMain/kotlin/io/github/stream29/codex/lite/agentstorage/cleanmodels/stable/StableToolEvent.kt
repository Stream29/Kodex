package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Stable clean projection of a completed tool interaction.
 */
@Serializable
public sealed interface StableToolEvent : StableCleanEvent

/**
 * Fallback stable projection for a dynamic tool whose arguments and result are JSON.
 *
 * MCP interactions use this fallback with the complete `CallToolResult`
 * envelope as [result]; projections must not flatten that envelope first.
 *
 * @property name Tool name shown to users.
 * @property namespace Nullable because plain function tools are not namespaced;
 * `null` means route or display by [name] only.
 * @property arguments Decoded tool-call arguments.
 * @property result Decoded JSON result returned by the tool.
 * @property success Nullable because a function-call output may omit an
 * explicit success value.
 */
@Serializable
@SerialName("json_tool_event")
public data class StableJsonToolEvent(
    public val name: String,
    public val namespace: String? = null,
    public val arguments: JsonElement,
    public val result: JsonElement,
    public val success: Boolean? = null,
) : StableToolEvent

/**
 * Fallback stable projection for a dynamic tool with JSON arguments and a text result.
 *
 * @property name Tool name shown to users.
 * @property namespace Nullable because plain function tools are not namespaced;
 * `null` means route or display by [name] only.
 * @property arguments Decoded tool-call arguments.
 * @property result Raw text returned by the tool.
 * @property success Nullable because a function-call output may omit an
 * explicit success value.
 */
@Serializable
@SerialName("text_tool_event")
public data class StableTextToolEvent(
    public val name: String,
    public val namespace: String? = null,
    public val arguments: JsonElement,
    public val result: String,
    public val success: Boolean? = null,
) : StableToolEvent
