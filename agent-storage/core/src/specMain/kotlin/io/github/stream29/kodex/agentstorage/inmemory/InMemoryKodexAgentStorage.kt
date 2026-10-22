@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.inmemory

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/**
 * Process-local mutable storage for tests and transient agent sessions.
 *
 * This implementation keeps all published values in memory. Construction
 * publishes the required snapshot-zero settings, so it can always back a legal
 * empty agent state without a synthetic compaction point.
 */
public expect class InMemoryKodexAgentStorage(initialSettings: KodexAgentSettings) : MutableKodexAgentStorage {
    override val uri: String
    override val index: MutableIndexVersioned<CleanIndexEntry>
    override val work: MutableIndexVersioned<StableWorkEvent>
    override val settings: MutableIndexVersioned<KodexAgentSettings>
    override val timestamp: MutableIndexVersioned<Instant>
    override val tokenCount: MutableIndexVersioned<TokenCountSnapshot>
    override val unstable: MutableIndexVersioned<List<UnstableCleanEvent>>

    public companion object {
        /** Creates the uninitialized storage backing a freshly spawned AgentSession. */
        public fun empty(): InMemoryKodexAgentStorage
    }
}
