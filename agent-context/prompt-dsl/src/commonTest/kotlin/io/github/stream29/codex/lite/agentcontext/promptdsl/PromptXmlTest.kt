package io.github.stream29.codex.lite.agentcontext.promptdsl

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val promptXmlTest by testSuite {
    test("renders nested prompt tags with indentation") {
        assertEquals(
            """
            <environment_context>
              <cwd>/workspace</cwd>
              <shell>bash</shell>
            </environment_context>
            """.trimIndent(),
            promptXml {
                tag("environment_context") {
                    tag("cwd") { text("/workspace") }
                    tag("shell") { +"bash" }
                }
            },
        )
    }

    test("escapes text and attribute values") {
        assertEquals(
            "<environment id=\"&quot;&lt;&gt;&amp;&apos;\">a &lt; b &amp; c &gt; d</environment>",
            promptXml {
                tag("environment", attributes = mapOf("id" to "\"<>&'")) {
                    text("a < b & c > d")
                }
            },
        )
    }

    test("preserves compact prompt fragments when requested") {
        assertEquals(
            "<root><item>value</item><item>next</item></root>",
            promptXml(indented = false) {
                tag("root") {
                    tag("item") { text("value") }
                    tag("item") { text("next") }
                }
            },
        )
    }
}
