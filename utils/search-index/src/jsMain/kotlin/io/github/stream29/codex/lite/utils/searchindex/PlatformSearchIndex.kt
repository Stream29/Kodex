package io.github.stream29.codex.lite.utils.searchindex

internal actual fun <T> createPlatformSearchIndex(documents: List<SearchDocument<T>>): SearchIndex<T> =
    FallbackSearchIndex(documents)

private class FallbackSearchIndex<T>(
    private val documents: List<SearchDocument<T>>,
) : SearchIndex<T> {
    override fun search(query: String, limit: Int): List<T> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) {
            return emptyList()
        }

        return documents
            .asSequence()
            .map { document -> document to score(document.text, queryTokens) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (document, _) -> document.value }
            .toList()
    }

    private fun score(text: String, queryTokens: Set<String>): Int {
        val textTokens = tokenize(text)
        return queryTokens.sumOf { token ->
            if (token in textTokens) 2 else textTokens.count { it.contains(token) || token.contains(it) }
        }
    }

    private fun tokenize(text: String): Set<String> =
        text
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filterTo(mutableSetOf()) { it.isNotBlank() }
}
