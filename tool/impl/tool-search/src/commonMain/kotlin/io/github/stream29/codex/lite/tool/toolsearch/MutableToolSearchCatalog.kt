package io.github.stream29.codex.lite.tool.toolsearch

import io.github.stream29.codex.lite.openai.ToolSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dynamic deferred-tool directory with a lazily rebuilt search index.
 *
 * [replaceDocuments] publishes a complete ordered snapshot. The BM25 index is
 * rebuilt lazily only when that snapshot changes.
 */
public class MutableToolSearchCatalog(
    initialDocuments: List<ToolSearchDocument>,
) {
    private val documents = MutableStateFlow(initialDocuments.toList())
    private val indexMutex = Mutex()
    private var indexedDocuments: List<ToolSearchDocument>? = null
    private var searchEngine: ToolSearchEngine? = null

    /** Replaces the searchable directory atomically. */
    public fun replaceDocuments(documents: List<ToolSearchDocument>) {
        this.documents.value = documents.toList()
    }

    /** Returns the client tool-search definition for the current directory. */
    public fun currentSpec(): ToolSpec.ToolSearch {
        val currentDocuments = documents.value
        return ToolSearchTools.createToolSearchSpec(
            searchableSources = currentDocuments.mapNotNull(ToolSearchDocument::sourceInfo),
        )
    }

    /** Searches the current directory, rebuilding its index after catalog changes. */
    public suspend fun search(arguments: SearchToolCallParams): ToolSearchResult = indexMutex.withLock {
        val currentDocuments = documents.value
        if (currentDocuments != indexedDocuments) {
            indexedDocuments = currentDocuments
            searchEngine = ToolSearchEngine(currentDocuments)
        }
        checkNotNull(searchEngine).search(arguments)
    }

    internal val indexedDocumentSnapshot: List<ToolSearchDocument>?
        get() = indexedDocuments
}
