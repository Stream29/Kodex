package io.github.stream29.codex.lite.utils.ktorclientext

import de.infix.testBalloon.framework.core.testSuite

import io.ktor.http.HeadersBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNull



val headersBuilderExtensionsTest by testSuite {
    test("nullable set replaces existing value when value is present") {
        val headers = HeadersBuilder()
        val value: String? = "new"

        headers.append("X-Test", "old")
        headers["X-Test"] = value

        assertEquals(listOf("new"), headers.getAll("X-Test"))
    }

    test("nullable set removes existing value when value is null") {
        val headers = HeadersBuilder()
        val value: String? = null

        headers.append("X-Test", "old")
        headers["X-Test"] = value

        assertNull(headers.getAll("X-Test"))
    }

    test("add all replaces existing values with map values") {
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
