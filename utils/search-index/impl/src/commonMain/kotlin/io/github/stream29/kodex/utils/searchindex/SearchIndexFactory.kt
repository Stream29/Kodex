package io.github.stream29.kodex.utils.searchindex

/**
 * Builds the platform search implementation for a fixed document snapshot.
 */
public fun <T> createSearchIndex(documents: List<SearchDocument<T>>): SearchIndex<T> =
    createPlatformSearchIndex(documents.toList())

internal expect fun <T> createPlatformSearchIndex(documents: List<SearchDocument<T>>): SearchIndex<T>
