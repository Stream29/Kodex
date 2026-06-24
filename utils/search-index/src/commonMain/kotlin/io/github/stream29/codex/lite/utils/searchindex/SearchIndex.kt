package io.github.stream29.codex.lite.utils.searchindex

public data class SearchDocument<T>(
    public val value: T,
    public val text: String,
)

/**
 * Read-only search index built from a fixed document snapshot.
 *
 * Implementations must not require callers to update the index after creation;
 * create a new index when the document set changes.
 */
public interface SearchIndex<T> {
    public fun search(query: String, limit: Int): List<T>
}

/**
 * Builds a read-only search index from the supplied document snapshot.
 */
public fun <T> createSearchIndex(documents: List<SearchDocument<T>>): SearchIndex<T> =
    createPlatformSearchIndex(documents)

internal expect fun <T> createPlatformSearchIndex(documents: List<SearchDocument<T>>): SearchIndex<T>
