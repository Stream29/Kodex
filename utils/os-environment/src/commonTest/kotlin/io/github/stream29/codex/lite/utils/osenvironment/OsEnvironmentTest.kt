package io.github.stream29.codex.lite.utils.osenvironment

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertNull



val osEnvironmentTest by testSuite {
    test("missing environment variable returns null") {
        assertNull(environmentVariable("CODEX_LITE_TEST_ENVIRONMENT_VARIABLE_THAT_SHOULD_NOT_EXIST"))
    }
}
