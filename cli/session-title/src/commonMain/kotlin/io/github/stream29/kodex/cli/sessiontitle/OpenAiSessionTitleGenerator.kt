package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.TextControls
import io.github.stream29.kodex.openai.TextFormat
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Responses API implementation of the Codex App-style session title request. */
public class OpenAiSessionTitleGenerator(
    private val client: OpenAiClient,
) : SessionTitleGenerator {
    override suspend fun generateTitle(
        userText: String,
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
    ): SessionTitleGenerationResult {
        val streamedText = StringBuilder()
        val completedItemsText = StringBuilder()
        var terminal: TitleStreamTerminal = TitleStreamTerminal.Open
        client.createResponse(titleRequest(userText, model, reasoningEffort)).collect { event ->
            when (event) {
                is ResponsesStreamEvent.OutputTextDelta -> streamedText.append(event.delta)
                is ResponsesStreamEvent.OutputItemDone -> {
                    if (streamedText.isEmpty()) {
                        event.item.outputTextOrNull()?.let(completedItemsText::append)
                    }
                }

                is ResponsesStreamEvent.Completed -> terminal = TitleStreamTerminal.Completed(event.response)
                is ResponsesStreamEvent.Failed -> terminal = TitleStreamTerminal.Rejected(
                    event.response.error?.message ?: "The title response failed.",
                )

                is ResponsesStreamEvent.Incomplete -> terminal = TitleStreamTerminal.Rejected(
                    event.response.incompleteDetails?.reason ?: "The title response was incomplete.",
                )

                else -> Unit
            }
        }

        val completed = when (val result = terminal) {
            TitleStreamTerminal.Open -> return SessionTitleGenerationResult.Rejected(
                "The title stream closed before response.completed.",
            )

            is TitleStreamTerminal.Rejected -> return SessionTitleGenerationResult.Rejected(result.reason)
            is TitleStreamTerminal.Completed -> result.response
        }
        val rawTitle = streamedText.toString()
            .ifBlank { completedItemsText.toString() }
            .ifBlank { completed.outputText.orEmpty() }
            .ifBlank { completed.output.joinToString(separator = "") { item -> item.outputTextOrNull().orEmpty() } }
        if (rawTitle.isBlank()) {
            return SessionTitleGenerationResult.Rejected("The completed title response contained no text.")
        }
        val decodedTitle = runCatching {
            OpenAiJsonCodec.decodeFromString<SessionTitleOutput>(rawTitle).title
        }.getOrDefault(rawTitle)
        val normalized = sanitizeGeneratedSessionTitle(decodedTitle)
            ?: return SessionTitleGenerationResult.Rejected("The generated title was invalid after normalization.")
        return SessionTitleGenerationResult.Generated(normalized)
    }
}

private fun titleRequest(
    userText: String,
    model: OpenAiModelId,
    reasoningEffort: ReasoningEffort,
): ResponsesApiRequest =
    ResponsesApiRequest(
        model = model,
        input = listOf(
            ResponseItem.Message(
                role = MessageRole.User,
                content = listOf(
                    ContentItem.InputText(
                        "User prompt:\n${userText.takeUnicodeScalars(SessionTitleInputLimit).trim()}\n",
                    ),
                ),
            ),
        ),
        instructions = SessionTitlePrompt,
        store = false,
        tools = emptyList(),
        parallelToolCalls = false,
        reasoning = Reasoning(effort = reasoningEffort),
        text = TextControls(
            format = TextFormat(
                name = "session_title",
                schema = OpenAiJsonCodec.encodeToJsonElement(SessionTitleOutputSchema).jsonObject,
            ),
        ),
    )

/**
 * @return Text carried by a completed assistant message, or `null` when this
 * item has no model output text.
 */
private fun ResponseItem.outputTextOrNull(): String? =
    (this as? ResponseItem.Message)
        ?.takeIf { message -> message.role == MessageRole.Assistant }
        ?.content
        ?.filterIsInstance<ContentItem.OutputText>()
        ?.joinToString(separator = "") { item -> item.text }
        ?.takeIf(String::isNotEmpty)

internal fun sanitizeGeneratedSessionTitle(value: String): String? {
    val firstLine = value
        .replace("\r\n", "\n")
        .lineSequence()
        .firstOrNull { line -> line.isNotBlank() }
        ?.trim()
        ?: return null
    val withoutPrefix = firstLine.removeTitlePrefix()
    val normalized = withoutPrefix
        .trim('"', '\'', '`', '“', '”', '‘', '’')
        .split(Regex("\\s+"))
        .joinToString(" ")
        .trimEnd('.', '?', '!')
        .trim()
    val length = normalized.unicodeScalarCount()
    if (length < SessionTitleMinimumLength) return null
    if (length <= SessionTitleMaximumLength) return normalized
    return normalized
        .takeUnicodeScalars(SessionTitleMaximumLength - 1)
        .trimEnd() + "…"
}

