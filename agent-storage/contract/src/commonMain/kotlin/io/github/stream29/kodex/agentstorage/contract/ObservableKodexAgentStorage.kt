package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Instant

/** Read-only storage view exposing each timeline's own cache metadata. */
public interface ObservableKodexAgentStorage : KodexAgentStorage {
    public override val index: CachedIndexVersioned<CleanIndexEntry>
    public override val work: CachedIndexVersioned<StableWorkEvent>
    public override val settings: CachedIndexVersioned<KodexAgentSettings>
    public override val timestamp: CachedIndexVersioned<Instant>
    public override val tokenCount: CachedIndexVersioned<Long>
    public override val unstable: CachedIndexVersioned<List<UnstableCleanEvent>>
}
