package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.sessiontitle.DefaultSessionTitleModel
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
val sessionTitleSettingsTest by testSuite {
    val json = Json { encodeDefaults = true }

    test("original title defaults descriptor and nullable model golden are preserved") {
        val serializer = SessionTitleSettings.serializer()
        val defaults = SessionTitleSettings()
        assertEquals(true, defaults.enabled)
        assertEquals(null, defaults.model)
        assertEquals(ReasoningEffort.Low, defaults.reasoningEffort)
        assertEquals(OpenAiModelId("gpt-5.3-codex-spark"), DefaultSessionTitleModel)
        assertEquals("io.github.stream29.kodex.cli.settings.SessionTitleSettings", serializer.descriptor.serialName)
        val golden = """{"enabled":true,"model":null,"reasoningEffort":"low"}"""
        assertEquals(golden, json.encodeToString(serializer, defaults))
        assertEquals(defaults, json.decodeFromString(serializer, golden))
        assertEquals(defaults, json.decodeFromString(serializer, "{}"))
    }

    test("configured title values and explicit null round trip without a replacement DTO") {
        val serializer = SessionTitleSettings.serializer()
        val value = SessionTitleSettings(false, OpenAiModelId("selected-title"), ReasoningEffort.Medium)
        val golden = """{"enabled":false,"model":"selected-title","reasoningEffort":"medium"}"""
        assertEquals(golden, json.encodeToString(serializer, value))
        assertEquals(value, json.decodeFromString(serializer, golden))
        assertEquals(
            value.copy(model = null),
            json.decodeFromString(serializer, """{"enabled":false,"model":null,"reasoningEffort":"medium"}"""),
        )
    }
}
