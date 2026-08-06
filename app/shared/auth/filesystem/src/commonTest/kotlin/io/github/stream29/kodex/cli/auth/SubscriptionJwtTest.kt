package io.github.stream29.kodex.cli.auth

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

val subscriptionJwtTest by testSuite {
    test("top-level email takes precedence over profile email") {
        val claims = jwt(
            buildJsonObject {
                put("email", "top-level@example.com")
                put(
                    "https://api.openai.com/profile",
                    buildJsonObject {
                        put("email", "profile@example.com")
                    },
                )
            },
        ).subscriptionJwtClaims()

        assertEquals("top-level@example.com", claims.email)
    }

    test("profile email is used when top-level email is absent") {
        val claims = jwt(
            buildJsonObject {
                put(
                    "https://api.openai.com/profile",
                    buildJsonObject {
                        put("email", "profile@example.com")
                    },
                )
            },
        ).subscriptionJwtClaims()

        assertEquals("profile@example.com", claims.email)
    }

    test("blank email claims are ignored") {
        val claims = jwt(
            buildJsonObject {
                put("email", "")
                put(
                    "https://api.openai.com/profile",
                    buildJsonObject {
                        put("email", " ")
                    },
                )
            },
        ).subscriptionJwtClaims()

        assertNull(claims.email)
    }
}

private fun jwt(payload: JsonObject): String {
    val header = buildJsonObject {
        put("alg", "none")
    }
    return listOf(header, payload)
        .joinToString(".") { value ->
            Base64.UrlSafe.encode(value.toString().encodeToByteArray()).trimEnd('=')
        } + "."
}
