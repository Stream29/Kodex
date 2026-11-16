package io.github.stream29.kodex.openai.modelcatalog

import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.coroutines.flow.StateFlow

/**
 * Observable model metadata used by Agent composition and configuration.
 *
 * [models] is the current published snapshot; successful provider refreshes
 * replace it. Callers may [resolve] a requested model against it. Catalog
 * refresh work is owned by this component, while the injected client remains
 * owned by its caller.
 *
 * An authentication-aware catalog refreshes when authentication changes and
 * cancels obsolete automatic requests. Changing accounts or becoming unavailable
 * discards the previous account's remote metadata before fetching a new catalog.
 * A failed refresh may retain a snapshot only for the same account; otherwise
 * the implementation's initial fallback remains available.
 */
public interface OpenAiModelCatalogStore : AutoCloseable {
    /** Latest atomically published model snapshot in provider order. */
    public val models: StateFlow<List<ModelInfo>>

    /**
     * Resolves [model] against the latest snapshot using the longest matching
     * slug prefix. If no direct match exists, a single provider namespace
     * segment may be stripped before matching. The result retains the
     * requested slug, even when a more general entry supplied its metadata.
     * An unknown slug still receives conservative fallback metadata.
     */
    public fun resolve(model: OpenAiModelId): ModelInfo

    /** Stops catalog-owned refresh work without closing the injected client. */
    override fun close()
}
