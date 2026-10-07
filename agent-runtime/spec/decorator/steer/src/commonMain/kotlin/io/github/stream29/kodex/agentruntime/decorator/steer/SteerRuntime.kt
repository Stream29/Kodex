package io.github.stream29.kodex.agentruntime.decorator.steer

import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer

/**
 * Runtime layer that delivers pending input within the current logical turn.
 *
 * It belongs outside compaction and inside tool handling. It does not own or
 * directly clear the host's pending input queue: [SteerProvider] atomically
 * transfers ownership when the state can append a user message. A claimed
 * nonempty batch is injected into AgentState in order within this logical turn.
 */
public interface SteerRuntime : ResumableAgentLayer {
    /**
     * Checks for pending input before the first inner resume and again after
     * each inner result whose state permits another user message. A nonempty
     * claim is persisted before the next inner resume; a late claim therefore
     * continues within the same outer turn.
     *
     * If the state is not appendable, [SteerProvider.take] is not called and
     * pending input stays available for a later boundary. If the provider
     * returns no input, this layer returns after the current inner result.
     */
    public override suspend fun resume()
}
