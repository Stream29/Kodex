package io.github.stream29.codex.lite.utils.ktorclientext

import io.ktor.http.HeadersBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadersBuilderExtensionsTest {
    @Test
    fun nullableSetReplacesExistingValueWhenValueIsPresent() {
        val headers = HeadersBuilder()
        val value: String? = "new"

        headers.append("X-Test", "old")
        headers["X-Test"] = value

        assertEquals(listOf("new"), headers.getAll("X-Test"))
    }

    @Test
    fun nullableSetRemovesExistingValueWhenValueIsNull() {
        val headers = HeadersBuilder()
        val value: String? = null

        headers.append("X-Test", "old")
        headers["X-Test"] = value

        assertNull(headers.getAll("X-Test"))
    }

    @Test
    fun addAllReplacesExistingValuesWithMapValues() {
        val headers = HeadersBuilder()

        headers.append("X-Test", "old")
        headers.addAll(
            mapOf(
                "X-Test" to "new",
                "X-Other" to "other",
            )
        )

        assertEquals(listOf("new"), headers.getAll("X-Test"))
        assertEquals(listOf("other"), headers.getAll("X-Other"))
    }
}
