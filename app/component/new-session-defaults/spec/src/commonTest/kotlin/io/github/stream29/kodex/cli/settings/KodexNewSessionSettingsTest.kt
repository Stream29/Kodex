package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalSerializationApi::class)
val kodexNewSessionSettingsTest by testSuite {
    val json = Json { encodeDefaults = true }

    test("original defaults descriptor and RPC golden survive the move") {
        val serializer = KodexNewSessionSettings.serializer()
        val defaults = KodexNewSessionSettings()
        assertEquals(OpenAiModelId("gpt-5.6-sol"), defaults.model)
        assertEquals(ReasoningEffort.Medium, defaults.reasoningEffort)
        assertEquals(ServiceTier.Default, defaults.serviceTier)
        assertEquals(RequestUserInputMode.AskUser, defaults.requestUserInputMode)
        assertEquals("io.github.stream29.kodex.cli.settings.KodexNewSessionSettings", serializer.descriptor.serialName)
        val golden = """{"model":"gpt-5.6-sol","reasoningEffort":"medium","serviceTier":"default","requestUserInputMode":"ask_user"}"""
        assertEquals(golden, json.encodeToString(serializer, defaults))
        assertEquals(defaults, json.decodeFromString(serializer, golden))
        assertEquals(defaults, json.decodeFromString(serializer, "{}"))
    }

    test("configured values and missing question mode retain the original behavior") {
        val serializer = KodexNewSessionSettings.serializer()
        val configured = KodexNewSessionSettings(
            model = OpenAiModelId("selected"),
            reasoningEffort = ReasoningEffort.High,
            serviceTier = ServiceTier.Fast,
            requestUserInputMode = RequestUserInputMode.NoQuestion,
        )
        val golden = """{"model":"selected","reasoningEffort":"high","serviceTier":"priority","requestUserInputMode":"no_question"}"""
        assertEquals(golden, json.encodeToString(serializer, configured))
        assertEquals(configured, json.decodeFromString(serializer, golden))
        assertEquals(
            configured.copy(requestUserInputMode = RequestUserInputMode.AskUser),
            json.decodeFromString(serializer, """{"model":"selected","reasoningEffort":"high","serviceTier":"priority"}"""),
        )
        assertFailsWith<IllegalArgumentException> { OpenAiModelId(" ") }
    }
}
