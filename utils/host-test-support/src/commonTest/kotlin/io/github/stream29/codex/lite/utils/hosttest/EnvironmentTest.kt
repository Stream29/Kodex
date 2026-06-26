package io.github.stream29.codex.lite.utils.hosttest

import kotlin.test.Test
import kotlin.test.assertNull

class EnvironmentTest {
    @Test
    fun missingEnvironmentVariableReturnsNull() {
        assertNull(environmentVariable("CODEX_LITE_TEST_ENVIRONMENT_VARIABLE_THAT_SHOULD_NOT_EXIST"))
    }
}
