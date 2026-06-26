package io.github.stream29.codex.lite.utils.osenvironment

import kotlin.test.Test
import kotlin.test.assertNull

class OsEnvironmentTest {
    @Test
    fun missingEnvironmentVariableReturnsNull() {
        assertNull(environmentVariable("CODEX_LITE_TEST_ENVIRONMENT_VARIABLE_THAT_SHOULD_NOT_EXIST"))
    }
}
