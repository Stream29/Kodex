package io.github.stream29.kodex.utils.kodexhome

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val kodexHomeTest by testSuite {
    test("default Home is the original host path without a provider projection") {
        assertEquals(Path(requireUserHomeDirectory(), ".kodex"), KodexHome)
    }
}
