package dev.kauri.framework

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GtkLanguageFallbackTest {
    @Test
    fun fallbackOnlyForPosixLanguages() {
        listOf("C", "C.UTF-8", "C.utf8", "POSIX", "POSIX.UTF-8").forEach {
            assertTrue(needsGtkLanguageFallback(it), it)
        }
        listOf(null, "en_US.UTF-8", "zh_CN", "en", "cy").forEach {
            assertFalse(needsGtkLanguageFallback(it), it)
        }
    }
}
