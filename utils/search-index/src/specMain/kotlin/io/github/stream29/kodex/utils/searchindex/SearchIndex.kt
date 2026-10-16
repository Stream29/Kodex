package io.github.stream29.kodex.utils.searchindex

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
