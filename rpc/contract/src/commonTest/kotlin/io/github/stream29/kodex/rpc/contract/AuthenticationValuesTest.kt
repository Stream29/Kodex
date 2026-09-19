package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val authenticationValuesTest by testSuite {
    val json = Json { encodeDefaults = true }

    test("authentication summary round trips all plans without credential fields") {
        for (plan in listOf(null) + OpenAiSubscriptionPlan.entries) {
            val state = SettingsAuthenticationState.Authenticated(
                accountId = "test-account",
                planType = plan,
                email = "test@example.invalid",
            )
            val encoded = json.encodeToJsonElement(SettingsAuthenticationState.serializer(), state)
            assertEquals(setOf("type", "accountId", "planType", "email"), encoded.jsonObject.keys)
            assertEquals(state, json.decodeFromJsonElement(SettingsAuthenticationState.serializer(), encoded))
        }
    }

    test("authentication summary round trips every unavailable reason") {
        for (reason in OpenAiAuthState.Unavailable.entries) {
            val state = SettingsAuthenticationState.Unavailable(reason)
            val encoded = json.encodeToString(SettingsAuthenticationState.serializer(), state)
            assertEquals(state, json.decodeFromString(SettingsAuthenticationState.serializer(), encoded))
        }
    }

    test("both authentication sources retain their existing wire names") {
        for ((source, name) in listOf(KodexAuthSource.Codex to "codex", KodexAuthSource.Kodex to "kodex")) {
            val encoded = json.encodeToString(KodexAuthSource.serializer(), source)
            assertEquals("\"$name\"", encoded)
            assertEquals(source, json.decodeFromString(KodexAuthSource.serializer(), encoded))
        }
    }

    test("existing value validation remains active when decoding") {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(
                SettingsAuthenticationState.Authenticated.serializer(),
                """{"accountId":"","planType":null,"email":null}""",
            )
        }
    }
}
