package io.github.stream29.kodex.utils.osenvironment

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertNull



val osEnvironmentTest by testSuite {
    test("missing environment variable returns null") {
        assertNull(environmentVariable("KODEX_TEST_ENVIRONMENT_VARIABLE_THAT_SHOULD_NOT_EXIST"))
    }
}
