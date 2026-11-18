package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ResponseItem

public actual fun AgentCommit.toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
    when (this) {
        is StableUserMessage -> toResponseHistoryItems()
        is StableAskUserEvent -> toResponseHistoryItems()
        is StableScheduleEvent -> toResponseHistoryItems()
        is StableTerminalExitedEvent -> toResponseHistoryItems()
        is StableSubagentCreatedEvent -> toResponseHistoryItems()
        is StableSubagentCompletedEvent -> toResponseHistoryItems()
        is StableAssistantMessage -> toResponseHistoryItems()
        is StableDeveloperMessage -> toResponseHistoryItems()
        is StableAgentMessage -> toResponseHistoryItems()
        is StablePlanUpdate -> toResponseHistoryItems()
        is StableContextCompaction -> toResponseHistoryItems()
        is StableReasoning -> toResponseHistoryItems()
        is StableCommandExecution -> toResponseHistoryItems()
        is StableAwait -> toResponseHistoryItems()
        is StablePatch -> toResponseHistoryItems()
        is StableReadMedia -> toResponseHistoryItems()
        is StableInvalidToolCall -> toResponseHistoryItems()
        is StableServerToolSearch -> toResponseHistoryItems()
        is StableWebSearchCall -> toResponseHistoryItems()
        is StableImageGenerationCall -> toResponseHistoryItems()
        is StableJsonTool -> toResponseHistoryItems()
        is StableTextTool -> toResponseHistoryItems()
        is StableCustomTool -> toResponseHistoryItems()
        is KodexAgentSettings, is LlmRequestMeta, is UnstableSnapshot, is SteerSnapshot, is AskUserSnapshot ->
            throw IllegalArgumentException("Commit ${commitType.wireName} is not a conversation record.")
    }
