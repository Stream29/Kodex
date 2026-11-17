package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stream29.kodex.openai.LoadableToolSpec
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.WebSearchAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Shared disclosure mechanics only; each built-in owns its fields and content layout. */
@Composable
internal fun BuiltInToolEvent(
    summary: String,
    firstLine: String,
    status: String,
    expansionKey: Any,
    expandedSummary: String = summary,
    content: @Composable HistoryContentDetailsScope.() -> Unit = {},
) {
    ToolEvent(
        summary = summary,
        rawName = null,
        status = status,
        expansionKey = expansionKey,
        expandedSummary = expandedSummary,
    ) {
        LocalHistoryRenderer.current.RecordedMetadata(firstLine, HistoryTextStyle.Normal)
        content()
    }
}

@Composable
internal fun RecordedField(label: String, value: String) {
    // Empty output and whitespace input are meaningful. Do not trim either.
    WrappedHistoryText("$label: $value")
}

@Composable
internal fun RecordedErrorField(label: String, value: String) {
    LocalHistoryRenderer.current.RecordedError("$label: $value", HistoryTextStyle.Normal)
}

internal fun WebSearchAction?.operationTitles(): List<String> = when (this) {
    is WebSearchAction.Search -> buildList {
        query?.takeIf(String::isNotEmpty)?.let { add("Search $it") }
        queries.orEmpty().forEach { add("Search $it") }
    }
    is WebSearchAction.OpenPage -> listOfNotNull(url?.takeIf(String::isNotEmpty)?.let { "Open $it" })
    is WebSearchAction.FindInPage -> listOfNotNull(
        when {
            pattern != null && url != null -> "Find \"$pattern\" in $url"
            pattern != null -> "Find \"$pattern\""
            url != null -> "Find in $url"
            else -> null
        },
    )
    WebSearchAction.Other, null -> emptyList()
}

internal fun JsonElement.toolSearchContext(name: String): String =
    if (this is JsonObject) compactToolLine(
        name,
        *entries.filter { (_, value) -> value != JsonNull }
            .map { (key, value) -> key to value.toString() }.toTypedArray(),
    ) else "$name; arguments=$this"

/**
 * Hosted strings have no format discriminator. Only explicit HTTP(S)/file URI
 * references are displayed; opaque strings and data/base64 payloads stay hidden.
 * No URI is resolved or opened.
 */
internal fun String.readableHostedImageReference(): String? =
    takeIf { matches(Regex("(?:https?://|file:///)[^\\s]+")) }

@Composable
internal fun HistoryContentDetailsScope.DiscoveredTools(
    tools: List<LoadableToolSpec>,
    renderDefinition: @Composable (String) -> Unit = { WrappedHistoryText(it) },
) {
    tools.forEachIndexed { index, tool ->
        key(index, tool) {
            // Serialize only after the user opens this definition, preserving namespace wrappers.
            val names = when (tool) {
                is ResponsesApiTool -> listOf(tool.name)
                is ResponsesApiNamespace -> tool.tools.map { member ->
                    when (member) {
                        is ResponsesApiTool -> "${tool.name}.${member.name}"
                    }
                }.ifEmpty { listOf(tool.name) }
            }
            names.forEachIndexed { memberIndex, name ->
                key(memberIndex) {
                    section(name) {
                        val definition = remember(tool) {
                            ToolDefinitionJson.encodeToString(LoadableToolSpec.serializer(), tool)
                        }
                        renderDefinition(definition)
                    }
                }
            }
        }
    }
}

internal fun isRedesignedInternalCall(name: String): Boolean = name in setOf(
    "exec_command", "write_stdin", "apply_patch", "read_media", "await_event",
    "web.run", "tool_search", "server_tool_search", "clock.curr_time",
    "get_context_remaining", "image_gen.imagegen", "view_image",
)

internal fun isReadOnlyUtility(name: String): Boolean =
    name == "clock.curr_time" || name == "get_context_remaining"

private val ToolDefinitionJson = Json {
    prettyPrint = true
    encodeDefaults = true
}
