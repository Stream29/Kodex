package io.github.stream29.codex.lite.utils.searchindex

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchIndexTest {
    @Test
    fun searchReturnsMatchingDocuments() {
        val index = createSearchIndex(
            listOf(
                SearchDocument("calendar", "create calendar event"),
                SearchDocument("filesystem", "read and write local files"),
            ),
        )

        assertEquals(listOf("calendar"), index.search("calendar event", limit = 1))
    }
}
