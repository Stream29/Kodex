package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ResponseItem

public actual fun AgentCommit.toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
    when (this) {
        is StableUserMessage -> toResponseHistoryItems()
        is StableAskUserEvent -> toResponseHistoryItems()
        is StableScheduleEvent -> toResponseHistoryItems()
        is StableTerminalExitedEvent -> toResponseHistoryItems()
        is StableAssistantMessage -> toResponseHistoryItems()
        is StableDeveloperMessage -> toResponseHistoryItems()
        is StableAgentMessage -> toResponseHistoryItems()
        is StablePlanUpdate -> toResponseHistoryItems()
        is StableContextCompaction -> toResponseHistoryItems()
        is StableReasoning -> toResponseHistoryItems()
        is StableCommandExecutionToolEvent -> toResponseHistoryItems()
        is StableAwaitEventToolEvent -> toResponseHistoryItems()
        is StablePatchToolEvent -> toResponseHistoryItems()
        is StableReadMediaToolEvent -> toResponseHistoryItems()
        is StableInvalidToolCall -> toResponseHistoryItems()
        is StableServerToolSearch -> toResponseHistoryItems()
        is StableWebSearchCall -> toResponseHistoryItems()
        is StableImageGenerationCall -> toResponseHistoryItems()
        is StableJsonToolEvent -> toResponseHistoryItems()
        is StableTextToolEvent -> toResponseHistoryItems()
        is StableCustomToolEvent -> toResponseHistoryItems()
        is KodexAgentSettings, is LlmRequestMeta, is UnstableSnapshot, is SteerSnapshot, is AskUserSnapshot ->
            throw IllegalArgumentException("Commit ${commitType.wireName} is not a conversation record.")
    }
