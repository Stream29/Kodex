@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.coroutines.CoroutineScope

/**
 * In-memory one-shot title generation for one Agent runtime.
 *
 * This object only addresses [KodexAgentState]. It has no Session identifier,
 * catalog, or UI selection state.
 */
public expect class AgentTitleGeneration(scope: CoroutineScope) : AutoCloseable {
    /**
     * Starts one best-effort request for the first nonblank text input.
     *
     * A disabled setting or a non-default thread name consumes the first text
     * without starting a request. Image-only input remains eligible.
     */
    public suspend fun start(
        agentState: KodexAgentState,
        content: List<ContentItem>,
        enabled: Boolean,
        model: OpenAiModelId?,
        reasoningEffort: ReasoningEffort,
        generator: SessionTitleGenerator,
    ): Boolean

    /** Cancels any pending automatic result and permanently consumes this Agent's chance. */
    public suspend fun suppress()

    /**
     * Serializes one destructive history replacement against title generation.
     *
     * [replacement] performs the replacement and returns whether the retained
     * history still contains an accepted nonblank user text. A retained text
     * keeps the one-shot gate consumed; removing every such text makes the next
     * accepted text eligible again.
     */
    public suspend fun replaceHistory(replacement: suspend () -> Boolean)

    /** Serializes an explicit rename ahead of any generated title. */
    public suspend fun renameThread(agentState: KodexAgentState, threadName: String): Int

    /** Serializes an explicit thread-name settings update ahead of generated output. */
    public suspend fun updateSettings(agentState: KodexAgentState, settings: KodexAgentSettings): Int
    override fun close()
}
