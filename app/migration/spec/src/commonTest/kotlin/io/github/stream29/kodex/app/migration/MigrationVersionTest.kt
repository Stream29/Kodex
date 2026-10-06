package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

public val migrationVersionTest by testSuite {
    test("parses compares and formats three integer components") {
        val version = MigrationVersion("10.2.3")

        assertEquals(10, version.major)
        assertEquals(2, version.minor)
        assertEquals(3, version.patch)
        assertEquals("10.2.3", version.toString())
        assertTrue(MigrationVersion(1, 10, 0) > MigrationVersion(1, 2, 99))
    }

    test("rejects non-canonical and out-of-range versions") {
        listOf(
            "1",
            "1.0",
            "1.0.0.0",
            "01.0.0",
            "1.0.0-alpha",
            "1.0.0+build",
            "2147483648.0.0",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> { MigrationVersion(value) }
        }
        assertFailsWith<IllegalArgumentException> { MigrationVersion(-1, 0, 0) }
    }
}
