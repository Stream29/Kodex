package io.github.stream29.kodex.agentstorage.cleanmodels.stable

/**
 * Direct O(1) call identifier for completed local tool events, or `null` for
 * hosted provider tool events ([StableServerToolSearch], [StableWebSearchCall],
 * [StableImageGenerationCall]).
 */
public val AgentCommit.CompletedTool.callId: String?
    get() = when (this) {
        is StablePlanUpdate -> callId
        is StableCommandExecution -> callId
        is StablePatch -> callId
        is StableReadMedia -> callId
        is StableAwait -> callId
        is StableInvalidToolCall -> callId
        is StableJsonTool -> callId
        is StableTextTool -> callId
        is StableCustomTool -> callId
        is StableServerToolSearch,
        is StableWebSearchCall,
        is StableImageGenerationCall,
        -> null
    }
