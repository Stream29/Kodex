# Kodex

Kotlin Multiplatform implementation for Codex.

## Try It

Go to releases.

## Reimplementation and Abstraction

### Storage

Six timelines are persisted in the file system in flat JSON:
- stable index events and compaction points;
- completed provider and tool work;
- request settings;
- timestamps;
- context-token counts;
- unfinished interactions.

All the timelines are simply indexed with integers.
For each timeline, there is a directory contains `1.json`, `3.json`, etc.
The index follows "happens-before" semantics.
As we get the `latestIndex()` and `floor()` the index to a specific point in time,
a snapshot is determined without blocking writing the storage.

For almost all the situations, only the latest parts of a timeline are parsed and loaded.
Parsing the whole timeline is avoided.

### Agent State Atomic Transition

`AgentState` provides a set of atomic transitions that can be applied to the state of an agent:
- `requestResponseApi(): RequestFinish`
- `compact(CompactionTrigger, CompactionReason, CompactionPhase)`
- `injectHistory(List<StableIndexEvent.Steerable>)`
- `appendUserMessage(List<ContentItem>)`
- `completeToolCall(StableEvent.CompletedTool)`
- `updateSettings(AgentSettings)`

And the union of `AgentStateValue` is exposed as `StateFlow`:

```kotlin
sealed interface AgentStateValue {
    data object Empty : AgentStateValue
    data object UserMessage : AgentStateValue
    data object AssistantMessage : AgentStateValue
    data class ToolPending(val events: List<PendingToolEvent>) : AgentStateValue
    data object ToolCompleted : AgentStateValue
    data object ExternalWrite : AgentStateValue
    sealed interface RequestResponse : AgentStateValue {
        data object Started : RequestResponse
        data class Message(val events: SharedFlow<ResponsesStreamEvent>) : RequestResponse
        data class AgentMessage(val events: SharedFlow<ResponsesStreamEvent>) : RequestResponse
        data class Reasoning(val events: SharedFlow<ResponsesStreamEvent>) : RequestResponse
        data class ToolCall(val events: SharedFlow<ResponsesStreamEvent>) : RequestResponse
        data class Unknown(val events: SharedFlow<ResponsesStreamEvent>) : RequestResponse
    }
    data object Compacting : AgentStateValue
}
```

### Handle Effects by Runtime Layers

`AgentRuntime.resume()` is the aspect of the runtime that handles effects.
It is implemented as a stack of layers:

```text
turn hooks
  -> tool dispatch
    -> steer delivery
      -> compaction and response continuation
        -> AgentState atomic operations
```

Each layer is a decorator of its inner layer and wraps `resume()` and owns one kind of effect.
For example, the Tool Dispatch layer handles tool calls:

```kotlin
override suspend fun resume() {
    innerLayer.resume()
    while (stateValue is ToolPending) {
        completePendingTools()
        innerLayer.resume()
    }
    // Let the outer layers handle the rest.
}
```