private fun String.removeTitlePrefix(): String {
    val prefix = take(SessionTitlePrefix.length)
    if (!prefix.equals(SessionTitlePrefix, ignoreCase = true)) return this
    val separator = getOrNull(SessionTitlePrefix.length) ?: return this
    if (separator != ':' && !separator.isWhitespace()) return this
    return drop(SessionTitlePrefix.length)
        .dropWhile { character -> character == ':' || character.isWhitespace() }
}

private fun String.takeUnicodeScalars(limit: Int): String {
    var offset = 0
    var count = 0
    while (offset < length && count < limit) {
        offset = nextUnicodeScalarOffset(offset)
        count += 1
    }
    return substring(0, offset)
}

private fun String.unicodeScalarCount(): Int {
    var offset = 0
    var count = 0
    while (offset < length) {
        offset = nextUnicodeScalarOffset(offset)
        count += 1
    }
    return count
}

private fun String.nextUnicodeScalarOffset(offset: Int): Int =
    if (
        this[offset] in Char.MIN_HIGH_SURROGATE..Char.MAX_HIGH_SURROGATE &&
        offset + 1 < length && this[offset + 1] in Char.MIN_LOW_SURROGATE..Char.MAX_LOW_SURROGATE
    ) {
        offset + 2
    } else {
        offset + 1
    }

@Serializable
private data class SessionTitleOutput(val title: String)

private sealed interface TitleStreamTerminal {
    data object Open : TitleStreamTerminal
    data class Completed(val response: Response) : TitleStreamTerminal
    data class Rejected(val reason: String) : TitleStreamTerminal
}

internal const val SessionTitleInputLimit: Int = 2_000
internal const val SessionTitleMinimumLength: Int = 18
internal const val SessionTitleMaximumLength: Int = 36
private const val SessionTitlePrefix: String = "title"

private const val SessionTitlePrompt: String =
    """You are a helpful assistant. You will be presented with a user prompt, and your job is to provide a short title for a task that will be created from that prompt.
The tasks typically have to do with coding-related tasks, for example requests for bug fixes or questions about a codebase. The title you generate will be shown in the UI to represent the prompt.
Generate a concise UI title (18-36 characters) for this task.
Return JSON with exactly one field: {"title": "..."}.
The title value must be plain text. No quotes or trailing punctuation.
Do not use markdown or formatting characters.
If the task includes a ticket reference (e.g. ABC-123), include it verbatim.

Generate a clear, informative task title based solely on the prompt provided. Follow the rules below to ensure consistency, readability, and usefulness.

How to write a good title:
Generate a single-line title that captures the question or core change requested. The title should be easy to scan and useful in changelogs or review queues.
- Use an imperative verb first: "Add", "Fix", "Update", "Refactor", "Remove", "Locate", "Find", etc.
- Aim for 18-36 characters; keep under 5 words where possible.
- Capitalize only the first word unless locale requires otherwise.
- Write the title in the user's locale.
- Do not use punctuation at the end.
- Output the title as plain text with no surrounding quotes or backticks.
- Use precise, non-redundant language.
- Translate fixed phrases into the user's locale (e.g., "Fix bug" -> "Corrige el error" in Spanish-ES), but leave code terms in English unless a widely adopted translation exists.
- If the user provides a title explicitly, reuse it (translated if needed) and skip generation logic.
- Make it clear when the user is requesting changes (use verbs like "Fix", "Add", etc) vs asking a question (use verbs like "Find", "Locate", "Count").
- Do NOT respond to the user, answer questions, or attempt to solve the problem; just write a title that can represent the user's query.

Examples:
- User: "Can we add dark-mode support to the settings page?" -> Add dark-mode support
- User: "Fehlerbehebung: Beim Anmelden erscheint 500." (de-DE) -> Login-Fehler 500 beheben
- User: "Refactoriser le composant sidebar pour reduire le code duplique." (fr-FR) -> Refactoriser composant sidebar
- User: "How do I fix our login bug?" -> Troubleshoot login bug
- User: "Where in the codebase is foo_bar created" -> Locate foo_bar
- User: "what's 2+2" -> Calculate 2+2

By following these conventions, your titles will be readable, changelog-friendly, and helpful to both users and downstream tools."""
