package io.github.stream29.codexlite

import kotlin.test.Test
import kotlin.test.assertEquals

class CodexLiteTest {
    @Test
    fun greetingReturnsLibraryName() {
        assertEquals("CodexLite", CodexLite.greeting())
    }
}
