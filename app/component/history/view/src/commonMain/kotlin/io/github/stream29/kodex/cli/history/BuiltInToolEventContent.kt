package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stream29.kodex.openai.LoadableToolSpec
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.SearchCommands
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
        WrappedHistoryText(firstLine)
        content()
    }
}

@Composable
internal fun RecordedField(label: String, value: String) {
    // Empty output and whitespace input are meaningful. Do not trim either.
    WrappedHistoryText("$label: $value")
}

/** Every actual operation is represented, in model order, without lossy previews. */
internal fun SearchCommands.operationTitles(): List<String> = buildList {
    searchQuery.orEmpty().forEach { query ->
        add(compactToolLine(
            "Search ${query.q}",
            "domains" to query.domains?.takeIf { it.isNotEmpty() }?.joinToString(),
            "recency" to query.recency,
        ))
    }
    imageQuery.orEmpty().forEach { query ->
        add(compactToolLine(
            "Search images ${query.q}",
            "domains" to query.domains?.takeIf { it.isNotEmpty() }?.joinToString(),
            "recency" to query.recency,
        ))
    }
    open.orEmpty().forEach { add("Open ${it.refId}" + (it.lineno?.let { n -> " at line $n" } ?: "")) }
    click.orEmpty().forEach { add("Click link ${it.id} in ${it.refId}") }
    find.orEmpty().forEach { add("Find \"${it.pattern}\" in ${it.refId}") }
    screenshot.orEmpty().forEach { add("Screenshot ${it.refId} page ${it.pageno}") }
    finance.orEmpty().forEach {
        add(compactToolLine(
            "Finance ${it.ticker}",
            "type" to it.type.name.lowercase(),
            "market" to it.market?.takeIf(String::isNotEmpty),
        ))
    }
    weather.orEmpty().forEach {
        add(compactToolLine(
            "Weather ${it.location}",
            "start" to it.start?.takeIf(String::isNotEmpty),
            "duration" to it.duration,
        ))
    }
    sports.orEmpty().forEach {
        add(compactToolLine(
            "Sports ${it.league.name.lowercase()} ${it.function.name.lowercase()}",
            "tool" to it.tool?.name?.lowercase(),
            "team" to it.team?.takeIf(String::isNotEmpty),
            "opponent" to it.opponent?.takeIf(String::isNotEmpty),
            "date_from" to it.dateFrom?.takeIf(String::isNotEmpty),
            "date_to" to it.dateTo?.takeIf(String::isNotEmpty),
            "num_games" to it.numGames,
            "locale" to it.locale?.takeIf(String::isNotEmpty),
        ))
    }
    time.orEmpty().forEach { add("Time ${it.utcOffset}") }
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

internal fun recordedImageTitle(savedPath: String?): String =
    savedPath?.takeIf(String::isNotBlank)
        ?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.takeIf(String::isNotBlank)?.let { "Generate $it" }
        ?: "Generate image"

/**
 * Hosted strings have no format discriminator. Only explicit HTTP(S)/file URI
 * references are displayed; opaque strings and data/base64 payloads stay hidden.
 * No URI is resolved or opened.
 */
internal fun String.readableHostedImageReference(): String? =
    takeIf { matches(Regex("(?:https?://|file:///)[^\\s]+")) }

@Composable
internal fun HistoryContentDetailsScope.DiscoveredTools(tools: List<LoadableToolSpec>) {
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
                        WrappedHistoryText(definition)
                    }
                }
            }
        }
    }
}

internal fun isRedesignedInternalCall(name: String): Boolean = name in setOf(
    "exec_command", "write_stdin", "apply_patch", "web.run", "tool_search",
    "server_tool_search", "clock.curr_time", "get_context_remaining",
    "image_gen.imagegen", "view_image",
)

internal fun isReadOnlyUtility(name: String): Boolean =
    name == "clock.curr_time" || name == "get_context_remaining"

private val ToolDefinitionJson = Json {
    prettyPrint = true
    encodeDefaults = true
}
